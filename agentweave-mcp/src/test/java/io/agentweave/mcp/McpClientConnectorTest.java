package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.observe.ObservationHandler.Observation;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end: the connector launches {@link TestMcpServer} over stdio and talks real MCP. */
class McpClientConnectorTest {

    private McpClientConnector connector;

    @AfterEach
    void close() {
        if (connector != null) {
            connector.close();
        }
    }

    @Test
    void negotiatesLatestRevisionAndReportsSdkVersionSeparately() {
        connector = server().build();
        assertThat(connector.compliance().negotiated()).isEmpty();

        connector.start();

        ComplianceReport report = connector.compliance();
        assertThat(report.sdkName()).isEqualTo("io.modelcontextprotocol.sdk:mcp");
        assertThat(report.sdkVersion()).isEqualTo("2.1.0");
        assertThat(report.wireRevisions()).containsExactly("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
        assertThat(report.negotiated()).contains("2025-11-25");
    }

    @Test
    void declaresOnlyWhatItImplements() {
        ComplianceReport report = server().build().compliance();
        assertThat(report.supported()).contains(McpFeatures.TRANSPORT_STDIO, McpFeatures.TOOLS_LIST, McpFeatures.TOOLS_CALL);
        assertThat(report.unsupported()).contains(McpFeatures.TRANSPORT_STREAMABLE_HTTP, McpFeatures.AUTHORIZATION,
                McpFeatures.RESOURCES, McpFeatures.PROMPTS, McpFeatures.SAMPLING);
    }

    @Test
    void listsToolsWithSchemas() {
        connector = server().build();
        connector.start();

        List<ToolSpec> tools = connector.listTools();

        assertThat(tools).extracting(ToolSpec::name).containsExactlyInAnyOrder("echo", "add", "fail", "env");
        ToolSpec echo = tools.stream().filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
        assertThat(echo.description()).isEqualTo("Echoes the message");
        assertThat(echo.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void invokesToolsAndMapsTextAndStructuredContent() {
        connector = server().build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("echo", Map.of("message", "hello"))).text()).isEqualTo("hello");

        ToolOutcome sum = connector.invoke(ToolInvocation.of("add", Map.of("a", 2, "b", 3)));
        assertThat(sum.error()).isFalse();
        assertThat(sum.text()).isEqualTo("5");
        assertThat(sum.structuredContent()).hasValueSatisfying(s -> assertThat(s).isEqualTo(Map.of("sum", 5)));
    }

    @Test
    void toolReportedFailureIsAnOutcomeNotAnException() {
        connector = server().build();
        connector.start();

        ToolOutcome outcome = connector.invoke(ToolInvocation.of("fail", Map.of()));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.text()).isEqualTo("it broke");
    }

    @Test
    void unknownToolMapsToInvalidArgumentsWithJsonRpcCode() {
        connector = server().build();
        connector.start();

        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of("nope", Map.of())))
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                    assertThat(e.protocolCode()).contains("-32602");
                });
    }

    @Test
    void namedSecretCredentialsReachTheServerAsEnvironment() {
        connector = server()
                .credentials((protocol, target) -> List.of(new Credential.NamedSecret("AGENTWEAVE_TEST_KEY", "s3cret")))
                .build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("env", Map.of("name", "AGENTWEAVE_TEST_KEY"))).text())
                .isEqualTo("s3cret");
    }

    @Test
    void bearerTokensAreRejectedOverStdio() {
        connector = server().credentials((p, t) -> List.of(new Credential.BearerToken("tok"))).build();

        assertThatThrownBy(connector::start)
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
    }

    @Test
    void observationHandlerSeesEachOperation() {
        List<String> seen = new CopyOnWriteArrayList<>();
        connector = server().observations(new ObservationHandler() {
            @Override
            public void onSuccess(Observation o, Duration elapsed) {
                seen.add(o.operation() + ":" + o.target());
            }
        }).build();
        connector.start();
        connector.invoke(ToolInvocation.of("echo", Map.of("message", "x")));

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).startsWith("initialize:");
        assertThat(seen.get(1)).isEqualTo("tools/call:echo");
    }

    @Test
    void callsBeforeStartFailWithConfigurationError() {
        connector = server().build();
        assertThatThrownBy(connector::listTools)
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONFIGURATION));
    }

    @Test
    void serverWithoutToolsCapabilityIsReportedAsUnsupported() {
        connector = server("--no-tools").build();
        connector.start();

        assertThatThrownBy(connector::listTools)
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED));
    }

    private static McpClientConnector.Builder server(String... serverArgs) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("agentweave.test.classpath");
        List<String> args = new java.util.ArrayList<>(List.of("-cp", classpath, TestMcpServer.class.getName()));
        args.addAll(List.of(serverArgs));
        return McpClientConnector.stdio(java, args.toArray(String[]::new)).requestTimeout(Duration.ofSeconds(30));
    }
}
