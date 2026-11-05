package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Our stdio client against {@link TestStdioToolServer} running as a subprocess. The server's
 * classpath omits the servlet API, proving stdio serving does not need it.
 */
class McpToolServerStdioTest {

    private McpClientConnector connector;

    @AfterEach
    void close() {
        if (connector != null) {
            connector.close();
        }
    }

    @Test
    void servesToolsOverStdio() {
        connector = McpClientConnector.stdio(java(), serverCommand()).requestTimeout(Duration.ofSeconds(30)).build();
        connector.start();

        assertThat(connector.compliance().negotiated()).contains("2025-11-25");
        assertThat(connector.listTools()).extracting(ToolSpec::name).contains("echo", "add", "whoami");

        ToolOutcome sum = connector.invoke(ToolInvocation.of("add", Map.of("a", 2, "b", 3)));
        assertThat(sum.text()).isEqualTo("5");
        assertThat(sum.structuredContent()).hasValueSatisfying(s -> assertThat(s).isEqualTo(Map.of("sum", 5)));
        assertThat(connector.invoke(ToolInvocation.of("fail", Map.of())).error()).isTrue();
    }

    @Test
    void callersOverStdioAreAnonymous() {
        connector = McpClientConnector.stdio(java(), serverCommand()).requestTimeout(Duration.ofSeconds(30)).build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("whoami", Map.of())).text()).isEqualTo("anonymous");
    }

    @Test
    void strayStdoutPrintsDoNotCorruptTheProtocol() {
        connector = McpClientConnector.stdio(java(), serverCommand()).requestTimeout(Duration.ofSeconds(30)).build();
        connector.start();

        assertThat(connector.invoke(ToolInvocation.of("noisy", Map.of())).text()).isEqualTo("quiet");
        assertThat(connector.invoke(ToolInvocation.of("echo", Map.of("message", "still fine"))).text())
                .isEqualTo("still fine");
    }

    @Test
    void unknownToolIsInvalidParams() {
        connector = McpClientConnector.stdio(java(), serverCommand()).requestTimeout(Duration.ofSeconds(30)).build();
        connector.start();

        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of("nope", Map.of())))
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                    assertThat(e.protocolCode()).contains("-32602");
                });
    }

    @Test
    void exitsWhenStdinCloses() throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(java()));
        command.addAll(List.of(serverCommand()));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    @Test
    void stdioServerReportsItsTransport() {
        ComplianceReport report = McpToolServer.stdio(TestTools.create()).compliance();

        assertThat(report.wireRevisions()).containsExactly("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
        assertThat(report.negotiated()).isEmpty();
        assertThat(report.supported()).contains(McpFeatures.TRANSPORT_STDIO, McpFeatures.TOOLS_CALL, McpFeatures.RESOURCES,
                McpFeatures.PROMPTS, McpFeatures.LOGGING, McpFeatures.PROGRESS, McpFeatures.COMPLETION);
        assertThat(report.unsupported()).contains(McpFeatures.TRANSPORT_STREAMABLE_HTTP, McpFeatures.SAMPLING,
                McpFeatures.ELICITATION, McpFeatures.ROOTS, McpFeatures.RESOURCES_LIST_CHANGED);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String[] serverCommand() {
        List<String> full = Arrays.asList(System.getProperty("agentweave.test.classpath").split(File.pathSeparator));
        assertThat(full).anyMatch(entry -> entry.contains("jakarta.servlet-api")); // otherwise nothing is proven
        String classpath = full.stream()
                .filter(entry -> !entry.contains("jakarta.servlet-api"))
                .collect(Collectors.joining(File.pathSeparator));
        return new String[] {"-cp", classpath, TestStdioToolServer.class.getName()};
    }
}
