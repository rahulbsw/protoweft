package io.github.rahulbsw.protoweft.rap;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.rahulbsw.protoweft.rap.FuzzJson.obj;

/**
 * Fuzzes toolset manifest validation ({@code GET /.well-known/rap-toolset}, a remote server's
 * answer; raw or generated JSON, see {@link FuzzJson}): any body is either a valid {@link Toolset}
 * or an {@link IllegalArgumentException} (which the connector reports as a {@code PROTOCOL} error).
 * An accepted manifest must satisfy the rules {@link Toolset#parse} promises, and its annotations
 * must survive the metadata mapping.
 *
 * <p>Regression mode (the default) runs the seeds under {@code ToolsetFuzzTestInputs/}; fuzz with
 * {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class ToolsetFuzzTest {

    /** Keys and values that structured mode (see {@link FuzzJson}) builds manifests from. */
    private static final List<String> WORDS = List.of("name", "description", "endpoint", "needsMigration", "tools",
            "inputSchema", "annotations", "type", "object", "properties", "x-protoweft-readOnly",
            "x-protoweft-destructive", "x-protoweft-title", "x-protoweft-tags", "get_forecast", "a-b_1", "bad name",
            "", "https://tools.example.com/invoke", "http://127.0.0.1:8080/", "ftp://x", "https://");
    /** Valid manifests that structured mode edits. */
    private static final List<Object> TEMPLATES = List.of(
            obj("name", "weather", "description", "Weather tools", "endpoint", "https://tools.example.com/invoke",
                    "tools", List.of(obj("name", "get_forecast", "description", "Forecast",
                            "inputSchema", obj("type", "object", "properties", obj("city", obj("type", "string")),
                                    "required", List.of("city"))))),
            obj("name", "t", "endpoint", "http://127.0.0.1:8080/", "needsMigration", false,
                    "tools", List.of(
                            obj("name", "a-b_1", "description", "", "inputSchema", obj(),
                                    "annotations", obj("x-protoweft-readOnly", true, "x-protoweft-title", "A",
                                            "x-protoweft-tags", List.of("x"), "custom", obj("k", List.of(1)))),
                            obj("name", "b", "description", "d", "inputSchema", obj("type", "object")))));

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void manifest(byte[] input) {
        Toolset toolset;
        try {
            toolset = Toolset.parse("https://tools.example.com", FuzzJson.document(input, WORDS, TEMPLATES));
        } catch (IllegalArgumentException rejected) {
            return;
        }
        check(!toolset.name().isEmpty() && toolset.name().length() <= 128, "toolset name length");
        String scheme = toolset.endpoint().getScheme();
        check(toolset.endpoint().isAbsolute() && toolset.endpoint().getHost() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)), "endpoint is http(s)");
        check(!toolset.tools().isEmpty(), "at least one tool");
        for (Map.Entry<String, ToolSpec> e : toolset.tools().entrySet()) {
            ToolSpec spec = e.getValue();
            check(e.getKey().equals(spec.name()) && Toolset.NAME.matcher(spec.name()).matches(), "tool name");
            Object raw = spec.metadata().get(RapClientConnector.META_ANNOTATIONS);
            Map<String, Object> annotations = Toolset.annotationsFor(spec);
            check(Objects.equals(raw, annotations), "annotations round-trip through metadata");
        }
    }


    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("accepted manifest violates: " + what);
        }
    }
}
