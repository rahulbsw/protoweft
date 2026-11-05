package io.agentweave.evals.discovery;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.data.EvalTool;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test fixtures and helpers. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path dir(String name) {
        try {
            return Path.of(Fixtures.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path mcp() {
        return dir("mcptoolbench");
    }

    public static Path toolret() {
        return dir("toolret");
    }

    /** A tool with a one-string-argument schema. */
    public static EvalTool tool(String id, String name) {
        return new EvalTool(id, "test", new ToolSpec(name, "does " + name,
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))));
    }

    /** {@code n} distinct distractor tools {@code d:0000…}. */
    public static List<EvalTool> distractors(int n) {
        List<EvalTool> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(tool(String.format("d:%04d", i), "distractor_" + i));
        }
        return out;
    }
}
