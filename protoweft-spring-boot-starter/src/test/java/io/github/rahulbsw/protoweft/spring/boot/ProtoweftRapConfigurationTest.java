package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.rap.FileRapStore;
import io.github.rahulbsw.protoweft.rap.InMemoryRapStore;
import io.github.rahulbsw.protoweft.rap.RapCallbackEndpoint;
import io.github.rahulbsw.protoweft.rap.RapCallbackListener;
import io.github.rahulbsw.protoweft.rap.RapClientConnector;
import io.github.rahulbsw.protoweft.rap.RapStore;
import io.github.rahulbsw.protoweft.rap.RapToolServer;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** RAP wiring with context runners: connectors, callback hosting, store, server mounting and validation. */
class ProtoweftRapConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtoweftAutoConfiguration.class,
                    ProtoweftServerAutoConfiguration.class, ProtoweftActuatorAutoConfiguration.class));

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtoweftAutoConfiguration.class,
                    ProtoweftServerAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader("org.springframework.security"))
            .withBean(RapTestTools.class);

    @Test
    void theStoreIsInMemoryByDefaultAndFileBasedWithADirectory(@TempDir Path dir) {
        runner.run(context -> assertThat(context.getBean(RapStore.class)).isInstanceOf(InMemoryRapStore.class));
        runner.withPropertyValues("protoweft.rap.store.dir=" + dir.resolve("rap")).run(context -> {
            assertThat(context.getBean(RapStore.class)).isInstanceOf(FileRapStore.class);
            assertThat(Files.isDirectory(dir.resolve("rap"))).isTrue();
        });
        RapStore own = new InMemoryRapStore();
        runner.withBean(RapStore.class, () -> own).run(context -> assertThat(context.getBean(RapStore.class)).isSameAs(own));
    }

    @Test
    void aConnectorWithTheListenerIsBuiltAndReportedLikeAnyOther() {
        runner.withPropertyValues("protoweft.fail-fast=false", "protoweft.rap.servers.tools.url=http://127.0.0.1:1",
                        "protoweft.rap.servers.tools.request-timeout=2s",
                        "protoweft.rap.callback.listener.enabled=true",
                        "protoweft.rap.callback.public-base-url=https://agent.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(RapCallbackEndpoint.class);
                    assertThat(context.getBean(RapCallbackListener.class).baseUrl()).isEqualTo("https://agent.example.com");
                    ProtoweftConnectors connectors = context.getBean(ProtoweftConnectors.class);
                    assertThat(connectors.connector("tools", RapClientConnector.class)).isNotNull();
                    assertThat(connectors.status().get("tools").state()).isEqualTo(ProtoweftConnectors.State.FAILED);
                    assertThat(context.getBean(ToolRegistry.class).sources()).doesNotContainKey("tools");
                    assertThat(context.getBean(ProtoweftHealthIndicator.class).health().getStatus()).isEqualTo(Status.DOWN);
                    Info.Builder info = new Info.Builder();
                    context.getBean(ProtoweftInfoContributor.class).contribute(info);
                    assertThat(info.build().getDetails()).extractingByKey("protoweft").asString()
                            .contains("tools", "protocol=rap", "infinity@e1a3e4b", "view_update");
                });
    }

    @Test
    void withoutAServletContainerOrListenerRapConnectorsAreRejected() {
        runner.withPropertyValues("protoweft.rap.servers.tools.url=https://tools.example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                        .hasMessageContaining("protoweft.rap.callback.listener.enabled"));
    }

    @Test
    void invalidRapPropertiesAreRejected() {
        assertInvalid("protoweft.rap.servers.x.url", "protoweft.rap.servers.x.timeout=5s");
        assertInvalid("protoweft.rap.servers.x.url", "protoweft.rap.servers.x.url=tools.example.com");
        assertInvalid("protoweft.rap.servers.x.additional-urls[0]", "protoweft.rap.servers.x.url=https://a.example.com",
                "protoweft.rap.servers.x.additional-urls[0]=b.example.com");
        assertInvalid("protoweft.rap.callback.public-base-url", "protoweft.rap.callback.public-base-url=agent.example.com");
        assertInvalid("protoweft.rap.servers.local", "protoweft.rap.servers.local.url=https://a.example.com");
        runner.withClassLoader(new FilteredClassLoader(RapClientConnector.class))
                .withPropertyValues("protoweft.rap.servers.x.url=https://tools.example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.github.rahulbsw:protoweft-rap"));
    }

    @Test
    void theServerIsMountedUnderItsBasePath() {
        web.withPropertyValues("protoweft.server.rap.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RapToolServer.class);
            ProtoweftEndpointRegistrar registrar = context.getBean("protoweftRapEndpoints", ProtoweftEndpointRegistrar.class);
            assertThat(registrar.servlets()).flatExtracting(ServletRegistrationBean::getUrlMappings)
                    .containsExactly("/rap/.well-known/rap-toolset", "/rap/invoke", "/rap/close_thread",
                            "/rap/cancel_tool_call", "/rap/user_choice_response");
            assertThat(registrar.filters().get(0).getFilter()).isInstanceOf(InboundAuthFilter.class);
            assertThat(context).doesNotHaveBean("protoweftRapCallbackEndpoints");
        });
        web.withPropertyValues("protoweft.server.rap.enabled=true", "protoweft.server.rap.base-path=/")
                .run(context -> assertThat(context.getBean("protoweftRapEndpoints", ProtoweftEndpointRegistrar.class)
                        .servlets().get(0).getUrlMappings()).contains("/.well-known/rap-toolset", "/invoke"));
    }

    @Test
    void declaredConnectorsMountTheCallbackEndpointUnlessTheListenerIsUsed() {
        // The connector starts when the context refreshes; fail-fast=false tolerates the unreachable server.
        web.withPropertyValues("protoweft.fail-fast=false", "protoweft.rap.servers.tools.url=http://127.0.0.1:1").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RapCallbackEndpoint.class)
                    .doesNotHaveBean(RapCallbackListener.class).doesNotHaveBean(RapToolServer.class);
            ProtoweftEndpointRegistrar registrar = context.getBean("protoweftRapCallbackEndpoints",
                    ProtoweftEndpointRegistrar.class);
            assertThat(registrar.servlets()).flatExtracting(ServletRegistrationBean::getUrlMappings)
                    .containsExactly("/rap/callback/*");
            assertThat(registrar.filters().get(0).getUrlPatterns()).containsExactly("/rap/callback/*");
        });
        web.withPropertyValues("protoweft.fail-fast=false", "protoweft.rap.servers.tools.url=http://127.0.0.1:1",
                        "protoweft.rap.callback.listener.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RapCallbackEndpoint.class)
                        .hasSingleBean(RapCallbackListener.class));
        web.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RapCallbackEndpoint.class)
                .doesNotHaveBean(ProtoweftEndpointRegistrar.class));
    }

    @Test
    void invalidServerConfigurationIsRejected() {
        assertInvalidWeb("protoweft.server.rap.base-path", "protoweft.server.rap.enabled=true",
                "protoweft.server.rap.base-path=rap");
        assertInvalidWeb("protoweft.server.rap.base-path", "protoweft.server.rap.enabled=true",
                "protoweft.server.rap.base-path=/rap/callback");
        assertInvalidWeb("protoweft.server.rap.base-path", "protoweft.server.rap.enabled=true",
                "protoweft.server.mcp.enabled=true", "protoweft.server.rap.base-path=/mcp");
        assertInvalidWeb("protoweft.server.rap.base-url", "protoweft.server.rap.enabled=true",
                "protoweft.server.rap.base-url=tools.example.com");
        assertInvalidWeb("protoweft.server.rap.name", "protoweft.server.rap.enabled=true",
                "protoweft.server.rap.name=" + "n".repeat(129));
        web.withClassLoader(new FilteredClassLoader(RapToolServer.class))
                .withPropertyValues("protoweft.server.rap.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.github.rahulbsw:protoweft-rap"));
    }

    private void assertInvalid(String property, String... properties) {
        runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining(property));
    }

    private void assertInvalidWeb(String property, String... properties) {
        web.withPropertyValues(properties).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining(property));
    }
}
