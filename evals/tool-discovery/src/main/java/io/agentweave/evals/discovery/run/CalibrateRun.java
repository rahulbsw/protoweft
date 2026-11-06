package io.agentweave.evals.discovery.run;

import io.agentweave.discovery.Confidence.Level;
import io.agentweave.evals.discovery.Json;
import io.agentweave.evals.discovery.calibration.Calibration;
import io.agentweave.evals.discovery.calibration.Calibration.Metrics;
import io.agentweave.evals.discovery.calibration.Calibration.Signals;
import io.agentweave.evals.discovery.calibration.Calibration.Suggestion;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.TaskSampler;
import io.agentweave.evals.discovery.report.GitInfo;
import io.agentweave.evals.discovery.report.Markdown;
import io.agentweave.evals.discovery.report.ResultsWriter;
import io.agentweave.evals.discovery.report.RunInfo;
import io.agentweave.evals.discovery.retrieval.CatalogRetriever;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The {@code calibrate} command: for each catalog retriever, searches every task of the offline
 * retrieval data (datasets × catalog sizes, as in {@link RetrievalRun}), records the confidence
 * signals and whether a gold tool was at rank 1 / in the top 5, splits tasks into dev and test by a
 * stable hash of the task id, grid-searches {@code Confidence.Policy} thresholds on dev
 * ({@link Calibration}) and reports default vs suggested on both splits. Writes
 * {@code calibration.json}; nothing is applied.
 *
 * @see Calibration
 */
public final class CalibrateRun {

    /**
     * @param devFraction   share of tasks in the dev split (the rest is the held-out test split)
     * @param minHighShare  constraint: HIGH on at least this share of dev queries
     * @param maxFalseAlarm constraint: LOW on at most this share of dev queries that hit in the top 5
     */
    public record Config(List<String> datasets, List<String> retrievers, List<Integer> catalogSizes, int maxQueries,
                         long seed, String distractors, double devFraction, double minHighShare, double maxFalseAlarm,
                         Path mcpDir, Path toolretDir, Path outDir, Path repoDir) {
    }

    private final Config config;
    private final Function<String, CatalogRetriever> retrievers;

    public CalibrateRun(Config config, Function<String, CatalogRetriever> retrievers) {
        this.config = Objects.requireNonNull(config, "config");
        this.retrievers = Objects.requireNonNull(retrievers, "retrievers");
    }

    /** Whether a task is in the dev split (stable across runs and machines). */
    static boolean isDev(String taskId, double devFraction) {
        long h = 1125899906842597L;
        for (int i = 0; i < taskId.length(); i++) {
            h = 31 * h + taskId.charAt(i);
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return Math.floorMod(h, 10_000) < Math.round(devFraction * 10_000);
    }

    public Path run() {
        DataSources data = new DataSources(config.mcpDir(), config.toolretDir(), config.datasets());
        String runId = RunIds.next("calibrate", config.repoDir());
        Map<String, List<Signals>> dev = new LinkedHashMap<>();
        Map<String, List<Signals>> test = new LinkedHashMap<>();
        Map<String, List<Signals>> perCell = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        try (ResultsWriter out = new ResultsWriter(config.outDir().resolve(runId))) {
            for (String spec : config.datasets()) {
                List<EvalTask> tasks = TaskSampler.sample(data.tasks(spec), config.maxQueries(), config.seed());
                Map<String, EvalTool> own = data.ownTools(spec);
                List<EvalTask> usable = tasks.stream().filter(t -> own.keySet().containsAll(t.goldToolIds())).toList();
                List<EvalTool> gold = usable.stream().flatMap(t -> t.goldToolIds().stream()).distinct().map(own::get).toList();
                List<EvalTool> pool = data.pool(spec, config.distractors());
                for (int size : config.catalogSizes()) {
                    EvalCatalog catalog;
                    try {
                        catalog = EvalCatalog.builder().required(gold).distractorPool(pool).size(size)
                                .seed(config.seed()).build();
                    } catch (IllegalArgumentException e) {
                        notes.add(spec + " N=" + size + ": skipped (" + e.getMessage() + ")");
                        continue;
                    }
                    for (String rname : config.retrievers()) {
                        CatalogRetriever r = retrievers.apply(rname);
                        r.index(catalog);
                        String cell = spec + "|" + rname + "|" + catalog.size();
                        for (EvalTask t : usable) {
                            CatalogRetriever.Search s = r.search(t, catalog, 10);
                            List<String> ids = s.toolIds();
                            boolean hit1 = !ids.isEmpty() && relevant(t, ids.get(0));
                            boolean hit5 = ids.stream().limit(5).anyMatch(id -> relevant(t, id));
                            Signals sig = Signals.of(s.result(), hit1, hit5);
                            boolean isDev = isDev(t.id(), config.devFraction());
                            (isDev ? dev : test).computeIfAbsent(rname, k -> new ArrayList<>()).add(sig);
                            perCell.computeIfAbsent(cell, k -> new ArrayList<>()).add(sig);
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("dataset", spec);
                            row.put("retriever", rname);
                            row.put("catalogSize", catalog.size());
                            row.put("taskId", t.id());
                            row.put("split", isDev ? "dev" : "test");
                            row.put("level", sig.reported().name());
                            row.put("coverage", nan(sig.coverage()));
                            row.put("margin", nan(sig.margin()));
                            row.put("agreement", sig.agreement());
                            row.put("cosine", nan(sig.cosine()));
                            row.put("hit1", hit1);
                            row.put("hit5", hit5);
                            out.row(row);
                        }
                    }
                }
            }
            RunInfo info = new RunInfo(runId, "calibrate", Instant.now().toString(), GitInfo.detect(config.repoDir()),
                    data.infos(), configMap());
            Map<String, Object> suggestions = new LinkedHashMap<>();
            StringBuilder md = new StringBuilder(Markdown.header("Confidence calibration (offline)", info));
            md.append("Suggestions only: nothing is applied. Objective on the dev split: precision of HIGH ")
                    .append("(gold at rank 1) + recall of LOW among misses (no gold in the top 5), subject to HIGH on ≥ ")
                    .append(pct(config.minHighShare())).append(" of queries and LOW on ≤ ")
                    .append(pct(config.maxFalseAlarm())).append(" of top-5 hits. Test = held-out split.\n\n");
            md.append(levelTable(perCell));
            for (String rname : config.retrievers()) {
                List<Signals> d = dev.getOrDefault(rname, List.of());
                List<Signals> te = test.getOrDefault(rname, List.of());
                if (d.isEmpty()) {
                    continue;
                }
                Suggestion s = Calibration.suggest(d, te, config.minHighShare(), config.maxFalseAlarm(),
                        productDefault(rname));
                suggestions.put(rname, suggestionMap(s, d.size(), te.size()));
                md.append(suggestionSection(rname, s));
            }
            if (!notes.isEmpty()) {
                md.append("## Notes\n\n");
                notes.forEach(n -> md.append("- ").append(n).append('\n'));
            }
            Map<String, Object> summary = new LinkedHashMap<>(info.toMap());
            summary.put("applied", false);
            summary.put("objective", "precisionHigh(hit@1|HIGH) + lowRecallOfMisses(LOW|miss@5) on dev; constraints "
                    + "highShare >= minHighShare, lowFalseAlarm(LOW|hit@5) <= maxFalseAlarm");
            summary.put("suggestions", suggestions);
            summary.put("notes", notes);
            out.summary(summary);
            write(out.dir().resolve("calibration.json"), Json.writePretty(summary));
            out.report(md.toString());
            return out.dir();
        }
    }

    /** The thresholds the catalog uses by default for this retriever's embedder (keyword-only: DEFAULT). */
    static io.agentweave.discovery.Confidence.Policy productDefault(String retriever) {
        if (retriever.contains("hybrid") || retriever.contains("dense")) {
            return io.agentweave.discovery.Confidence.Policy.BGE_SMALL_EN_V15;
        }
        return io.agentweave.discovery.Confidence.Policy.DEFAULT;
    }

    /** Relevant for retrieval: graded gold (alternatives do not count, as in the retrieval metrics). */
    private static boolean relevant(EvalTask t, String toolId) {
        return t.goldRelevance().getOrDefault(toolId, 0) > 0;
    }

    private static String levelTable(Map<String, List<Signals>> perCell) {
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Signals>> e : perCell.entrySet()) {
            String[] k = e.getKey().split("\\|");
            Metrics m = Calibration.metrics(productDefault(k[1]), e.getValue());
            List<String> row = new ArrayList<>(List.of(k[0], k[1], k[2], String.valueOf(m.n())));
            for (Level l : Level.values()) {
                row.add(m.counts().get(l) + " (" + pct(m.counts().get(l) / (double) Math.max(1, m.n())) + "): "
                        + pct(m.hit1Rate().get(l)) + " / " + pct(m.hit5Rate().get(l)));
            }
            rows.add(row);
        }
        return "## Levels under the default policy\n\nEach level: count (share): hit@1 / hit@5 rate.\n\n"
                + Markdown.table(List.of("dataset", "retriever", "N", "queries", "HIGH", "MEDIUM", "LOW"), rows);
    }

    private static String suggestionSection(String rname, Suggestion s) {
        StringBuilder md = new StringBuilder("## " + rname + "\n\n");
        md.append(s.feasible() ? "Suggested policy: `" + Calibration.policyMap(s.policy()) + "` (grid of "
                + s.gridSize() + ", objective " + String.format(Locale.ROOT, "%.3f", s.objective()) + ")\n\n"
                : "No grid policy met the constraints; the default is kept.\n\n");
        List<List<String>> rows = new ArrayList<>();
        rows.add(metricRow("default", "dev", s.defaultDev()));
        rows.add(metricRow("suggested", "dev", s.suggestedDev()));
        rows.add(metricRow("default", "test", s.defaultTest()));
        rows.add(metricRow("suggested", "test", s.suggestedTest()));
        md.append(Markdown.table(List.of("policy", "split", "queries", "HIGH share", "P(hit@1 | HIGH)",
                "P(LOW | miss@5)", "P(LOW | hit@5)"), rows));
        return md.toString();
    }

    private static List<String> metricRow(String policy, String split, Metrics m) {
        return List.of(policy, split, String.valueOf(m.n()), pct(m.highShare()), pct(m.precisionHigh()),
                pct(m.lowRecallOfMisses()), pct(m.lowFalseAlarm()));
    }

    private static Map<String, Object> suggestionMap(Suggestion s, int devN, int testN) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("default", Calibration.policyMap(s.defaultPolicy()));
        m.put("suggested", Calibration.policyMap(s.policy()));
        m.put("feasible", s.feasible());
        m.put("gridSize", s.gridSize());
        m.put("objective", Double.isNaN(s.objective()) ? "NaN" : s.objective());
        m.put("devQueries", devN);
        m.put("testQueries", testN);
        m.put("defaultDev", s.defaultDev().toMap());
        m.put("suggestedDev", s.suggestedDev().toMap());
        m.put("defaultTest", s.defaultTest().toMap());
        m.put("suggestedTest", s.suggestedTest().toMap());
        return m;
    }

    private static String pct(double d) {
        return Double.isNaN(d) ? "–" : String.format(Locale.ROOT, "%.1f%%", 100 * d);
    }

    private static Object nan(double d) {
        return Double.isNaN(d) ? "NaN" : d;
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> configMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasets", config.datasets());
        m.put("retrievers", config.retrievers());
        m.put("catalogSizes", config.catalogSizes());
        m.put("maxQueries", config.maxQueries());
        m.put("seed", config.seed());
        m.put("distractors", config.distractors());
        m.put("devFraction", config.devFraction());
        m.put("minHighShare", config.minHighShare());
        m.put("maxFalseAlarm", config.maxFalseAlarm());
        return m;
    }
}
