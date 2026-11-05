package io.agentweave.evals.discovery.run;

import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.TaskSampler;
import io.agentweave.evals.discovery.metrics.RetrievalMetrics;
import io.agentweave.evals.discovery.metrics.Stats.Estimate;
import io.agentweave.evals.discovery.report.Aggregator;
import io.agentweave.evals.discovery.report.GitInfo;
import io.agentweave.evals.discovery.report.Markdown;
import io.agentweave.evals.discovery.report.ResultsWriter;
import io.agentweave.evals.discovery.report.RunInfo;
import io.agentweave.evals.discovery.retrieval.Retriever;

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
     */
    public record Config(List<String> datasets, List<String> retrievers, List<Integer> ks, List<Integer> catalogSizes,
                         int depth, int maxQueries, long seed, String distractors, Path mcpDir, Path toolretDir,
                         Path outDir, Path repoDir) {
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
                            Map<String, Object> row = score(t, r.retrieve(t, catalog, depth), spec, r.name(), catalog);
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
        return m;
    }
}
