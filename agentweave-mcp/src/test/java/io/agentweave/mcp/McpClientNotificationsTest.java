package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolSpec;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Our stdio client against SDK-built servers: undeclared capabilities and list-change notifications. */
class McpClientNotificationsTest {

    private McpClientConnector connector;

    @AfterEach
    void close() {
        if (connector != null) {
            connector.close();
        }
    }

    /** Logging is missing here only because SDK 2.1.0 servers always declare it. */
    @Test
    void everyFeatureTheServerDoesNotDeclareIsUnsupported() {
        connector = server("--no-tools");
        connector.start();

        List<ThrowingCallable> calls = List.of(connector::listResources, connector::listResourceTemplates,
                () -> connector.readResource("x://y"), () -> connector.subscribe("x://y", c -> { }),
                () -> connector.unsubscribe("x://y"), connector::listPrompts,
                () -> connector.getPrompt("p", Map.of()),
                () -> connector.completePromptArgument("p", "a", "", Map.of()),
                () -> connector.completeTemplateVariable("x://{y}", "y", "", Map.of()));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED);
                assertThat(e.getMessage()).contains("did not declare");
            });
        }
    }

    @Test
    void subscribingNeedsTheSubscribeCapability() {
        connector = server("--dynamic"); // resources without subscribe
        connector.start();

        assertThat(connector.listResources()).isEmpty();
        assertThatThrownBy(() -> connector.subscribe("test://grown", c -> { }))
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.getMessage()).contains("resources.subscribe"));
    }

    @Test
    void listChangeListenersReceiveTheNewLists() throws Exception {
        connector = server("--dynamic");
        BlockingQueue<List<ToolSpec>> tools = new LinkedBlockingQueue<>();
        BlockingQueue<List<McpResourceInfo>> resources = new LinkedBlockingQueue<>();
        BlockingQueue<List<McpPromptInfo>> prompts = new LinkedBlockingQueue<>();
        connector.onToolsChanged(tools::add);
        connector.onResourcesChanged(resources::add);
        connector.onPromptsChanged(prompts::add);
        connector.start();

        connector.invoke(ToolInvocation.of("grow", Map.of()));

        assertThat(tools.poll(10, TimeUnit.SECONDS)).extracting(ToolSpec::name).contains("grow", "echo");
        assertThat(resources.poll(10, TimeUnit.SECONDS)).extracting(McpResourceInfo::uri).containsExactly("test://grown");
        assertThat(prompts.poll(10, TimeUnit.SECONDS)).extracting(McpPromptInfo::name).containsExactly("grown");
    }

    private static McpClientConnector server(String flag) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return McpClientConnector.stdio(java, "-cp", System.getProperty("agentweave.test.classpath"),
                        TestMcpServer.class.getName(), flag)
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }
}
