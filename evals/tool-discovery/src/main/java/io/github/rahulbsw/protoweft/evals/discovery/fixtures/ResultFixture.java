package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One recorded tool output, replayed when a call matches the tool and its normalized arguments.
 * Recording from live servers is out of scope here; fixtures are authored or captured elsewhere and
 * loaded by {@link FixtureStore}. {@code tokens} is the jtokkit estimate of {@code content}, used to
 * build the observed size distribution that sizes synthetic fallbacks.
 *
 * @param tool    the tool name the output belongs to
 * @param args    the arguments it was produced for (normalized on lookup, not here)
 * @param content the result payload as it enters the model's context
 * @param tokens  jtokkit o200k token count of {@code content}
 */
public record ResultFixture(String tool, Map<String, Object> args, String content, int tokens) {

    public ResultFixture {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(content, "content");
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /** As a plain map for JSONL serialization. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("args", args);
        m.put("content", content);
        m.put("tokens", tokens);
        return m;
    }
}
