package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Our Streamable HTTP client against our HTTP server, hosted by embedded Jetty. */
class McpHttpEndToEndTest {

    static final String TOKEN = "good-token";

    /** Bearer {@value #TOKEN} → alice; {@code X-Api-Key: k} → bob; anything else → 401. */
    static final InboundAuthenticator AUTH = request -> {
        if (request.bearerToken().filter(TOKEN::equals).isPresent()) {
            return AuthDecision.allow(CallerIdentity.of("alice"));
        }
        if (request.header("X-Api-Key").filter("k"::equals).isPresent()) {
            return AuthDecision.allow(CallerIdentity.of("bob"));
        }
        return AuthDecision.unauthenticated("missing or invalid credentials");
    };

    private static final List<String> serverCalls = new CopyOnWriteArrayList<>();
    private static ProtoweftServer server;
    private McpClientConnector connector;

    @BeforeAll
    static void startServer() {
        McpToolServer.Http mcp = McpToolServer.http(TestTools.create())
                .serverInfo("protoweft-http-test", "1.0.0")
                .requestTimeout(Duration.ofSeconds(1))
                .allowedOrigins("http://trusted.example")
                .observations(new ObservationHandler() {
                    @Override
                    public void onSuccess(Observation o, Duration elapsed) {
                        serverCalls.add(o.operation() + ":" + o.target());
                    }
                });
        server = ProtoweftServer.builder().port(0).authenticator(AUTH).endpoints(mcp).start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void closeConnector() {
        if (connector != null) {
            connector.close();
        }
    }

    @Test
    void negotiatesOverStreamableHttpAndReportsThatTransportOnly() {
        connector = authenticated().build();
        ComplianceReport before = connector.compliance();
        assertThat(before.wireRevisions()).containsExactly("2025-03-26", "2025-06-18", "2025-11-25");
        assertThat(before.supported()).contains(McpFeatures.TRANSPORT_STREAMABLE_HTTP, McpFeatures.TOOLS_CALL)
                .doesNotContain(McpFeatures.TRANSPORT_STDIO);
        assertThat(before.unsupported()).contains(McpFeatures.TRANSPORT_STDIO, McpFeatures.TRANSPORT_SSE,
                McpFeatures.AUTHORIZATION);
        assertThat(before.sdkVersion()).isEqualTo("2.1.0");

        connector.start();

        assertThat(connector.compliance().negotiated()).contains("2025-11-25");
    }

    @Test
    void listsTheServedTools() {
        connector = authenticated().build();
        connector.start();

        List<ToolSpec> tools = connector.listTools();

        assertThat(tools).extracting(ToolSpec::name).contains("echo", "add", "whoami");
        ToolSpec echo = tools.stream().filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
        assertThat(echo.description()).isEqualTo("Echoes the message");
        assertThat(echo.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void structuredOutputRoundTripsOnlyForJsonObjects() {
        connector = authenticated().build();
        connector.start();

        ToolOutcome sum = connector.invoke(ToolInvocation.of("add", Map.of("a", 2, "b", 3)));
        assertThat(sum.text()).isEqualTo("5");
        assertThat(sum.structuredContent()).hasValueSatisfying(s -> assertThat(s).isEqualTo(Map.of("sum", 5)));

        ToolOutcome list = connector.invoke(ToolInvocation.of("list", Map.of()));
        assertThat(list.text()).isEqualTo("[1,2]");
        assertThat(list.structuredContent()).isEmpty();
    }

    @Test
    void toolFailuresAndInvalidArgumentsAreToolErrors() {
        connector = authenticated().build();
        connector.start();

        ToolOutcome failed = connector.invoke(ToolInvocation.of("fail", Map.of()));
        assertThat(failed.error()).isTrue();
        assertThat(failed.text()).isEqualTo("it broke");

        ToolOutcome rejected = connector.invoke(ToolInvocation.of("strict", Map.of()));
        assertThat(rejected.error()).isTrue();
        assertThat(rejected.text()).isEqualTo("date must be ISO-8601");
    }

    @Test
    void unknownToolIsJsonRpcInvalidParams() {
        connector = authenticated().build();
        connector.start();

        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of("nope", Map.of())))
                .isInstanceOfSatisfying(ProtoweftException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                    assertThat(e.protocolCode()).contains("-32602");
                });
    }

    @Test
    void otherToolSourceFailuresAreJsonRpcInternalErrors() {
        connector = authenticated().build();
        connector.start();

        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of("unavailable", Map.of())))
                .isInstanceOfSatisfying(ProtoweftException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.PROTOCOL);
                    assertThat(e.protocolCode()).contains("-32603");
                    assertThat(e.getMessage()).contains("backend unreachable");
                });
    }

    @Test
    void slowToolsHitTheServerTimeout() {
        connector = authenticated().build();
        connector.start();

        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of("slow", Map.of("ms", 3000))))
                .isInstanceOfSatisfying(ProtoweftException.class, e -> {
                    assertThat(e.protocolCode()).contains("-32603");
                    assertThat(e.getMessage()).contains("timed out");
                });
    }

    @Test
    void mapsImageJsonAndLinkContent() {
        connector = authenticated().build();
        connector.start();

        ToolOutcome media = connector.invoke(ToolInvocation.of("media", Map.of()));

        assertThat(media.content()).containsExactly(
                new Content.Data("image/png", TestTools.PNG),
                new Content.Text("{\"k\":\"v\"}"),
                new Content.Link("https://example.com/report.pdf", "application/pdf", "report"));
    }

    @Test
    void binaryContentMcpCannotCarryIsRejectedNotDropped() {
        connector = authenticated().build();
        connector.start();

        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of("pdf", Map.of())))
                .isInstanceOfSatisfying(ProtoweftException.class, e -> {
                    assertThat(e.protocolCode()).contains("-32603");
                    assertThat(e.getMessage()).contains("application/pdf");
                });
    }

    @Test
    void bearerCredentialAuthenticatesAndTheCallerReachesTheTool() {
        connector = authenticated().build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("whoami", Map.of())).text()).isEqualTo("alice");
    }

    @Test
    void namedSecretBecomesAHeader() {
        connector = McpClientConnector.http(server.baseUrl())
                .credentials((p, t) -> List.of(new Credential.NamedSecret("X-Api-Key", "k")))
                .build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("whoami", Map.of())).text()).isEqualTo("bob");
    }

    @Test
    void staticHeadersAreSent() {
        connector = McpClientConnector.http(server.baseUrl()).header("X-Api-Key", "k").build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("whoami", Map.of())).text()).isEqualTo("bob");
    }

    @Test
    void credentialProviderSeesTheBaseUrl() {
        List<String> targets = new CopyOnWriteArrayList<>();
        connector = McpClientConnector.http(server.baseUrl()).credentials((p, t) -> {
            targets.add(t);
            return List.of(new Credential.BearerToken(TOKEN));
        }).build();
        connector.start();

        assertThat(targets).containsExactly(server.baseUrl());
    }

    @Test
    void missingTokenIsAnAuthenticationFailure() {
        connector = McpClientConnector.http(server.baseUrl()).build();

        assertThatThrownBy(connector::start)
                .isInstanceOfSatisfying(ProtoweftException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION);
                    assertThat(e.protocolCode()).contains("401");
                });
    }

    @Test
    void untrustedBrowserOriginsAreRejected() {
        connector = authenticated().header("Origin", "http://evil.example").build();
        assertThatThrownBy(connector::start)
                .isInstanceOfSatisfying(ProtoweftException.class, e -> assertThat(e.protocolCode()).contains("403"));

        McpClientConnector trusted = authenticated().header("Origin", "http://trusted.example").build();
        try {
            trusted.start();
            assertThat(trusted.invoke(ToolInvocation.of("echo", Map.of("message", "hi"))).text()).isEqualTo("hi");
        } finally {
            trusted.close();
        }
    }

    @Test
    void observationTargetIsTheBaseUrlWithoutQuery() {
        List<String> seen = new CopyOnWriteArrayList<>();
        connector = McpClientConnector.http(server.baseUrl() + "/?api_key=secret")
                .credentials((p, t) -> List.of(new Credential.BearerToken(TOKEN)))
                .observations(new ObservationHandler() {
                    @Override
                    public void onSuccess(Observation o, Duration elapsed) {
                        seen.add(o.operation() + ":" + o.target());
                    }
                })
                .build();
        connector.start();
        connector.invoke(ToolInvocation.of("echo", Map.of("message", "x")));

        assertThat(seen).containsExactly("initialize:" + server.baseUrl() + "/", "tools/call:echo");
        assertThat(serverCalls).contains("tools/call:echo");
    }

    @Test
    void serverReportsItsTransportAndTheSdkSeparately() {
        ComplianceReport report = McpToolServer.http(TestTools.create()).compliance();

        assertThat(report.sdkName()).isEqualTo("io.modelcontextprotocol.sdk:mcp");
        assertThat(report.sdkVersion()).isEqualTo("2.1.0");
        assertThat(report.wireRevisions()).containsExactly("2025-03-26", "2025-06-18", "2025-11-25");
        assertThat(report.negotiated()).isEmpty();
        assertThat(report.supported()).contains(McpFeatures.TRANSPORT_STREAMABLE_HTTP, McpFeatures.TOOLS_LIST,
                McpFeatures.TOOLS_CALL, McpFeatures.TOOLS_STRUCTURED_OUTPUT, McpFeatures.CONTENT_RESOURCE_LINK,
                McpFeatures.RESOURCES, McpFeatures.RESOURCES_SUBSCRIBE, McpFeatures.PROMPTS, McpFeatures.COMPLETION,
                McpFeatures.LOGGING, McpFeatures.PROGRESS);
        assertThat(report.unsupported()).containsExactlyInAnyOrder(McpFeatures.TRANSPORT_STDIO, McpFeatures.TRANSPORT_SSE,
                McpFeatures.AUTHORIZATION, McpFeatures.TOOLS_LIST_CHANGED, McpFeatures.RESOURCES_LIST_CHANGED,
                McpFeatures.PROMPTS_LIST_CHANGED, McpFeatures.CANCELLATION, McpFeatures.SAMPLING, McpFeatures.ROOTS,
                McpFeatures.ELICITATION);
    }

    @Test
    void configurationIsFrozenOnceEndpointsAreBuilt() {
        McpToolServer.Http mcp = McpToolServer.http(TestTools.create());
        assertThat(mcp.endpoints()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("mcp");
            assertThat(e.urlPatterns()).containsExactly("/mcp");
            assertThat(e.asyncSupported()).isTrue();
        });
        assertThatThrownBy(() -> mcp.endpointPath("/other")).isInstanceOf(IllegalStateException.class);
    }

    private static McpClientConnector.HttpBuilder authenticated() {
        return McpClientConnector.http(server.baseUrl())
                .requestTimeout(Duration.ofSeconds(10))
                .credentials((p, t) -> List.of(new Credential.BearerToken(TOKEN)));
    }
}
