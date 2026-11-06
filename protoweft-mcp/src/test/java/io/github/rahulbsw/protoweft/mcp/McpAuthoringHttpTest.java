package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AuthoringContract} over Streamable HTTP, plus a server that publishes no resources or prompts. */
class McpAuthoringHttpTest extends AuthoringContract {

    private static ProtoweftServer server;
    private McpClientConnector connector;

    @BeforeAll
    static void startServer() {
        TestAuthoring.Fixture fixture = TestAuthoring.create();
        server = ProtoweftServer.builder().port(0).authenticator(McpHttpEndToEndTest.AUTH)
                .endpoints(McpToolServer.http(fixture.tools()).resources(fixture.resources()).prompts(fixture.prompts()),
                        McpToolServer.http(TestTools.create()).endpointPath("/plain"))
                .start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void close() {
        if (connector != null) {
            connector.close();
        }
    }

    @Override
    McpClientConnector connector() {
        connector = client("/mcp");
        return connector;
    }

    @Override
    String expectedCaller() {
        return "alice";
    }

    @Test
    void capabilitiesTheServerDoesNotDeclareAreUnsupported() {
        connector = client("/plain");

        List<ThrowingCallable> calls = List.of(connector::listResources, connector::listResourceTemplates,
                () -> connector.readResource("test://static-text"),
                () -> connector.subscribe("test://static-text", c -> { }),
                connector::listPrompts, () -> connector.getPrompt("test_simple_prompt", Map.of()),
                () -> connector.completePromptArgument("p", "a", "", Map.of()));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOfSatisfying(ProtoweftException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED));
        }
        connector.setLogLevel(ToolProgress.Level.ERROR); // logging is declared by every Protoweft server
    }

    private static McpClientConnector client(String path) {
        McpClientConnector c = McpClientConnector.http(server.baseUrl()).endpoint(path)
                .credentials((p, t) -> List.of(new Credential.BearerToken(McpHttpEndToEndTest.TOKEN)))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        c.start();
        return c;
    }
}
