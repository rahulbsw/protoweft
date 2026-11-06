package io.github.rahulbsw.protoweft.evals.discovery.run;

import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTool;
import io.github.rahulbsw.protoweft.evals.discovery.data.TaskSampler;
import io.github.rahulbsw.protoweft.evals.discovery.metrics.RetrievalMetrics;
import io.github.rahulbsw.protoweft.evals.discovery.metrics.Stats.Estimate;
import io.github.rahulbsw.protoweft.evals.discovery.report.Aggregator;
import io.github.rahulbsw.protoweft.evals.discovery.report.GitInfo;
import io.github.rahulbsw.protoweft.evals.discovery.report.Markdown;
import io.github.rahulbsw.protoweft.evals.discovery.report.ResultsWriter;
import io.github.rahulbsw.protoweft.evals.discovery.report.RunInfo;
import io.github.rahulbsw.protoweft.evals.discovery.retrieval.Retriever;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The offline arm: datasets × retrievers × catalog sizes, scored with {@link RetrievalMetrics}
 * at every k. No model calls, no keys.
 */
public final class RetrievalRun {

    /**
     * @param datasets     {@code mcptoolbench} and/or {@code toolret:<subset>}
     * @param retrievers   retriever names
     * @param ks           cut-offs, e.g. 1,3,5,10
     * @param catalogSizes catalog sizes; 0 = gold + the whole pool
     * @param depth        ranking depth requested from retrievers (MRR is computed at this depth)
     * @param maxQueries   stratified sample size per dataset; 0 = all
     * @param distractors  {@code own}, {@code toolret} or {@code both}
     * @param split        {@code all}, or {@code dev}/{@code test}: score only that split of tasks (the
     *                     same stable hash as {@code calibrate}); catalogs still hold every task's gold
     *                     tools, so both splits search identical catalogs
     * @param devFraction  share of tasks in the dev split
     */
    public record Config(List<String> datasets, List<String> retrievers, List<Integer> ks, List<Integer> catalogSizes,
                         int depth, int maxQueries, long seed, String distractors, Path mcpDir, Path toolretDir,
                         Path outDir, Path repoDir, String split, double devFraction) {

        public Config {
            if (!List.of("all", "dev", "test").contains(split)) {
                throw new IllegalArgumentException("split must be all, dev or test: " + split);
            }
        }

        /** All tasks scored. */
        public Config(List<String> datasets, List<String> retrievers, List<Integer> ks, List<Integer> catalogSizes,
                      int depth, int maxQueries, long seed, String distractors, Path mcpDir, Path toolretDir,
                      Path outDir, Path repoDir) {
            this(datasets, retrievers, ks, catalogSizes, depth, maxQueries, seed, distractors, mcpDir, toolretDir,
                    outDir, repoDir, "all", 0.5);
        }

        boolean scores(EvalTask task) {
            return split.equals("all") || CalibrateRun.isDev(task.id(), devFraction) == split.equals("dev");
        }
    }

    private final Config config;
    private final Function<String, Retriever> retrievers;

    public RetrievalRun(Config config, Function<String, Retriever> retrievers) {
        this.config = Objects.requireNonNull(config, "config");
        this.retrievers = Objects.requireNonNull(retrievers, "retrievers");
    }

    /** Runs everything and returns the results directory. */
    public Path run() {
        DataSources data = new DataSources(config.mcpDir(), config.toolretDir(), config.datasets());
        String runId = RunIds.next("retrieval", config.repoDir());
        int maxK = config.ks().stream().mapToInt(Integer::intValue).max().orElse(10);
        int depth = Math.max(config.depth(), maxK);
        Map<String, List<Map<String, Object>>> cells = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        try (ResultsWriter out = new ResultsWriter(config.outDir().resolve(runId))) {
            for (String spec : config.datasets()) {
                List<EvalTask> tasks = TaskSampler.sample(data.tasks(spec), config.maxQueries(), config.seed());
                Map<String, EvalTool> own = data.ownTools(spec);
                List<EvalTask> usable = tasks.stream().filter(t -> own.keySet().containsAll(t.goldToolIds())).toList();
                if (usable.size() < tasks.size()) {
                    notes.add(spec + ": skipped " + (tasks.size() - usable.size()) + " tasks whose gold tools are missing");
                }
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
                        Retriever r = retrievers.apply(rname);
                        r.index(catalog);
                        String cell = spec + "|" + r.name() + "|" + catalog.size();
                        List<Map<String, Object>> rows = cells.computeIfAbsent(cell, k -> new ArrayList<>());
                        for (EvalTask t : usable) {
                            if (!config.scores(t)) {
                                continue;
                            }
                            long start = System.nanoTime();
                            List<String> ranked = r.retrieve(t, catalog, depth);
                            double millis = (System.nanoTime() - start) / 1e6;
                            Map<String, Object> row = score(t, ranked, spec, r.name(), catalog);
                            row.put("retrieveMs", millis);
                            row.putAll(r.rowExtras());
                            rows.add(row);
                            out.row(row);
                        }
                    }
                }
            }
            RunInfo info = new RunInfo(runId, "retrieval", Instant.now().toString(), GitInfo.detect(config.repoDir()),
                    data.infos(), configMap());
            Map<String, Object> summary = new LinkedHashMap<>(info.toMap());
            Map<String, Object> cellSummaries = new LinkedHashMap<>();
            StringBuilder md = new StringBuilder(Markdown.header("Tool retrieval evaluation (offline)", info));
            md.append(table(cells, cellSummaries));
            md.append(latency(cells, cellSummaries));
            if (!notes.isEmpty()) {
                md.append("## Notes\n\n");
                notes.forEach(n -> md.append("- ").append(n).append('\n'));
            }
            summary.put("cells", cellSummaries);
            summary.put("notes", notes);
            out.summary(summary);
            out.report(md.toString());
            return out.dir();
        }
    }

    private Map<String, Object> score(EvalTask t, List<String> ranked, String spec, String retriever, EvalCatalog c) {
        Map<String, Integer> q = t.goldRelevance();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dataset", spec);
        row.put("taskDataset", t.dataset());
        row.put("retriever", retriever);
        row.put("catalogSize", c.size());
        row.put("catalogFingerprint", c.fingerprint());
        row.put("taskId", t.id());
        row.put("gold", t.goldToolIds());
        row.put("top", RetrievalMetrics.topK(ranked, Math.min(10, ranked.size())));
        row.put("mrr", RetrievalMetrics.reciprocalRank(ranked, q));
        for (int k : config.ks()) {
            row.put("recall@" + k, RetrievalMetrics.recallAt(ranked, q, k));
            row.put("precision@" + k, RetrievalMetrics.precisionAt(ranked, q, k));
            row.put("ndcg@" + k, RetrievalMetrics.ndcgAt(ranked, q, k));
            row.put("completeness@" + k, RetrievalMetrics.completenessAt(ranked, q, k) == 1.0);
        }
        return row;
    }

    private String table(Map<String, List<Map<String, Object>>> cells, Map<String, Object> summaries) {
        List<String> headers = new ArrayList<>(List.of("dataset", "retriever", "N", "queries", "MRR"));
        for (int k : config.ks()) {
            headers.addAll(List.of("R@" + k, "nDCG@" + k, "C@" + k));
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> cell : cells.entrySet()) {
            String[] key = cell.getKey().split("\\|");
            List<Map<String, Object>> rs = cell.getValue();
            Map<String, Object> s = new LinkedHashMap<>();
            List<String> line = new ArrayList<>(List.of(key[0], key[1], key[2], String.valueOf(rs.size())));
            Estimate mrr = Aggregator.estimate(rs, "mrr", false);
            s.put("mrr", Markdown.toMap(mrr));
            line.add(Markdown.fmt(mrr, false));
            for (int k : config.ks()) {
                Estimate r = Aggregator.estimate(rs, "recall@" + k, false);
                Estimate n = Aggregator.estimate(rs, "ndcg@" + k, false);
                Estimate c = Aggregator.estimate(rs, "completeness@" + k, true);
                Estimate p = Aggregator.estimate(rs, "precision@" + k, false);
                s.put("recall@" + k, Markdown.toMap(r));
                s.put("ndcg@" + k, Markdown.toMap(n));
                s.put("completeness@" + k, Markdown.toMap(c));
                s.put("precision@" + k, Markdown.toMap(p));
                line.addAll(List.of(Markdown.fmt(r, false), Markdown.fmt(n, false), Markdown.fmt(c, true)));
            }
            summaries.put(cell.getKey(), s);
            rows.add(line);
        }
        return "## Results\n\nR = Recall, C = Completeness (all gold tools in top k), N = catalog size. "
                + "Precision@k is in summary.json.\n\n" + Markdown.table(headers, rows);
    }

    /**
     * Median and p95 per-query latency of each cell ({@code retrieveMs}: the whole search, warm;
     * {@code rerankMs}: the reranking step, when a retriever reports it). Machine-dependent.
     */
    private static String latency(Map<String, List<Map<String, Object>>> cells, Map<String, Object> summaries) {
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> cell : cells.entrySet()) {
            String[] key = cell.getKey().split("\\|");
            List<String> line = new ArrayList<>(List.of(key[0], key[1], key[2]));
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) summaries.get(cell.getKey());
            for (String field : List.of("retrieveMs", "rerankMs")) {
                double[] v = cell.getValue().stream().map(row -> row.get(field)).filter(Number.class::isInstance)
                        .mapToDouble(x -> ((Number) x).doubleValue()).sorted().toArray();
                if (v.length == 0) {
                    line.addAll(List.of("", ""));
                    continue;
                }
                double median = percentile(v, 0.5);
                double p95 = percentile(v, 0.95);
                if (s != null) {
                    s.put(field, Map.of("median", median, "p95", p95, "n", v.length));
                }
                line.addAll(List.of(String.format(java.util.Locale.ROOT, "%.1f", median),
                        String.format(java.util.Locale.ROOT, "%.1f", p95)));
            }
            rows.add(line);
        }
        return "\n## Latency (ms per query, this machine)\n\n"
                + Markdown.table(List.of("dataset", "retriever", "N", "search median", "search p95", "rerank median",
                "rerank p95"), rows);
    }

    /** Nearest-rank percentile of sorted values. */
    static double percentile(double[] sorted, double q) {
        int rank = (int) Math.ceil(q * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private Map<String, Object> configMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasets", config.datasets());
        m.put("retrievers", config.retrievers());
        m.put("ks", config.ks());
        m.put("catalogSizes", config.catalogSizes());
        m.put("depth", config.depth());
        m.put("maxQueries", config.maxQueries());
        m.put("seed", config.seed());
        m.put("distractors", config.distractors());
        m.put("split", config.split());
        m.put("devFraction", config.devFraction());
        return m;
    }
}
