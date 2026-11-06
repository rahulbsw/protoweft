package io.github.rahulbsw.protoweft.utcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.utcp.TestBackend.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.rahulbsw.protoweft.utcp.TestManuals.http;
import static io.github.rahulbsw.protoweft.utcp.TestManuals.manual;
import static io.github.rahulbsw.protoweft.utcp.TestManuals.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Variable namespacing, lookup order and failures, per default_variable_substitutor.py. */
class VariablesTest {

    @TempDir
    Path dir;
    private TestBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        backend = new TestBackend().route("/v", r -> Reply.json(200, "{}"));
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    private UtcpClientConnector.Builder builder() {
        String manual = manual(tool("t", http("GET", backend.url("/v"),
                "headers", Map.of("X-A", "${API_KEY}", "X-B", "pre-$API_KEY-post"))));
        return UtcpClientConnector.text("my_api", manual).allowedCommunicationProtocols("http").environment(k -> null);
    }

    private String sentKey(UtcpClientConnector.Builder b) {
        UtcpClientConnector c = b.build();
        c.start();
        c.invoke(ToolInvocation.of("t", Map.of()));
        return backend.last().header("X-A") + "|" + backend.last().header("X-B");
    }

    @Test
    void namespacesByManualNameWithDoubledUnderscores() {
        assertThat(Variables.namespaced("my_api", "API_KEY")).isEqualTo("my__api_API_KEY");
        assertThat(sentKey(builder().variable("my__api_API_KEY", "k"))).isEqualTo("k|pre-k-post");
    }

    @Test
    void thereIsNoBareNameFallback() {
        assertThatThrownBy(() -> sentKey(builder().variable("API_KEY", "k")))
                .isInstanceOf(ProtoweftException.class)
                .hasMessageStartingWith("Variable my__api_API_KEY referenced in provider configuration not found")
                .extracting(e -> ((ProtoweftException) e).category()).isEqualTo(ErrorCategory.CONFIGURATION);
    }

    @Test
    void looksUpConfigThenDotenvThenEnvironment() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# comment\nexport my__api_API_KEY=\"from-dotenv\"\n");
        Map<String, String> os = Map.of("my__api_API_KEY", "from-env");

        assertThat(sentKey(builder().variable("my__api_API_KEY", "from-config").dotenv(env).environment(os::get)))
                .startsWith("from-config|");
        assertThat(sentKey(builder().dotenv(env).environment(os::get))).startsWith("from-dotenv|");
        assertThat(sentKey(builder().dotenv(dir.resolve("missing.env")).environment(os::get))).startsWith("from-env|");
    }

    @Test
    void substitutesTheManualTemplateAtRegistration() {
        backend.route("/utcp", r -> Reply.json(200, manual()));
        UtcpClientConnector c = UtcpClientConnector.http("disc", "http://127.0.0.1:${PORT}/utcp")
                .header("X-Key", "$KEY")
                .variable("disc_PORT", String.valueOf(backend.port()))
                .variable("disc_KEY", "${INDIRECT}")
                .environment(Map.of("INDIRECT", "resolved")::get)
                .build();
        c.start();
        assertThat(backend.last().header("X-Key")).isEqualTo("resolved");
    }

    @Test
    void skipsJsonReferencesButNotLookalikes() {
        Variables v = Variables.create(Map.of("refresh_token", "R"), List.of(), k -> null);
        assertThat(v.substitute("#/$ref/x and $missing", null)).isEqualTo("#/$ref/x and $missing");
        assertThat(v.substitute(Map.of("a", List.of("$refresh_token", 3)), null))
                .isEqualTo(Map.of("a", List.of("R", 3)));
    }

    @Test
    void parsesDotenvLikePythonDotenv() {
        assertThat(Variables.parseDotenv("""
                A=1
                export B = two # trailing comment
                C='lit $x # not comment'
                D="esc\\nline"
                # ignored
                E
                """)).containsExactly(Map.entry("A", "1"), Map.entry("B", "two"),
                Map.entry("C", "lit $x # not comment"), Map.entry("D", "esc\nline"));
    }
}
