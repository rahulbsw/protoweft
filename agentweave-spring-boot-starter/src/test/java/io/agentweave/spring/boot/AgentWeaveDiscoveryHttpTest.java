package io.agentweave.spring.boot;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryTools;
import io.agentweave.mcp.McpClientConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real application on Tomcat serving the discovery meta-tools over MCP at
 * {@code /mcp-discovery}: the local tools require a scope, a stdio MCP connector declared in
 * properties doesn't, and our MCP client searches and calls through the endpoint.
 */
class AgentWeaveDiscoveryHttpTest {

    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        List<String> properties = new java.util.ArrayList<>(List.of(
                "server.port=" + port,
                "agentweave.discovery.enabled=true",
                "agentweave.discovery.expose-mcp=true",
                "agentweave.discovery.sources.local.required-scopes=tools:local"));
        properties.addAll(List.of(TestStdioServer.clientProperties("files")));
        context = new SpringApplicationBuilder(App.class).web(WebApplicationType.SERVLET)
                .properties(properties.toArray(String[]::new)).run();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void searchCapabilitiesOverMcpHonorsTheCallersScopes() {
        try (McpClientConnector user = client("user-token")) {
            user.start();
            assertThat(user.listTools()).extracting(ToolSpec::name)
                    .containsExactlyInAnyOrder(DiscoveryTools.SEARCH, DiscoveryTools.DESCRIBE, DiscoveryTools.CALL);

            ToolOutcome echo = user.invoke(search("echo the message"));
            assertThat(names(echo)).first().isEqualTo("files.echo");
            assertThat(user.invoke(ToolInvocation.of(DiscoveryTools.CALL,
                    Map.of("name", "files.echo", "arguments", Map.of("message", "hi")))).text()).isEqualTo("hi");

            assertThat(names(user.invoke(search("add two integers")))).doesNotContain("local.add");
        }
        try (McpClientConnector admin = client("admin-token")) {
            admin.start();
            assertThat(names(admin.invoke(search("add two integers")))).first().isEqualTo("local.add");
            assertThat(admin.invoke(ToolInvocation.of(DiscoveryTools.CALL,
                    Map.of("name", "local.add", "arguments", Map.of("a", 3, "b", 4)))).text()).isEqualTo("7");
            assertThat(admin.invoke(ToolInvocation.of(DiscoveryTools.CALL,
                    Map.of("name", "local.whoami", "arguments", Map.of()))).text()).isEqualTo("admin");
        }
    }

    @Test
    void theDiscoveryEndpointRequiresTheInboundAuthenticator() {
        try (McpClientConnector anonymous = McpClientConnector.http(base).endpoint("/mcp-discovery").build()) {
            assertThatThrownBy(anonymous::start).isInstanceOfSatisfying(AgentWeaveException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
    }

    private static ToolInvocation search(String query) {
        return ToolInvocation.of(DiscoveryTools.SEARCH, Map.of("query", query));
    }

    private static List<String> names(ToolOutcome search) {
        Map<?, ?> structured = (Map<?, ?>) search.structuredContent().orElseThrow();
        return ((List<?>) structured.get("capabilities")).stream()
                .map(c -> String.valueOf(((Map<?, ?>) c).get("name"))).toList();
    }

    private static McpClientConnector client(String token) {
        return McpClientConnector.http(base).endpoint("/mcp-discovery")
                .credentials((p, t) -> List.of(new Credential.BearerToken(token))).build();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, AgentWeaveAutoConfiguration.class,
            AgentWeaveServerAutoConfiguration.class, AgentWeaveDiscoveryAutoConfiguration.class})
    static class App {

        @Bean
        TestTools testTools() {
            return new TestTools();
        }

        /** {@code admin-token} holds {@code tools:local}; {@code user-token} holds no scopes. */
        @Bean
        InboundAuthenticator bearerAuthenticator() {
            return request -> {
                if (request.bearerToken().filter("admin-token"::equals).isPresent()) {
                    return AuthDecision.allow(new CallerIdentity("admin",
                            Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("tools:local"))));
                }
                if (request.bearerToken().filter("user-token"::equals).isPresent()) {
                    return AuthDecision.allow(CallerIdentity.of("user"));
                }
                return AuthDecision.unauthenticated("bearer token required");
            };
        }
    }
}
