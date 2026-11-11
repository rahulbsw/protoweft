package io.github.rahulbsw.protoweft.utcp;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import tools.jackson.core.JacksonException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.rahulbsw.protoweft.utcp.FuzzJson.obj;

/**
 * Fuzzes UTCP manual handling with a manual body (raw or generated JSON, see {@link FuzzJson}).
 * As a fetched remote manual: JSON parsing ({@link JacksonException}), {@link Manual#parse} and
 * {@link Manual.HttpTemplate#parse} ({@link IllegalArgumentException}) and the remote-loopback rule
 * ({@link ProtoweftException} {@code PROTOCOL}). As an inline text manual through the public
 * connector (register, list, search, call its text tools): only {@link ProtoweftException}s of
 * category {@code CONFIGURATION}, {@code PROTOCOL} or {@code UNSUPPORTED}. Nothing touches the network.
 *
 * <p>Regression mode (the default) runs the seeds under {@code ManualFuzzTestInputs/}; fuzz with
 * {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class ManualFuzzTest {

    private static final Set<ErrorCategory> REJECTIONS =
            Set.of(ErrorCategory.CONFIGURATION, ErrorCategory.PROTOCOL, ErrorCategory.UNSUPPORTED);
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * Keys and values that structured mode builds manuals from, one per line in
     * {@code ManualFuzzTestWords.txt}: UTCP field names, not credentials.
     */
    private static final List<String> WORDS = words();
    /** Valid manuals that structured mode edits. */
    private static final List<Object> TEMPLATES = List.of(
            obj("utcp_version", "1.1.0", "manual_version", "1.0.0", "tools", List.of(
                    obj("name", "echo", "description", "Echoes", "inputs", obj("type", "object",
                                    "properties", obj("msg", obj("type", "string")), "required", List.of("msg")),
                            "outputs", obj("type", "string"), "tags", List.of("text", "echo"),
                            "average_response_size", 10,
                            "tool_call_template", obj("call_template_type", "text", "content", "hi ${NAME}")))),
            obj("utcp_version", "1.1.0", "tools", List.of(
                    obj("name", "weather", "description", "Forecast", "tool_call_template", obj(
                            "call_template_type", "http", "url", "https://api.example.com/{city}", "http_method", "POST",
                            "headers", obj("X-Trace", "1"), "body_field", "body", "header_fields", List.of("h"),
                            "auth", obj("auth_type", "api_key", "api_key", "$KEY", "var_name", "k", "location", "query"))),
                    obj("name", "token", "tool_call_template", obj("call_template_type", "http",
                            "url", "https://api.example.com/t", "auth", obj("auth_type", "oauth2",
                                    "token_url", "https://auth.example.com/token", "client_id", "c",
                                    "client_secret", "${OAUTH_CLIENT_SECRET}", "scope", "read"))),
                    obj("name", "basic", "tool_call_template", obj("call_template_type", "http",
                            "url", "http://127.0.0.1:8080/x", "auth", obj("auth_type", "basic", "username", "u",
                                    "password", "${BASIC_PASSWORD}"))))));

    @DictionaryFile(resourcePath = "json.dict")
    @FuzzTest(maxDuration = "2m")
    void manual(byte[] input) {
        byte[] body = FuzzJson.document(input, WORDS, TEMPLATES);
        asRemoteManual(body);
        asTextManual(new String(body, StandardCharsets.UTF_8));
    }

    private static void asRemoteManual(byte[] body) {
        Manual manual;
        try {
            manual = Manual.parse(Json.parse(body));
            for (Manual.Tool tool : manual.tools()) {
                if ("http".equals(tool.callTemplateType())) {
                    Manual.HttpTemplate.parse(tool.callTemplate());
                }
            }
        } catch (JacksonException | IllegalArgumentException rejected) {
            return;
        }
        try {
            UrlSecurity.rejectRemoteLoopbackToolUrls("https://manuals.example.com/utcp", manual.tools());
        } catch (ProtoweftException e) {
            if (e.category() != ErrorCategory.PROTOCOL) {
                throw e;
            }
        }
    }

    private static void asTextManual(String manualJson) {
        UtcpClientConnector connector = UtcpClientConnector.text("fuzz", manualJson)
                .environment(name -> "v").httpClient(HTTP).build();
        try {
            connector.start();
            List<ToolSpec> tools = connector.listTools();
            connector.search("echo weather forecast text");
            connector.skippedTools();
            for (ToolSpec tool : tools) {
                connector.invoke(ToolInvocation.of(tool.name(), Map.of()));
            }
        } catch (ProtoweftException e) {
            if (!REJECTIONS.contains(e.category())) {
                throw e;
            }
        } finally {
            connector.close();
        }
    }

    private static List<String> words() {
        try (java.io.InputStream in = ManualFuzzTest.class.getResourceAsStream("ManualFuzzTestWords.txt")) {
            String text = new String(java.util.Objects.requireNonNull(in, "ManualFuzzTestWords.txt").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            // One word per line; the file ends with a newline, and an empty line is the empty word.
            return List.of(text.substring(0, text.length() - 1).split("\n", -1));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
