package io.github.rahulbsw.protoweft.evals.discovery.run;

import io.github.rahulbsw.protoweft.evals.discovery.arms.Arm;
import io.github.rahulbsw.protoweft.evals.discovery.arms.ArmResult;
import io.github.rahulbsw.protoweft.evals.discovery.arms.MultiTurn;
import io.github.rahulbsw.protoweft.evals.discovery.arms.SelectedCall;
import io.github.rahulbsw.protoweft.evals.discovery.fixtures.FixtureStore;
import io.github.rahulbsw.protoweft.evals.discovery.fixtures.MockResultProvider;
import io.github.rahulbsw.protoweft.evals.discovery.cost.BudgetExceededException;
import io.github.rahulbsw.protoweft.evals.discovery.cost.CostGuard;
import io.github.rahulbsw.protoweft.evals.discovery.cost.GuardedModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.cost.PriceTable;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.TaskSampler;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenCountingClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.metrics.CallScorer;
import io.github.rahulbsw.protoweft.evals.discovery.report.GitInfo;
import io.github.rahulbsw.protoweft.evals.discovery.report.ResultsWriter;
import io.github.rahulbsw.protoweft.evals.discovery.report.RunInfo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The LLM arm: catalog sizes × trials × tasks × models × arms, in that nesting, so that when the
 * cost guard stops a run every (model, arm) has seen the same tasks and paired comparisons stay
 * valid. Every call is guarded unless it is a dry run; each scored task is flushed to
 * {@code calls.jsonl} immediately and the summary/report are written even after a stop.
 */
public final class LlmRun {

    /**
     * @param datasets             task sources ({@code mcptoolbench}, {@code toolret:<subset>})
     * @param arms                 arm specs (see {@link io.github.rahulbsw.protoweft.evals.discovery.arms.Arms})
     * @param models               {@code provider:model} specs
     * @param catalogSizes         N values, run in ascending order
     * @param tasks                stratified task sample size per dataset (0 = all)
     * @param catalogScope         {@code shared} (one catalog for all tasks; N must cover their gold tools) or
     *                             {@code per-task} (each task gets its own N-tool catalog)
     * @param distractors          {@code own}, {@code toolret} or {@code both}
     * @param maxConsecutiveErrors abort a (model, arm, N) cell after this many consecutive call errors
     * @param settings             model settings for the report header (temperature, effort, …)
     * @param multiTurn            multi-turn mocked-execution config, or {@code null} for single-turn
     */
    public record Config(List<String> datasets, List<String> arms, List<String> models, List<Integer> catalogSizes,
                         int trials, int tasks, long seed, int maxOutputTokens, int maxTurns, String catalogScope,
                         String distractors, int maxConsecutiveErrors, boolean dryRun, Map<String, Object> settings,
                         Path mcpDir, Path toolretDir, Path outDir, Path repoDir, MultiTurnConfig multiTurn) {
    }

    /**
     * Multi-turn mocked-execution settings (opt-in {@code --multiturn}). A selected tool call is
     * answered with a mocked result and the loop continues, so result and history tokens are
     * exercised and counted.
     *
     * @param resultFixtures JSONL file or directory of recorded {@link io.github.rahulbsw.protoweft.evals.discovery.fixtures.ResultFixture}s
     * @param fallbackTokens target result size for a fixture miss when no fixtures were recorded
     */
    public record MultiTurnConfig(Path resultFixtures, int fallbackTokens) {
    }

    /** Builds an arm from its spec; {@code multiTurn} is {@code null} for single-turn runs. */
    @FunctionalInterface
    public interface ArmBuilder {
        Arm build(String spec, Map<String, String> defaults, TokenEstimator estimator, MultiTurn multiTurn);
    }

    private final Config config;
    private final Map<String, ModelClient> clients;
    private final CostGuard guard;
    private final PriceTable prices;
    private final ArmBuilder armBuilder;
    private final io.github.rahulbsw.protoweft.evals.discovery.metrics.LlmJudge judge;
    private final String judgeKey;
    private final TokenEstimator estimator = TokenEstimator.o200k();

    public LlmRun(Config config, Map<String, ModelClient> clients, CostGuard guard, PriceTable prices,
                  ArmBuilder armBuilder) {
        this(config, clients, guard, prices, armBuilder, null);
    }

    /**
     * @param clients     model spec → client (real, scripted, or estimating for dry runs)
     * @param guard       the cost guard; ignored for dry runs, required otherwise
     * @param prices      prices for cost columns (may be empty)
     * @param judgeClient optional model client that grades free-text final answers, or {@code null}
     */
    public LlmRun(Config config, Map<String, ModelClient> clients, CostGuard guard, PriceTable prices,
                  ArmBuilder armBuilder, ModelClient judgeClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.clients = new LinkedHashMap<>(clients);
        this.guard = config.dryRun() ? null : Objects.requireNonNull(guard, "guard");
        this.prices = prices == null ? PriceTable.empty() : prices;
        this.armBuilder = Objects.requireNonNull(armBuilder, "armBuilder");
        this.judge = judgeClient == null ? null
                : new io.github.rahulbsw.protoweft.evals.discovery.metrics.LlmJudge(
                        config.dryRun() ? judgeClient : new GuardedModelClient(judgeClient, guard, estimator));
        this.judgeKey = judgeClient == null ? null : judgeClient.key();
    }

    private MultiTurn buildMultiTurn() {
        MultiTurnConfig mt = config.multiTurn();
        if (mt == null) {
            return null;
        }
        FixtureStore store = FixtureStore.load(mt.resultFixtures());
        MockResultProvider provider = new MockResultProvider(store, estimator, mt.fallbackTokens(), config.seed());
        return new MultiTurn(provider, config.maxTurns());
    }

    /** Runs and returns the results directory. */
    public Path run() {
        DataSources data = new DataSources(config.mcpDir(), config.toolretDir(), config.datasets());
        List<EvalTask> tasks = new ArrayList<>();
        Map<String, EvalTool> goldTools = new LinkedHashMap<>();
        Map<String, EvalTool> pool = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (String spec : config.datasets()) {
            Map<String, EvalTool> own = data.ownTools(spec);
            List<EvalTask> sampled = TaskSampler.sample(data.tasks(spec), config.tasks(), config.seed());
            for (EvalTask t : sampled) {
                if (own.keySet().containsAll(t.goldToolIds())) {
                    tasks.add(t);
                    t.goldToolIds().forEach(id -> goldTools.put(id, own.get(id)));
                } else {
                    notes.add("skipped " + t.id() + ": gold tool missing from the dataset");
                }
            }
            data.pool(spec, config.distractors()).forEach(t -> pool.putIfAbsent(t.id(), t));
        }
        Map<String, String> armDefaults = Map.of("maxOutputTokens", String.valueOf(config.maxOutputTokens()),
                "maxTurns", String.valueOf(config.maxTurns()), "seed", String.valueOf(config.seed()));
        MultiTurn multiTurn = buildMultiTurn();
        List<Arm> arms = config.arms().stream().map(s -> armBuilder.build(s, armDefaults, estimator, multiTurn)).toList();
        Map<String, ModelClient> guarded = new LinkedHashMap<>();
        clients.forEach((spec, c) -> guarded.put(spec, config.dryRun() ? c : new GuardedModelClient(c, guard, estimator)));

        GitInfo git = GitInfo.detect(config.repoDir()); // at start: the code that runs
        String runId = RunIds.next(config.dryRun() ? "llm-dryrun" : "llm", git);
        String startedAt = Instant.now().toString();
        LlmReport report = new LlmReport(config, prices);
        String stopReason = null;
        Map<String, Integer> consecutiveErrors = new HashMap<>();
        Set<String> aborted = new HashSet<>();
        try (ResultsWriter out = new ResultsWriter(config.outDir().resolve(runId))) {
            List<Integer> sizes = config.catalogSizes().stream().sorted().toList();
            outer:
            for (int n : sizes) {
                Map<String, EvalCatalog> catalogs;
                try {
                    catalogs = catalogs(tasks, goldTools, new ArrayList<>(pool.values()), n);
                } catch (IllegalArgumentException e) {
                    notes.add("N=" + n + " skipped: " + e.getMessage());
                    continue;
                }
                for (int trial = 1; trial <= config.trials(); trial++) {
                    for (EvalTask task : tasks) {
                        EvalCatalog catalog = catalogs.get(task.id());
                        for (Map.Entry<String, ModelClient> m : guarded.entrySet()) {
                            for (Arm arm : arms) {
                                String cell = m.getValue().key() + "|" + arm.name() + "|" + n;
                                if (aborted.contains(cell)) {
                                    continue;
                                }
                                ArmResult result;
                                try {
                                    result = arm.run(task, catalog, m.getValue());
                                } catch (BudgetExceededException e) {
                                    stopReason = e.getMessage();
                                    break outer;
                                }
                                Map<String, Object> row = row(task, catalog, n, trial, m.getValue(), result,
                                        exactCount(clients.get(m.getKey())));
                                // Judge after the agent row is built: if judging exhausts the budget,
                                // keep the completed agent row and clean-stop like an agent call does.
                                boolean budgetStop = false;
                                if (judge != null && result.error() == null) {
                                    try {
                                        var verdict = judge.judge(task, result.finalText());
                                        row.put("judgedSuccess", verdict.pass());
                                        row.put("judgeReason", verdict.reason());
                                    } catch (BudgetExceededException e) {
                                        stopReason = e.getMessage();
                                        budgetStop = true;
                                    }
                                }
                                out.row(row);
                                report.add(cell, row);
                                if (budgetStop) {
                                    break outer;
                                }
                                if (result.error() != null) {
                                    int errs = consecutiveErrors.merge(cell, 1, Integer::sum);
                                    if (errs >= config.maxConsecutiveErrors()) {
                                        aborted.add(cell);
                                        notes.add(cell + " aborted after " + errs + " consecutive errors; last: "
                                                + result.error());
                                    }
                                } else {
                                    consecutiveErrors.put(cell, 0);
                                }
                            }
                        }
                    }
                }
            }
            RunInfo info = new RunInfo(runId, "llm", startedAt, git, data.infos(),
                    configMap());
            Map<String, Object> spend = new LinkedHashMap<>();
            if (guard != null) {
                spend.put("runSpentUsd", guard.runSpentUsd());
                spend.put("runCalls", guard.runCalls());
                spend.put("ledgerTotalUsd", guard.ledger().totalUsd());
                spend.put("budgetUsd", guard.budgetUsd());
            }
            out.summary(report.summary(info, stopReason, spend, notes));
            out.report(report.markdown(info, stopReason, spend, notes));
            return out.dir();
        }
    }

    private Map<String, EvalCatalog> catalogs(List<EvalTask> tasks, Map<String, EvalTool> gold, List<EvalTool> pool,
                                              int n) {
        Map<String, EvalCatalog> out = new HashMap<>();
        if (config.catalogScope().equals("per-task")) {
            for (EvalTask t : tasks) {
                List<EvalTool> req = t.goldToolIds().stream().map(gold::get).toList();
                out.put(t.id(), EvalCatalog.builder().required(req).distractorPool(pool).size(n)
                        .seed(config.seed() ^ t.id().hashCode()).build());
            }
        } else if (config.catalogScope().equals("shared")) {
            EvalCatalog shared = EvalCatalog.builder().required(gold.values()).distractorPool(pool).size(n)
                    .seed(config.seed()).build();
            tasks.forEach(t -> out.put(t.id(), shared));
        } else {
            throw new IllegalArgumentException("--catalog-scope must be shared or per-task");
        }
        return out;
    }

    private static Long exactCount(ModelClient base) {
        return base instanceof TokenCountingClient tc ? tc.drain() : null;
    }

    private Map<String, Object> row(EvalTask task, EvalCatalog catalog, int n, int trial, ModelClient client,
                                    ArmResult r, Long exactInput) {
        CallScorer.TaskScore s = CallScorer.score(task, catalog, r);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", client.key());
        row.put("arm", r.arm());
        row.put("catalogSize", n);
        row.put("catalogFingerprint", catalog.fingerprint());
        row.put("trial", trial);
        row.put("taskId", task.id());
        row.put("dataset", task.dataset());
        row.put("gold", task.goldToolIds());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (SelectedCall c : r.calls()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("toolId", c.toolId());
            cm.put("name", c.name());
            cm.put("arguments", c.arguments());
            if (c.argumentError() != null) {
                cm.put("argumentError", c.argumentError());
            }
            calls.add(cm);
        }
        row.put("calls", calls);
        row.put("firstToolId", s.firstToolId());
        row.put("noCall", s.noCall());
        row.put("selectionCorrect", s.selectionCorrect());
        row.put("selectionCorrectStrict", s.selectionCorrectStrict());
        row.put("realCalls", s.realCalls());
        row.put("wrongCalls", s.wrongCalls());
        row.put("callCorrect", s.callCorrect());
        row.put("argKeysCorrect", s.argKeysCorrect());
        row.put("argumentMismatches", s.argumentMismatches());
        row.put("reFetches", s.reFetches());
        row.put("goldToolsCovered", s.goldToolsCovered());
        row.put("requiredFactsPresent", s.requiredFactsPresent());
        row.put("taskSuccess", s.taskSuccess());
        row.put("discoveryCalls", r.discoveryCalls());
        row.put("modelCalls", r.modelCalls());
        row.put("toolsOffered", r.toolsOffered());
        row.put("inputTokens", r.usage().inputTokens());
        row.put("outputTokens", r.usage().outputTokens());
        row.put("cachedInputTokens", r.usage().cachedInputTokens());
        row.put("cacheWriteTokens", r.usage().cacheWriteTokens());
        row.put("toolPayloadTokensEst", r.toolPayloadTokens());
        row.put("estDefinitionTokens", r.estimated().definitions());
        row.put("estResultTokens", r.estimated().results());
        row.put("estHistoryTokens", r.estimated().history());
        row.put("estSystemTokens", r.estimated().system());
        row.put("estInputTokens", r.estimated().total());
        row.put("estDefinitionTokensPerCall", r.definitionTokensPerCall());
        row.put("reconciliationResidual", r.reconciliationResidual());
        row.put("reconciliationResidualPerCall", r.reconciliationResidualPerCall());
        row.put("perRequest", r.perRequest().stream()
                .map(rt -> Map.of("realInput", rt.realInput(), "est", rt.estimate().total(),
                        "def", rt.estimate().definitions(), "res", rt.estimate().results(),
                        "hist", rt.estimate().history(), "residual", rt.residual()))
                .toList());
        row.put("exactInputTokens", exactInput);
        row.put("latencyMs", r.latencyMillis());
        row.put("costUsd", prices.price(client.key()).map(p -> p.cost(r.usage())).orElse(null));
        if (config.dryRun()) {
            row.put("worstCaseUsd", prices.price(client.key())
                    .map(p -> p.worstCase(r.usage().inputTokens(), (long) config.maxOutputTokens() * r.modelCalls()))
                    .orElse(null));
        }
        row.put("hitMaxTurns", r.hitMaxTurns());
        row.put("error", r.error());
        row.put("finalText", r.finalText().length() > 300 ? r.finalText().substring(0, 300) + "…" : r.finalText());
        row.put("extras", r.extras());
        return row;
    }

    private Map<String, Object> configMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasets", config.datasets());
        m.put("arms", config.arms());
        m.put("models", config.models());
        m.put("catalogSizes", config.catalogSizes());
        m.put("trials", config.trials());
        m.put("tasks", config.tasks());
        m.put("seed", config.seed());
        m.put("maxOutputTokens", config.maxOutputTokens());
        m.put("maxTurns", config.maxTurns());
        m.put("catalogScope", config.catalogScope());
        m.put("distractors", config.distractors());
        m.put("dryRun", config.dryRun());
        if (judgeKey != null) {
            m.put("judgeModel", judgeKey);
        }
        if (config.multiTurn() != null) {
            m.put("multiTurn", Map.of("resultFixtures", String.valueOf(config.multiTurn().resultFixtures()),
                    "fallbackTokens", config.multiTurn().fallbackTokens()));
        }
        m.put("modelSettings", config.settings());
        return m;
    }
}
