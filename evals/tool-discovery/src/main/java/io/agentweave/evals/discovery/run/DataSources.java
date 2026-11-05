package io.agentweave.evals.discovery.run;

import io.agentweave.evals.discovery.data.DatasetInfo;
import io.agentweave.evals.discovery.data.EvalDataset;
import io.agentweave.evals.discovery.data.EvalTask;
import io.agentweave.evals.discovery.data.EvalTool;
import io.agentweave.evals.discovery.data.McpToolBenchLoader;
import io.agentweave.evals.discovery.data.ToolRetLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves dataset specs ({@code mcptoolbench}, {@code toolret:<subset>}) to tasks and tool
 * pools, loading each dataset at most once.
 */
final class DataSources {

    private final Path mcpDir;
    private final Path toolretDir;
    private EvalDataset mcp;
    private EvalDataset toolret;
    private final Set<String> toolretSubsets = new LinkedHashSet<>();

    DataSources(Path mcpDir, Path toolretDir, List<String> specs) {
        this.mcpDir = mcpDir;
        this.toolretDir = toolretDir;
        for (String s : specs) {
            if (s.startsWith("toolret:")) {
                toolretSubsets.add(s.substring("toolret:".length()));
            } else if (!s.equals("mcptoolbench")) {
                throw new IllegalArgumentException("unknown dataset '" + s + "' (use mcptoolbench or toolret:<subset>)");
            }
        }
    }

    EvalDataset mcp() {
        if (mcp == null) {
            mcp = McpToolBenchLoader.load(mcpDir);
        }
        return mcp;
    }

    boolean toolretAvailable() {
        return Files.isDirectory(toolretDir.resolve("tools"));
    }

    /** ToolRet tools and the requested subsets' queries (tools only if no subset was requested). */
    EvalDataset toolret() {
        if (toolret == null) {
            toolret = toolretSubsets.isEmpty() ? ToolRetLoader.loadTools(toolretDir)
                    : ToolRetLoader.load(toolretDir, toolretSubsets);
        }
        return toolret;
    }

    List<EvalTask> tasks(String spec) {
        if (spec.equals("mcptoolbench")) {
            return mcp().tasks();
        }
        String dataset = ToolRetLoader.DATASET + "/" + spec.substring("toolret:".length());
        return toolret().tasks().stream().filter(t -> t.dataset().equals(dataset)).toList();
    }

    /** All tools of the spec's own dataset. */
    Map<String, EvalTool> ownTools(String spec) {
        return spec.equals("mcptoolbench") ? mcp().tools() : toolret().tools();
    }

    /**
     * The distractor pool: {@code toolret} (the 43k ToolRet corpus), {@code own} (the spec's own
     * dataset only) or {@code both}.
     */
    List<EvalTool> pool(String spec, String distractors) {
        Map<String, EvalTool> pool = new LinkedHashMap<>();
        if (distractors.equals("own") || distractors.equals("both")) {
            pool.putAll(ownTools(spec));
        }
        if (distractors.equals("toolret") || distractors.equals("both")) {
            if (!toolretAvailable()) {
                throw new IllegalArgumentException("ToolRet tools not found under " + toolretDir
                        + "; run scripts/fetch_toolret.py or use --distractors own");
            }
            pool.putAll(toolret().tools());
        }
        return new ArrayList<>(pool.values());
    }

    List<DatasetInfo> infos() {
        List<DatasetInfo> out = new ArrayList<>();
        if (mcp != null) {
            out.add(mcp.info());
        }
        if (toolret != null) {
            out.add(toolret.info());
        }
        return out;
    }
}
