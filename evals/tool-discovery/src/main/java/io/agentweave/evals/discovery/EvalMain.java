package io.agentweave.evals.discovery;

import io.agentweave.evals.discovery.arms.Arms;
import io.agentweave.evals.discovery.cost.CostGuard;
import io.agentweave.evals.discovery.cost.PriceTable;
import io.agentweave.evals.discovery.cost.SpendLedger;
import io.agentweave.evals.discovery.llm.ModelClient;
import io.agentweave.evals.discovery.llm.ModelClients;
import io.agentweave.evals.discovery.llm.TokenEstimator;
import io.agentweave.evals.discovery.retrieval.DiscoveryRetrieverFactory;
import io.agentweave.evals.discovery.retrieval.Retrievers;
import io.agentweave.evals.discovery.run.CalibrateRun;
import io.agentweave.evals.discovery.run.LlmRun;
import io.agentweave.evals.discovery.run.RetrievalRun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point: {@code run-retrieval}, {@code calibrate}, {@code run-llm}, {@code list}, {@code help}. See
 * {@code evals/tool-discovery/README.md}. API keys are read from the environment only and are
 * never printed.
 */
public final class EvalMain {

    private EvalMain() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    /** Runs a command; returns the exit code. */
    public static int run(String[] args) {
        CliArgs cli = new CliArgs(args);
        switch (cli.command()) {
            case "run-retrieval":
                return runRetrieval(cli);
            case "run-llm":
                return runLlm(cli);
            case "calibrate":
                return calibrate(cli);
            case "list":
                System.out.println("arms: " + Arms.available());
                System.out.println("retrievers: " + Retrievers.available());
                return 0;
            case "help":
            case "--help":
                System.out.println(USAGE);
                return 0;
            default:
                System.err.println("unknown command: " + cli.command() + "\n" + USAGE);
                return 2;
        }
    }

    private static int runRetrieval(CliArgs cli) {
        Path data = Path.of(cli.string("data-dir", "build/data"));
        Path toolret = Path.of(cli.string("toolret-dir", data.resolve("toolret").toString()));
        Map<String, String> ropts = new LinkedHashMap<>();
        long seed = cli.longValue("seed", 42);
        ropts.put("seed", String.valueOf(seed));
        cli.repeated("retriever-opt", List.of()).forEach(kv -> {
            String[] p = kv.split("=", 2);
            ropts.put(p[0], p.length > 1 ? p[1] : "true");
        });
        RetrievalRun.Config config = new RetrievalRun.Config(
                cli.list("datasets", "mcptoolbench"),
                cli.list("retrievers", "random,oracle"),
                cli.integers("k", "1,3,5,10"),
                cli.integers("catalog-sizes", "all"),
                cli.integer("depth", 100),
                cli.integer("max-queries", 0),
                seed,
                cli.string("distractors", defaultDistractors(toolret)),
                Path.of(cli.string("mcptoolbench-dir", data.resolve("mcptoolbench").toString())),
                toolret,
                Path.of(cli.string("out", "build/eval-results")),
                Path.of(cli.string("repo-dir", ".")));
        cli.rejectUnknown();
        Path dir = new RetrievalRun(config, name -> Retrievers.create(name, ropts)).run();
        System.out.println("results: " + dir.resolve("report.md"));
        return 0;
    }

    private static int calibrate(CliArgs cli) {
        Path data = Path.of(cli.string("data-dir", "build/data"));
        Path toolret = Path.of(cli.string("toolret-dir", data.resolve("toolret").toString()));
        Map<String, String> ropts = new LinkedHashMap<>();
        long seed = cli.longValue("seed", 42);
        ropts.put("seed", String.valueOf(seed));
        cli.repeated("retriever-opt", List.of()).forEach(kv -> {
            String[] p = kv.split("=", 2);
            ropts.put(p[0], p.length > 1 ? p[1] : "true");
        });
        CalibrateRun.Config config = new CalibrateRun.Config(
                cli.list("datasets", "mcptoolbench"),
                cli.list("retrievers", "catalog-bm25,catalog-dense,catalog-hybrid"),
                cli.integers("catalog-sizes", "all"),
                cli.integer("max-queries", 0),
                seed,
                cli.string("distractors", defaultDistractors(toolret)),
                cli.decimal("dev-fraction", 0.5),
                cli.decimal("min-high-share", 0.10),
                cli.decimal("max-low-false-alarm", 0.20),
                Path.of(cli.string("mcptoolbench-dir", data.resolve("mcptoolbench").toString())),
                toolret,
                Path.of(cli.string("out", "build/eval-results")),
                Path.of(cli.string("repo-dir", ".")));
        cli.rejectUnknown();
        Path dir = new CalibrateRun(config, name -> DiscoveryRetrieverFactory.catalogRetriever(name, ropts)).run();
        System.out.println("results: " + dir.resolve("report.md") + "\nsuggestion: " + dir.resolve("calibration.json"));
        return 0;
    }

    private static int runLlm(CliArgs cli) {
        Path data = Path.of(cli.string("data-dir", "build/data"));
        Path toolret = Path.of(cli.string("toolret-dir", data.resolve("toolret").toString()));
        boolean dryRun = cli.flag("dry-run");
        ModelClients.Settings settings = new ModelClients.Settings(cli.optionalDecimal("temperature"),
                cli.string("anthropic-effort", null), cli.string("anthropic-thinking", null),
                cli.flag("anthropic-cache-tools"), cli.string("openai-reasoning-effort", null), cli.flag("count-tokens"),
                cli.integer("max-retries", ModelClients.DEFAULT_MAX_RETRIES));
        List<String> models = cli.list("models", ModelClients.DEFAULT_MODELS);
        PriceTable prices = cli.string("prices", null) == null ? PriceTable.empty()
                : PriceTable.load(Path.of(cli.string("prices", null)));
        cli.repeated("price", List.of()).forEach(prices::putFlag);
        double budget = cli.decimal("budget-usd", 0);
        double runCap = cli.decimal("run-cap-usd", 0);
        long maxCalls = cli.longValue("max-calls", 0);
        boolean allowUnpriced = cli.flag("allow-unpriced");
        double safety = cli.decimal("estimate-safety", 1.3);
        Path ledgerPath = Path.of(cli.string("ledger", ".eval-spend.json"));
        LlmRun.Config config = new LlmRun.Config(
                cli.list("datasets", "mcptoolbench"),
                cli.repeated("arm", List.of("all-tools")),
                models,
                cli.integers("catalog-sizes", "50,200,1000"),
                cli.integer("trials", 1),
                cli.integer("tasks", 50),
                cli.longValue("seed", 42),
                cli.integer("max-output-tokens", 4096),
                cli.integer("max-turns", 6),
                cli.string("catalog-scope", "shared"),
                cli.string("distractors", defaultDistractors(toolret)),
                cli.integer("max-consecutive-errors", 3),
                dryRun,
                settings.describe(),
                Path.of(cli.string("mcptoolbench-dir", data.resolve("mcptoolbench").toString())),
                toolret,
                Path.of(cli.string("out", "build/eval-results")),
                Path.of(cli.string("repo-dir", ".")));
        cli.rejectUnknown();
        TokenEstimator estimator = TokenEstimator.o200k();
        Map<String, ModelClient> clients = new LinkedHashMap<>();
        CostGuard guard = null;
        if (dryRun) {
            models.forEach(m -> clients.put(m, ModelClients.estimating(m, estimator)));
        } else {
            if (budget <= 0) {
                throw new IllegalArgumentException("refusing paid model calls: --budget-usd is 0 (the default). "
                        + "Set a budget explicitly, or use --dry-run to see the token/cost plan");
            }
            guard = new CostGuard(prices, budget, runCap, SpendLedger.open(ledgerPath), safety, maxCalls, allowUnpriced);
            for (String m : models) {
                if (prices.price(m).isEmpty() && !allowUnpriced) {
                    throw new IllegalArgumentException("no price for " + m + ": pass --prices <file> or --price "
                            + m + "=<input>,<output> (USD per Mtok, from the provider's pricing page), "
                            + "or --allow-unpriced with --max-calls");
                }
                clients.put(m, ModelClients.create(m, settings));
            }
        }
        Path dir = new LlmRun(config, clients, guard, prices, Arms::create).run();
        System.out.println("results: " + dir.resolve("report.md"));
        return 0;
    }

    private static String defaultDistractors(Path toolretDir) {
        return Files.isDirectory(toolretDir.resolve("tools")) ? "toolret" : "own";
    }

    static final String USAGE = """
            tool-discovery-eval <command> [options]

            commands:
              run-retrieval   offline retrieval metrics (no model calls)
                --datasets mcptoolbench,toolret:<subset>   --retrievers random,oracle
                --k 1,3,5,10   --catalog-sizes all|N,...   --depth 100   --max-queries 0
                --distractors own|toolret|both   --retriever-opt key=value
                retrievers: random, oracle, catalog-bm25, catalog-dense, catalog-hybrid
                (catalog-* options: fallback=none|progressive|all_schemas, min-cosine=x, instruction=true,
                 embedding-cache=build/embedding-cache|none)
              calibrate       Confidence levels vs top-1/top-5 hits per catalog retriever; suggests
                              Confidence.Policy thresholds (dev/test split; writes calibration.json, applies nothing)
                --datasets ...  --retrievers catalog-bm25,...  --catalog-sizes all  --max-queries 0
                --dev-fraction 0.5   --min-high-share 0.10   --max-low-false-alarm 0.20   --retriever-opt key=value
              run-llm         LLM arms over catalogs (paid; guarded)
                --arm all-tools --arm top-k:k=5,retriever=oracle   (repeat --arm)
                --arm generic-search:inlineSchemas=0,fallback=progressive,retriever=catalog-hybrid
                --arm native-anthropic:retriever=catalog-hybrid   --arm native-openai:deferred=none
                --models anthropic:claude-sonnet-5-5,openai:gpt-6.1-sol
                --catalog-sizes 50,200,1000   --tasks 50   --trials 1   --catalog-scope shared|per-task
                --budget-usd <USD> (default 0 = refuse)   --run-cap-usd <USD>   --prices prices.json
                --price provider:model=in,out[,cachedIn[,cacheWrite]]   --allow-unpriced --max-calls N
                --ledger .eval-spend.json   --estimate-safety 1.3   --max-output-tokens 4096   --max-turns 6
                --temperature none|<t>   --anthropic-effort <e>   --anthropic-thinking adaptive|between_tools|disabled
                --anthropic-cache-tools   --openai-reasoning-effort <e>   --count-tokens   --dry-run
                --max-retries <n> (SDK retries per call, default 2; raise for rate-limited proxies)
              list            available arms and retrievers
            common: --data-dir build/data  --mcptoolbench-dir  --toolret-dir  --out build/eval-results  --seed 42
            keys: ANTHROPIC_API_KEY, OPENAI_API_KEY (environment only; never printed)
            """;
}
