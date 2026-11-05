package io.agentweave.spring.boot;

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.mcp.McpClientConnector;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One real application on Tomcat serving MCP (over the whole registry), UTCP and A2A behind an
 * {@link InboundAuthenticator}, and consuming its own UTCP manual and A2A agent through connectors
 * declared in properties.
 */
class AgentWeaveHttpTest {

    private static final CredentialProvider BEARER = (protocol, target) -> List.of(new Credential.BearerToken("secret"));

    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        context = new SpringApplicationBuilder(App.class).web(WebApplicationType.SERVLET).properties(
                "server.port=" + port,
                "spring.application.name=echo-agent",
                "agentweave.server.mcp.enabled=true",
                "agentweave.server.mcp.expose=registry",
                "agentweave.server.utcp.enabled=true",
                "agentweave.server.utcp.api-key-header=Authorization",
                "agentweave.server.utcp.api-key-variable=TOKEN",
                "agentweave.server.a2a.enabled=true",
                "agentweave.utcp.manuals.self.url=" + base + "/utcp",
                "agentweave.utcp.manuals.self.variables.TOKEN=Bearer secret",
                "agentweave.a2a.agents.me.url=" + base).run();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void everyEndpointRequiresTheInboundAuthenticator() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> mcp = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mcp.statusCode()).isEqualTo(401);
        HttpResponse<String> utcp = http.send(HttpRequest.newBuilder(URI.create(base + "/utcp")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(utcp.statusCode()).isEqualTo(401);
        HttpResponse<String> card = http.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/agent-card.json"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(card.statusCode()).isEqualTo(401);
        try (McpClientConnector anonymous = McpClientConnector.http(base).build()) {
            assertThatThrownBy(anonymous::start).isInstanceOfSatisfying(AgentWeaveException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
    }

    @Test
    void connectorsFromPropertiesConsumeTheAppsOwnUtcpManualAndA2aAgent() {
        ToolRegistry registry = context.getBean(ToolRegistry.class);
        // The UTCP provider's call template sends the whole argument object as the "body" field.
        assertThat(registry.listTools()).extracting(ToolSpec::name)
                .contains("local.add", "local.whoami", "self.add", "self.whoami", "me.ask");
        assertThat(registry.invoke(ToolInvocation.of("self.add", Map.of("body", Map.of("a", 2, "b", 40)))).text()).isEqualTo("42");
        assertThat(registry.invoke(ToolInvocation.of("self.whoami", Map.of("body", Map.of()))).text()).isEqualTo("tester");
        assertThat(registry.invoke(ToolInvocation.of("me.ask", Map.of("message", "hi"))).text()).contains("echo: hi");
    }

    @Test
    void mcpServesTheWholeRegistryAndRecordsMicrometerObservations() {
        try (McpClientConnector mcp = McpClientConnector.http(base).credentials(BEARER).build()) {
            mcp.start();
            assertThat(mcp.listTools()).extracting(ToolSpec::name)
                    .contains("local.add", "self.add", "me.ask");
            assertThat(mcp.invoke(ToolInvocation.of("local.whoami", Map.of())).text()).isEqualTo("tester");
        }
        TestObservationRegistryAssert.assertThat(context.getBean(TestObservationRegistry.class)).hasAnObservation(o -> o
                .hasNameEqualTo(MicrometerObservationHandler.NAME)
                .hasLowCardinalityKeyValue(MicrometerObservationHandler.PROTOCOL, "mcp")
                .hasLowCardinalityKeyValue(MicrometerObservationHandler.OPERATION, "tools/call")
                .hasHighCardinalityKeyValue(MicrometerObservationHandler.TARGET, "local.whoami"));
    }

    @Test
    void a2aAgentCardAdvertisesTheLocalUrlWithoutPublicUrl() {
        try (A2aAgentConnector agent = A2aAgentConnector.builder(base).credentials(BEARER).build()) {
            agent.start();
            assertThat(agent.descriptor().name()).isEqualTo("echo-agent");
            assertThat(agent.send(AgentMessage.user("ping")).text()).contains("echo: ping");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, AgentWeaveAutoConfiguration.class,
            AgentWeaveServerAutoConfiguration.class, AgentWeaveMicrometerAutoConfiguration.class})
    static class App {

        @Bean
        TestTools testTools() {
            return new TestTools();
        }

        @Bean
        AgentHandler echoAgent() {
            return (request, task) -> task.reply("echo: " + request.text());
        }

        @Bean
        InboundAuthenticator bearerAuthenticator() {
            return request -> request.bearerToken().filter("secret"::equals).isPresent()
                    ? AuthDecision.allow(CallerIdentity.of("tester"))
                    : AuthDecision.unauthenticated("bearer token required");
        }

        @Bean
        CredentialProvider credentials() {
            return BEARER;
        }

        @Bean
        TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }
    }
}
