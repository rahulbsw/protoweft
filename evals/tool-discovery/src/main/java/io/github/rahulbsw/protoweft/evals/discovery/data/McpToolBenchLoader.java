package io.github.rahulbsw.protoweft.evals.discovery.data;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads MCPToolBench++ (https://github.com/mcp-tool-bench/MCPToolBenchPP, MIT) from its
 * {@code data/} directory as fetched by {@code scripts/fetch_mcptoolbench.py}.
 *
 * <p>Each data file is a JSON array of rows
 * {@code {uuid, category, call_type, tools[], mcp_tools_dict, query, function_call_label[]}}:
 * <ul>
 *   <li>{@code tools[]}: {@code {name, description, input_schema}} offered with the row;</li>
 *   <li>{@code mcp_tools_dict}: MCP server name to the tool names it serves;</li>
 *   <li>{@code function_call_label[]}: gold calls
 *       {@code {name, mcp_server, input, similar_tools[{name, mcp_server}], step, id, output}}.</li>
 * </ul>
 * Tool ids are {@code mcptoolbench:<server>/<tool>}. Every {@code *.json} file below the
 * directory is read except the fetch manifest, {@code *_demo*} files (tiny samples that repeat
 * the main files) and {@code *forqwen*} variants (a duplicate of the search file reformatted for
 * one model), and anything under {@code test_project_root}.
 */
public final class McpToolBenchLoader {

    public static final String DATASET = "mcptoolbench";
    public static final String SOURCE = "https://github.com/mcp-tool-bench/MCPToolBenchPP";

    private McpToolBenchLoader() {
    }

    public static EvalDataset load(Path dataDir) {
        List<Path> files = DataFiles.list(dataDir, ".json").stream()
                .filter(McpToolBenchLoader::isDataFile).toList();
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no MCPToolBench++ data files under " + dataDir);
        }
        Map<String, EvalTool> tools = new LinkedHashMap<>();
        List<EvalTask> tasks = new ArrayList<>();
        Set<String> seenTasks = new LinkedHashSet<>();
        for (Path file : files) {
            for (Object rowObj : Json.asList(Json.read(file))) {
                EvalTask task = parseRow(Json.asMap(rowObj), tools);
                if (task != null && seenTasks.add(task.id())) {
                    tasks.add(task);
                }
            }
        }
        DatasetInfo info = new DatasetInfo("MCPToolBench++", SOURCE, DataFiles.manifestRevision(dataDir),
                DataFiles.sha256(files), Map.of("files", files.size(), "tools", tools.size(), "tasks", tasks.size()));
        return new EvalDataset(tools, tasks, info);
    }

    static boolean isDataFile(Path p) {
        String name = p.getFileName().toString();
        return !name.equals(DataFiles.MANIFEST) && !name.contains("_demo") && !name.contains("forqwen")
                && !p.toString().replace('\\', '/').contains("/test_project_root/");
    }

    /** Tool id for a tool served by an MCP server. */
    public static String toolId(String server, String tool) {
        return DATASET + ":" + (server == null || server.isBlank() ? "unknown" : server) + "/" + tool;
    }

    private static EvalTask parseRow(Map<String, Object> row, Map<String, EvalTool> tools) {
        Object uuid = row.get("uuid");
        Object query = row.get("query");
        if (uuid == null || query == null) {
            return null;
        }
        Map<String, String> serverOf = new HashMap<>();
        Json.asMap(row.get("mcp_tools_dict")).forEach((server, names) ->
                Json.asList(names).forEach(n -> serverOf.put(String.valueOf(n), server)));
        String category = String.valueOf(row.getOrDefault("category", "unknown"));
        for (Object t : Json.asList(row.get("tools"))) {
            Map<String, Object> tm = Json.asMap(t);
            String name = Json.asString(tm.get("name"));
            if (name == null) {
                continue;
            }
            String server = serverOf.getOrDefault(name, "unknown");
            String id = toolId(server, name);
            tools.computeIfAbsent(id, k -> new EvalTool(k, DATASET, new ToolSpec(name,
                    String.valueOf(tm.getOrDefault("description", "")).strip(),
                    Json.asMap(tm.get("input_schema")), Map.of("server", server, "category", category))));
        }
        Map<String, Integer> relevance = new LinkedHashMap<>();
        List<GoldCall> calls = new ArrayList<>();
        Set<String> alternatives = new LinkedHashSet<>();
        for (Object l : Json.asList(row.get("function_call_label"))) {
            Map<String, Object> label = Json.asMap(l);
            String name = Json.asString(label.get("name"));
            if (name == null) {
                continue;
            }
            String server = Json.asString(label.get("mcp_server"));
            String id = toolId(server != null ? server : serverOf.get(name), name);
            relevance.put(id, 1);
            calls.add(new GoldCall(id, Json.asMap(label.get("input"))));
            for (Object s : Json.asList(label.get("similar_tools"))) {
                Map<String, Object> sm = Json.asMap(s);
                String sn = Json.asString(sm.get("name"));
                if (sn != null) {
                    alternatives.add(toolId(Json.asString(sm.get("mcp_server")), sn));
                }
            }
        }
        if (relevance.isEmpty()) {
            return null;
        }
        alternatives.removeAll(relevance.keySet());
        List<String> requiredFacts = Json.asList(row.get("required_facts")).stream()
                .map(String::valueOf).toList();
        return new EvalTask(DATASET + ":" + uuid, DATASET + "/" + category, query.toString(), null,
                relevance, calls, alternatives, requiredFacts);
    }
}
