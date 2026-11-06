package io.github.rahulbsw.protoweft.utcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.utcp.TestBackend.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.rahulbsw.protoweft.utcp.TestManuals.http;
import static io.github.rahulbsw.protoweft.utcp.TestManuals.manual;
import static io.github.rahulbsw.protoweft.utcp.TestManuals.ofType;
import static io.github.rahulbsw.protoweft.utcp.TestManuals.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Manual call templates, protocol filtering, unsupported tools and the compliance report. */
class ManualRegistrationTest {

    @TempDir
    Path dir;
    private TestBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        backend = new TestBackend();
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    private String mixedManual() {
        return manual(
                tool("echo", Map.of("call_template_type", "text", "content", "hi")),
                tool("m.web", http("GET", backend.url("/x"))),
                tool("shell", ofType("cli")),
                tool("stream", ofType("sse")));
    }

    private static ErrorCategory category(Throwable e) {
        return ((ProtoweftException) e).category();
    }

    @Test
    void defaultAllowsOnlyTheManualsOwnProtocol() {
        UtcpClientConnector c = UtcpClientConnector.text("m", mixedManual()).build();
        c.start();

        assertThat(c.listTools()).extracting(ToolSpec::name).containsExactly("echo");
        assertThat(c.skippedTools()).containsOnlyKeys("web", "shell", "stream");
        assertThat(c.skippedTools().get("web")).contains("not in allowed protocols [text]");
        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("web", Map.of())))
                .hasMessageStartingWith("Tool not found: m.web (Tool 'm.web' uses communication protocol 'http'")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));
    }

    @Test
    void allowedButUnimplementedTemplateTypesAreSkippedAndDeclared() {
        UtcpClientConnector c = UtcpClientConnector.text("m", mixedManual())
                .allowedCommunicationProtocols("text", "http", "cli").build();
        c.start();

        assertThat(c.listTools()).extracting(ToolSpec::name).containsExactly("echo", "web");
        assertThat(c.skippedTools().get("shell")).isEqualTo("call template type 'cli' is not supported by this connector");
        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("shell", Map.of())))
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.UNSUPPORTED));
        assertThat(c.compliance().unsupported()).contains(UtcpFeatures.CALL_CLI, UtcpFeatures.CALL_SSE);
        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("nope", Map.of())))
                .hasMessage("Tool not found: m.nope");
    }

    @Test
    void reportsSpecImplementationWithSeparateModuleVersionAndWireRevisions() {
        UtcpClientConnector c = UtcpClientConnector.text("m", mixedManual()).build();
        ComplianceReport before = c.compliance();
        assertThat(before.protocol()).isEqualTo(ProtocolId.UTCP);
        assertThat(before.sdkName()).isEqualTo("none");
        assertThat(before.sdkVersion()).isNotBlank().doesNotContain("$").isNotEqualTo("1.1");
        assertThat(before.wireRevisions()).containsExactly("1.0", "1.1");
        assertThat(before.negotiated()).isEmpty();
        assertThat(before.supported()).contains(UtcpFeatures.MANUAL_HTTP, UtcpFeatures.CALL_HTTP,
                UtcpFeatures.AUTH_OAUTH2, UtcpFeatures.VARIABLES, UtcpFeatures.ALLOWED_COMMUNICATION_PROTOCOLS);
        assertThat(before.unsupported()).contains(UtcpFeatures.MANUAL_YAML, UtcpFeatures.MANUAL_OPENAPI,
                UtcpFeatures.CALL_TOOL_STREAMING, UtcpFeatures.CALL_MCP);

        c.start();
        assertThat(c.compliance().negotiated()).contains("1.1.0");
    }

    @Test
    void readsFileManualsAndRejectsYamlAndOpenApi() throws Exception {
        Path json = Files.writeString(dir.resolve("manual.json"), mixedManual());
        UtcpClientConnector onlyFile = UtcpClientConnector.file("m", json).build();
        onlyFile.start();
        assertThat(onlyFile.listTools()).isEmpty();
        UtcpClientConnector c = UtcpClientConnector.file("m", json).allowedCommunicationProtocols("text").build();
        c.start();
        assertThat(c.listTools()).extracting(ToolSpec::name).containsExactly("echo");

        Path yaml = Files.writeString(dir.resolve("manual.yaml"), "tools: []");
        assertThatThrownBy(() -> UtcpClientConnector.file("m", yaml).build().start())
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.UNSUPPORTED));
        assertThatThrownBy(() -> UtcpClientConnector.text("m", "{\"openapi\":\"3.0.0\",\"paths\":{}}").build().start())
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.UNSUPPORTED));
        assertThatThrownBy(() -> UtcpClientConnector.file("m", dir.resolve("absent.json")).build().start())
                .hasMessageStartingWith("Manual file not found");
    }

    @Test
    void acceptsAManualSerializedByThePythonReference() throws Exception {
        String dumped;
        try (var in = getClass().getResourceAsStream("python-dumped-manual.json")) {
            dumped = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // Like the reference, a text template's content is variable-substituted, so "$schema" is a variable.
        assertThatThrownBy(() -> UtcpClientConnector.text("users", dumped).environment(k -> null).build().start())
                .hasMessageStartingWith("Variable users_schema referenced in provider configuration not found");

        Path file = Files.writeString(dir.resolve("dumped.json"), dumped);
        UtcpClientConnector c = UtcpClientConnector.file("users", file).allowedCommunicationProtocols("http").build();
        c.start();

        assertThat(c.compliance().negotiated()).contains("1.2.0");
        ToolSpec spec = c.listTools().get(0);
        assertThat(spec.name()).isEqualTo("get_user");
        assertThat(spec.inputSchema()).isEqualTo(Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string")), "required", List.of("id")));
        assertThat(c.search("users")).extracting(ToolSpec::name).containsExactly("get_user");
    }

    @Test
    void invalidManualsFailRegistration() {
        assertThatThrownBy(() -> UtcpClientConnector.text("m", "{\"tools\":[{\"name\":\"x\"}]}").build().start())
                .hasMessageContaining("'tool_call_template' is required")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.CONFIGURATION));
        assertThatThrownBy(() -> UtcpClientConnector.text("m", "tools: []").build().start())
                .hasMessageContaining("YAML manuals are not supported");
        String badMethod = manual(tool("t", http("FETCH", backend.url("/x"))));
        assertThatThrownBy(() -> UtcpClientConnector.text("m", badMethod).allowedCommunicationProtocols("http").build().start())
                .hasMessageContaining("http_method must be one of");
    }

    @Test
    void sanitizesManualNamesAndRequiresStart() {
        UtcpClientConnector c = UtcpClientConnector.text("my-api.v2", manual()).build();
        assertThat(c.manualName()).isEqualTo("my_api_v2");
        assertThatThrownBy(c::listTools).hasMessageContaining("not started");
        c.start();
        assertThatThrownBy(c::start).hasMessageContaining("already started");
        c.close();
        assertThatThrownBy(c::listTools).hasMessageContaining("not started");
    }

    @Test
    void httpDiscoveryUsesTemplateHeadersAuthAndCredentials() {
        backend.route("/utcp", r -> Reply.json(200, manual(tool("t", http("GET", backend.url("/x"))))));
        List<String> ops = new CopyOnWriteArrayList<>();
        UtcpClientConnector c = UtcpClientConnector.http("d", backend.url("/utcp"))
                .httpMethod("POST")
                .header("X-Static", "s")
                .auth(Map.of("auth_type", "api_key", "api_key", "k", "var_name", "key", "location", "query"))
                .credentials((p, target) -> {
                    assertThat(target).isEqualTo(backend.url("/utcp"));
                    return List.of(new Credential.BearerToken("bt"), new Credential.NamedSecret("X-Secret", "ns"));
                })
                .observations(new ObservationHandler() {
                    @Override
                    public void onSuccess(Observation o, Duration elapsed) {
                        ops.add(o.operation() + ":" + o.target());
                    }
                })
                .build();
        c.start();

        TestBackend.Recorded r = backend.last();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.query()).isEqualTo("key=k");
        assertThat(r.header("X-Static")).isEqualTo("s");
        assertThat(r.header("Authorization")).isEqualTo("Bearer bt");
        assertThat(r.header("X-Secret")).isEqualTo("ns");
        assertThat(c.listTools()).extracting(ToolSpec::name).containsExactly("t");
        assertThat(ops).containsExactly("register_manual:d");
    }

    @Test
    void httpDiscoveryFailuresAreMapped() {
        backend.route("/denied", r -> Reply.json(401, "{\"error\":\"bad key\"}"))
                .route("/openapi", r -> Reply.json(200, "{\"openapi\":\"3.0.0\"}"))
                .route("/garbage", r -> Reply.text(200, "<html>"))
                .route("/m.yaml", r -> Reply.json(200, "{}"));

        assertThatThrownBy(() -> UtcpClientConnector.http("d", backend.url("/denied")).build().start())
                .hasMessage("Error connecting to HTTP provider 'd': 401, Unauthorized: bad key")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.AUTHENTICATION));
        assertThatThrownBy(() -> UtcpClientConnector.http("d", backend.url("/openapi")).build().start())
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.UNSUPPORTED));
        assertThatThrownBy(() -> UtcpClientConnector.http("d", backend.url("/garbage")).build().start())
                .hasMessageStartingWith("Error parsing spec from HTTP provider 'd'")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.PROTOCOL));
        assertThatThrownBy(() -> UtcpClientConnector.http("d", backend.url("/m.yaml")).build().start())
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.UNSUPPORTED));
    }

    @Test
    void searchScoresTagsAndDescriptionWords() {
        String m = manual(
                tool("weather", "Get current weather for a city", List.of("weather"), Map.of("call_template_type", "text", "content", "")),
                tool("stocks", "Stock prices", List.of("Finance", "stock market"), Map.of("call_template_type", "text", "content", "")),
                tool("echo", "go to it", List.of(), Map.of("call_template_type", "text", "content", "")));
        UtcpClientConnector c = UtcpClientConnector.text("s", m).build();
        c.start();

        assertThat(c.search("weather in the city")).extracting(ToolSpec::name).containsExactly("weather", "stocks", "echo");
        assertThat(c.search("market news", 2, null)).extracting(ToolSpec::name).containsExactly("stocks", "weather");
        assertThat(c.search("anything", 10, List.of("FINANCE"))).extracting(ToolSpec::name).containsExactly("stocks");
        assertThat(c.search("go it", 10, null)).extracting(ToolSpec::name).containsExactly("weather", "stocks", "echo");
        assertThat(c.search("prices city", 10, null)).extracting(ToolSpec::name).containsExactly("weather", "stocks", "echo");
        assertThatThrownBy(() -> c.search("x", -1, null))
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void searchWeightsMatchTheReference() {
        Manual.Tool a = new Manual.Tool("a", "alpha beta", Map.of(), Map.of(), List.of("x"), null, Map.of());
        Manual.Tool b = new Manual.Tool("b", "alpha beta gamma", Map.of(), Map.of(), List.of(), null, Map.of());
        // a: tag "x" absent, "alpha"(+1) → 1 ; b: "alpha"(+1) + "gamma"(+1) → 2
        assertThat(ToolSearch.search(List.of(a, b), "alpha gamma", 10, null)).extracting(Manual.Tool::name)
                .containsExactly("b", "a");
        // tag substring match beats two description words
        assertThat(ToolSearch.search(List.of(b, a), "alpha gamma x", 10, null)).extracting(Manual.Tool::name)
                .containsExactly("a", "b");
    }
}
