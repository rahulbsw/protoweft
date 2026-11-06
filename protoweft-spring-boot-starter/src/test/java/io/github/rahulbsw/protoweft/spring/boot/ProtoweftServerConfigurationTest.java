package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.mcp.McpToolServer;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import io.github.rahulbsw.protoweft.utcp.UtcpManualProvider;
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

class ProtoweftServerConfigurationTest {

    private static final AgentHandler ECHO = (request, task) -> task.reply(request.text());

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtoweftAutoConfiguration.class,
                    ProtoweftServerAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader("org.springframework.security"))
            .withBean(TestTools.class);

    @Test
    void nothingIsExposedWithoutOptIn() {
        runner.withBean(AgentHandler.class, () -> ECHO).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProtoweftEndpointRegistrar.class);
            assertThat(context).doesNotHaveBean(McpToolServer.Http.class);
            assertThat(context).doesNotHaveBean(UtcpManualProvider.class);
            assertThat(context).doesNotHaveBean(A2aAgentServer.class);
        });
    }

    @Test
    void mcpIsMountedAsyncAtTheConfiguredPath() {
        runner.withPropertyValues("protoweft.server.mcp.enabled=true", "protoweft.server.mcp.path=/tools/mcp")
                .run(context -> {
                    ProtoweftEndpointRegistrar registrar = registrar(context, "protoweftMcpEndpoints");
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
        runner.withPropertyValues("protoweft.server.mcp.enabled=true", "protoweft.server.mcp.expose=registry")
                .run(context -> assertThat(registrar(context, "protoweftMcpEndpoints").servlets().get(0).getServlet())
                        .isInstanceOf(DeferredServlet.class));
    }

    @Test
    void utcpIsMountedUnderSlashUtcp() {
        runner.withPropertyValues("protoweft.server.utcp.enabled=true", "protoweft.server.auth-filter-order=5")
                .run(context -> {
                    ProtoweftEndpointRegistrar registrar = registrar(context, "protoweftUtcpEndpoints");
                    assertThat(registrar.servlets().get(0).getUrlMappings()).containsExactly("/utcp", "/utcp/*");
                    assertThat(registrar.filters().get(0).getUrlPatterns()).containsExactly("/utcp", "/utcp/*");
                    assertThat(registrar.getOrder()).isEqualTo(5);
                });
    }

    @Test
    void a2aMountsTheAgentCardAndJsonRpcEndpoints() {
        runner.withBean(AgentHandler.class, () -> ECHO)
                .withPropertyValues("protoweft.server.a2a.enabled=true", "protoweft.server.a2a.name=Echo",
                        "protoweft.server.a2a.public-url=https://agents.example.com",
                        "protoweft.server.a2a.skills[0].id=echo", "protoweft.server.a2a.skills[0].tags[0]=demo")
                .run(context -> {
                    ProtoweftEndpointRegistrar registrar = registrar(context, "protoweftA2aEndpoints");
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
                .withPropertyValues("protoweft.server.a2a.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(A2aAgentServer.class));
    }

    @Test
    void anInboundAuthenticatorBeanGuardsEveryEndpoint() {
        InboundAuthenticator auth = request -> io.github.rahulbsw.protoweft.core.server.AuthDecision.unauthenticated("no");
        runner.withBean(InboundAuthenticator.class, () -> auth)
                .withPropertyValues("protoweft.server.mcp.enabled=true", "protoweft.server.utcp.enabled=true")
                .run(context -> assertThat(context.getBeansOfType(ProtoweftEndpointRegistrar.class)).hasSize(2));
    }

    @Test
    void invalidServerConfigurationIsRejected() {
        assertInvalid("protoweft.server.a2a.enabled", "protoweft.server.a2a.enabled=true",
                "protoweft.server.a2a.name=x");
        runner.withBean(AgentHandler.class, () -> ECHO).withPropertyValues("protoweft.server.a2a.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("protoweft.server.a2a.name"));
        assertInvalid("protoweft.server.mcp.path", "protoweft.server.mcp.enabled=true",
                "protoweft.server.mcp.path=mcp");
        assertInvalid("protoweft.server.a2a.path", "protoweft.server.mcp.enabled=true",
                "protoweft.server.a2a.enabled=true", "protoweft.server.a2a.path=/mcp");
        assertInvalid("protoweft.server.utcp.api-key-header", "protoweft.server.utcp.enabled=true",
                "protoweft.server.utcp.api-key-header=X-Key");
        assertInvalid("protoweft.server.utcp.base-url", "protoweft.server.utcp.enabled=true",
                "protoweft.server.utcp.base-url=api.example.com");
        runner.withClassLoader(new FilteredClassLoader(McpToolServer.class))
                .withPropertyValues("protoweft.server.mcp.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.github.rahulbsw:protoweft-mcp"));
    }

    private static ProtoweftEndpointRegistrar registrar(AssertableWebApplicationContext context, String name) {
        assertThat(context).hasNotFailed();
        assertThat(context.getBeansOfType(ProtoweftEndpointRegistrar.class)).containsOnlyKeys(name);
        return context.getBean(name, ProtoweftEndpointRegistrar.class);
    }

    private void assertInvalid(String property, String... properties) {
        runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining(property));
    }
}
