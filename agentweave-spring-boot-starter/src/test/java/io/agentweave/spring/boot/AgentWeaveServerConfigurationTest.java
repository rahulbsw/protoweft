package io.agentweave.spring.boot;

import io.agentweave.a2a.A2aAgentServer;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.InboundAuthFilter;
import io.agentweave.utcp.UtcpManualProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWeaveServerConfigurationTest {

    private static final AgentHandler ECHO = (request, task) -> task.reply(request.text());

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWeaveAutoConfiguration.class,
                    AgentWeaveServerAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader("org.springframework.security"))
            .withBean(TestTools.class);

    @Test
    void nothingIsExposedWithoutOptIn() {
        runner.withBean(AgentHandler.class, () -> ECHO).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AgentWeaveEndpointRegistrar.class);
            assertThat(context).doesNotHaveBean(McpToolServer.Http.class);
            assertThat(context).doesNotHaveBean(UtcpManualProvider.class);
            assertThat(context).doesNotHaveBean(A2aAgentServer.class);
        });
    }

    @Test
    void mcpIsMountedAsyncAtTheConfiguredPath() {
        runner.withPropertyValues("agentweave.server.mcp.enabled=true", "agentweave.server.mcp.path=/tools/mcp")
                .run(context -> {
                    AgentWeaveEndpointRegistrar registrar = registrar(context, "agentWeaveMcpEndpoints");
                    ServletRegistrationBean<?> servlet = registrar.servlets().get(0);
                    assertThat(servlet.getUrlMappings()).containsExactly("/tools/mcp");
                    assertThat(servlet.isAsyncSupported()).isTrue();
                    FilterRegistrationBean<?> filter = registrar.filters().get(0);
                    assertThat(filter.getFilter()).isInstanceOf(InboundAuthFilter.class);
                    assertThat(filter.getUrlPatterns()).containsExactly("/tools/mcp");
                    assertThat(filter.isAsyncSupported()).isTrue();
                    assertThat(registrar.getOrder()).isZero();
                });
    }

    @Test
    void mcpOverTheRegistryIsDeferredUntilConnectorsStart() {
        runner.withPropertyValues("agentweave.server.mcp.enabled=true", "agentweave.server.mcp.expose=registry")
                .run(context -> assertThat(registrar(context, "agentWeaveMcpEndpoints").servlets().get(0).getServlet())
                        .isInstanceOf(DeferredServlet.class));
    }

    @Test
    void utcpIsMountedUnderSlashUtcp() {
        runner.withPropertyValues("agentweave.server.utcp.enabled=true", "agentweave.server.auth-filter-order=5")
                .run(context -> {
                    AgentWeaveEndpointRegistrar registrar = registrar(context, "agentWeaveUtcpEndpoints");
                    assertThat(registrar.servlets().get(0).getUrlMappings()).containsExactly("/utcp", "/utcp/*");
                    assertThat(registrar.filters().get(0).getUrlPatterns()).containsExactly("/utcp", "/utcp/*");
                    assertThat(registrar.getOrder()).isEqualTo(5);
                });
    }

    @Test
    void a2aMountsTheAgentCardAndJsonRpcEndpoints() {
        runner.withBean(AgentHandler.class, () -> ECHO)
                .withPropertyValues("agentweave.server.a2a.enabled=true", "agentweave.server.a2a.name=Echo",
                        "agentweave.server.a2a.public-url=https://agents.example.com",
                        "agentweave.server.a2a.skills[0].id=echo", "agentweave.server.a2a.skills[0].tags[0]=demo")
                .run(context -> {
                    AgentWeaveEndpointRegistrar registrar = registrar(context, "agentWeaveA2aEndpoints");
                    assertThat(registrar.servlets()).flatExtracting(ServletRegistrationBean::getUrlMappings)
                            .containsExactly(A2aAgentServer.AGENT_CARD_PATH, "/a2a");
                    assertThat(registrar.servlets()).allMatch(ServletRegistrationBean::isAsyncSupported);
                    assertThat(context.getBean(A2aAgentServer.class).interfaceUrl())
                            .isEqualTo("https://agents.example.com/a2a");
                });
    }

    @Test
    void anAgentDescriptorBeanReplacesTheProperties() {
        runner.withBean(AgentHandler.class, () -> ECHO)
                .withBean(AgentDescriptor.class, () -> new AgentDescriptor("Bean agent", "", "2.0", List.of()))
                .withPropertyValues("agentweave.server.a2a.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(A2aAgentServer.class));
    }

    @Test
    void anInboundAuthenticatorBeanGuardsEveryEndpoint() {
        InboundAuthenticator auth = request -> io.agentweave.core.server.AuthDecision.unauthenticated("no");
        runner.withBean(InboundAuthenticator.class, () -> auth)
                .withPropertyValues("agentweave.server.mcp.enabled=true", "agentweave.server.utcp.enabled=true")
                .run(context -> assertThat(context.getBeansOfType(AgentWeaveEndpointRegistrar.class)).hasSize(2));
    }

    @Test
    void invalidServerConfigurationIsRejected() {
        assertInvalid("agentweave.server.a2a.enabled", "agentweave.server.a2a.enabled=true",
                "agentweave.server.a2a.name=x");
        runner.withBean(AgentHandler.class, () -> ECHO).withPropertyValues("agentweave.server.a2a.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentweave.server.a2a.name"));
        assertInvalid("agentweave.server.mcp.path", "agentweave.server.mcp.enabled=true",
                "agentweave.server.mcp.path=mcp");
        assertInvalid("agentweave.server.a2a.path", "agentweave.server.mcp.enabled=true",
                "agentweave.server.a2a.enabled=true", "agentweave.server.a2a.path=/mcp");
        assertInvalid("agentweave.server.utcp.api-key-header", "agentweave.server.utcp.enabled=true",
                "agentweave.server.utcp.api-key-header=X-Key");
        assertInvalid("agentweave.server.utcp.base-url", "agentweave.server.utcp.enabled=true",
                "agentweave.server.utcp.base-url=api.example.com");
        runner.withClassLoader(new FilteredClassLoader(McpToolServer.class))
                .withPropertyValues("agentweave.server.mcp.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.agentweave:agentweave-mcp"));
    }

    private static AgentWeaveEndpointRegistrar registrar(AssertableWebApplicationContext context, String name) {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(AgentWeaveEndpointRegistrar.class)).containsOnlyKeys(name);
        return context.getBean(name, AgentWeaveEndpointRegistrar.class);
    }

    private void assertInvalid(String property, String... properties) {
        runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining(property));
    }
}
