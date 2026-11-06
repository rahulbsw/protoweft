package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoweftAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProtoweftAutoConfiguration.class,
                    ProtoweftServerAutoConfiguration.class, ProtoweftMicrometerAutoConfiguration.class,
                    ProtoweftActuatorAutoConfiguration.class));

    @Test
    void agentToolBeansBecomeLocalToolsInTheRegistry() {
        runner.withBean(TestTools.class)
                .withBean("ping", LocalTools.LocalTool.class, () -> new LocalTools.LocalTool(
                        new ToolSpec("ping", "Health check", Map.of("type", "object")),
                        (args, caller) -> ToolOutcome.text("pong")))
                .run(context -> {
                    ToolRegistry registry = context.getBean(ToolRegistry.class);
                    assertThat(registry.listTools()).extracting(ToolSpec::name)
                            .containsExactlyInAnyOrder("local.add", "local.whoami", "local.ping");
                    assertThat(registry.invoke(ToolInvocation.of("local.add", Map.of("a", 2, "b", 3))).text())
                            .isEqualTo("5");
                    assertThat(context).doesNotHaveBean(ProtoweftEndpointRegistrar.class);
                });
    }

    @Test
    void localToolsNameIsConfigurableAndDiscoveryCanBeDisabled() {
        runner.withBean(TestTools.class).withPropertyValues("protoweft.local-tools.name=mine").run(context ->
                assertThat(context.getBean(ToolRegistry.class).sources()).containsOnlyKeys("mine"));
        runner.withBean(TestTools.class).withPropertyValues("protoweft.local-tools.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LocalTools.class);
            assertThat(context.getBean(ToolRegistry.class).sources()).isEmpty();
        });
    }

    @Test
    void stdioMcpClientFromPropertiesIsStartedNamespacedAndClosed() {
        TestObservationRegistry observations = TestObservationRegistry.create();
        List<String> seen = new CopyOnWriteArrayList<>();
        ObservationHandler own = new ObservationHandler() {
            @Override
            public void onStart(Observation observation) {
                seen.add(observation.operation());
            }
        };
        ProtoweftConnectors[] connectors = new ProtoweftConnectors[1];
        runner.withPropertyValues(TestStdioServer.clientProperties("files"))
                .withBean(TestObservationRegistry.class, () -> observations)
                .withBean(ObservationHandler.class, () -> own)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolRegistry registry = context.getBean(ToolRegistry.class);
                    assertThat(registry.listTools()).extracting(ToolSpec::name).containsExactly("files.echo");
                    assertThat(registry.invoke(ToolInvocation.of("files.echo", Map.of("message", "hi"))).text())
                            .isEqualTo("hi");
                    connectors[0] = context.getBean(ProtoweftConnectors.class);
                    assertThat(connectors[0].connector("files", McpClientConnector.class).compliance().negotiated())
                            .isPresent();
                    assertThat(context.getBean(ProtoweftHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
                    Info.Builder info = new Info.Builder();
                    context.getBean(ProtoweftInfoContributor.class).contribute(info);
                    assertThat(info.build().getDetails()).extractingByKey("protoweft").asString()
                            .contains("files", "mcp", "negotiatedRevision");
                });
        assertThat(seen).contains("initialize", "tools/list", "tools/call");
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(MicrometerObservationHandler.NAME)
                .that()
                .hasLowCardinalityKeyValue(MicrometerObservationHandler.PROTOCOL, "mcp")
                .hasBeenStarted()
                .hasBeenStopped();
        TestObservationRegistryAssert.assertThat(observations).hasAnObservation(o -> o
                .hasLowCardinalityKeyValue(MicrometerObservationHandler.OPERATION, "tools/call")
                .hasHighCardinalityKeyValue(MicrometerObservationHandler.TARGET, "echo"));
        assertThat(connectors[0].status().get("files").state()).isEqualTo(ProtoweftConnectors.State.CLOSED);
    }

    @Test
    void aConnectorThatFailsToStartFailsStartupByDefault() {
        runner.withPropertyValues("protoweft.mcp.clients.down.url=http://127.0.0.1:1",
                        "protoweft.mcp.clients.down.request-timeout=2s")
                .run(context -> assertThat(context).hasFailed().getFailure().cause()
                        .hasMessageContaining("connector 'down' failed to start")
                        .hasMessageContaining("protoweft.fail-fast=false"));
    }

    @Test
    void withoutFailFastAFailedConnectorIsLeftOutAndReportedDown() {
        runner.withBean(TestTools.class)
                .withPropertyValues("protoweft.fail-fast=false", "protoweft.mcp.clients.down.url=http://127.0.0.1:1",
                        "protoweft.mcp.clients.down.request-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ToolRegistry.class).sources()).containsOnlyKeys("local");
                    ProtoweftConnectors.Status status = context.getBean(ProtoweftConnectors.class).status().get("down");
                    assertThat(status.state()).isEqualTo(ProtoweftConnectors.State.FAILED);
                    assertThat(status.failure()).isNotNull();
                    assertThat(context.getBean(ProtoweftHealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Test
    void invalidClientPropertiesAreRejectedAtStartup() {
        assertInvalid("protoweft.mcp.clients.x", "protoweft.mcp.clients.x.command=npx",
                "protoweft.mcp.clients.x.url=https://mcp.example.com");
        assertInvalid("protoweft.mcp.clients.x", "protoweft.mcp.clients.x.endpoint=/mcp");
        assertInvalid("protoweft.mcp.clients.x.headers", "protoweft.mcp.clients.x.command=npx",
                "protoweft.mcp.clients.x.headers.X-Team=a");
        assertInvalid("protoweft.utcp.manuals.w", "protoweft.utcp.manuals.w.variables.KEY=v");
        assertInvalid("protoweft.a2a.agents.t.url", "protoweft.a2a.agents.t.streaming=false");
        assertInvalid("protoweft.utcp.manuals.dup", "protoweft.mcp.clients.dup.url=https://mcp.example.com",
                "protoweft.utcp.manuals.dup.url=https://api.example.com/utcp");
        assertInvalid("protoweft.mcp.clients.local", "protoweft.mcp.clients.local.url=https://mcp.example.com");
    }

    @Test
    void aProtocolWhoseModuleIsMissingIsReportedClearly() {
        runner.withClassLoader(new FilteredClassLoader(McpClientConnector.class))
                .withPropertyValues("protoweft.mcp.clients.x.url=https://mcp.example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.github.rahulbsw:protoweft-mcp"));
        runner.withClassLoader(new FilteredClassLoader(McpClientConnector.class)).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ToolRegistry.class));
    }

    private void assertInvalid(String property, String... properties) {
        runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining(property));
    }
}
