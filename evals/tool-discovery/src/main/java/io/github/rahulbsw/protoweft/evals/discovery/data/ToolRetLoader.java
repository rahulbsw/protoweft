package io.github.rahulbsw.protoweft.evals.discovery.data;

import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads ToolRet (arXiv 2503.01763; HF {@code mangopy/ToolRet-Queries} and
 * {@code mangopy/ToolRet-Tools}) from the JSONL files written by
 * {@code scripts/fetch_toolret.py}:
 * <pre>
 * &lt;dir&gt;/manifest.json                 revision of both HF repos + per-file hashes
 * &lt;dir&gt;/tools/&lt;category&gt;.jsonl         {"id", "category", "documentation": "&lt;tool JSON as a string&gt;"}
 * &lt;dir&gt;/queries/&lt;subset&gt;.jsonl         {"id", "subset", "category", "query", "instruction",
 *                                    "labels": [{"id", "relevance"}]}
 * </pre>
 * Tool ids are {@code toolret:<id>}; task ids {@code toolret:<query id>}. The HF data cards carry
 * no license tag, so the data is only ever downloaded locally at evaluation time.
 */
public final class ToolRetLoader {

    public static final String DATASET = "toolret";
    public static final String SOURCE = "https://huggingface.co/datasets/mangopy/ToolRet-Queries + ToolRet-Tools";

    private ToolRetLoader() {
    }

    /** Loads every tool file and the query files of the given subsets (all present when empty). */
    public static EvalDataset load(Path dir, Collection<String> subsets) {
        List<Path> toolFiles = DataFiles.list(dir.resolve("tools"), ".jsonl");
        List<Path> queryFiles = queryFiles(dir, subsets);
        Map<String, EvalTool> tools = readTools(toolFiles);
        List<EvalTask> tasks = new ArrayList<>();
        for (Path f : queryFiles) {
            for (Map<String, Object> row : Json.readJsonl(f)) {
                EvalTask t = parseQuery(row, fileStem(f));
                if (t != null) {
                    tasks.add(t);
                }
            }
        }
        List<Path> all = new ArrayList<>(toolFiles);
        all.addAll(queryFiles);
        DatasetInfo info = new DatasetInfo("ToolRet", SOURCE, DataFiles.manifestRevision(dir), DataFiles.sha256(all),
                Map.of("tools", tools.size(), "tasks", tasks.size(), "subsets", queryFiles.size()));
        return new EvalDataset(tools, tasks, info);
    }

    /** Loads only the tool corpus (for example as a distractor pool). */
    public static EvalDataset loadTools(Path dir) {
        List<Path> toolFiles = DataFiles.list(dir.resolve("tools"), ".jsonl");
        Map<String, EvalTool> tools = readTools(toolFiles);
        DatasetInfo info = new DatasetInfo("ToolRet-Tools", SOURCE, DataFiles.manifestRevision(dir),
                DataFiles.sha256(toolFiles), Map.of("tools", tools.size()));
        return new EvalDataset(tools, List.of(), info);
    }

    public static String toolId(String rawId) {
        return DATASET + ":" + rawId;
    }

    private static List<Path> queryFiles(Path dir, Collection<String> subsets) {
        Path qdir = dir.resolve("queries");
        if (subsets == null || subsets.isEmpty()) {
            return DataFiles.list(qdir, ".jsonl");
        }
        List<Path> files = new ArrayList<>();
        for (String s : subsets) {
            Path f = qdir.resolve(s + ".jsonl");
            if (!Files.isRegularFile(f)) {
                throw new IllegalArgumentException("ToolRet subset not fetched: " + s + " (expected " + f + ")");
            }
            files.add(f);
        }
        return files;
    }

    private static Map<String, EvalTool> readTools(List<Path> files) {
        Map<String, EvalTool> tools = new LinkedHashMap<>();
        for (Path f : files) {
            String category = fileStem(f);
            for (Map<String, Object> row : Json.readJsonl(f)) {
                String rawId = Json.asString(row.get("id"));
                Object docObj = row.get("documentation");
                if (rawId == null || docObj == null) {
                    continue;
                }
                String raw = docObj instanceof String s ? s : Json.write(docObj);
                Map<String, Object> doc;
                try {
                    doc = Json.asMap(Json.parse(raw));
                } catch (RuntimeException e) {
                    doc = Map.of("name", rawId, "description", raw);
                }
                String id = toolId(rawId);
                tools.putIfAbsent(id, new EvalTool(id, DATASET + "/" + category, ToolRetSchemas.toSpec(rawId, doc), raw));
            }
        }
        return tools;
    }

    private static EvalTask parseQuery(Map<String, Object> row, String subset) {
        String id = Json.asString(row.get("id"));
        String query = Json.asString(row.get("query"));
        if (id == null || query == null) {
            return null;
        }
        Object labelsObj = row.get("labels");
        List<Object> labels = labelsObj instanceof String s ? Json.asList(Json.parse(s)) : Json.asList(labelsObj);
        Map<String, Integer> relevance = new LinkedHashMap<>();
        for (Object l : labels) {
            Map<String, Object> lm = Json.asMap(l);
            String toolId = Json.asString(lm.get("id"));
            if (toolId != null) {
                Object rel = lm.get("relevance");
                relevance.put(toolId(toolId), rel instanceof Number n ? n.intValue() : 1);
            }
        }
        if (relevance.values().stream().noneMatch(r -> r > 0)) {
            return null;
        }
        String sub = row.get("subset") instanceof String s ? s : subset;
        return new EvalTask(toolId(id), DATASET + "/" + sub, query, Json.asString(row.get("instruction")),
                relevance, List.of(), null);
    }

    private static String fileStem(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".jsonl") ? n.substring(0, n.length() - ".jsonl".length()) : n;
    }
}
