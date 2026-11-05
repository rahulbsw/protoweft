package io.agentweave.spring.boot;

import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.mcp.McpClientConnector;
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

class AgentWeaveAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWeaveAutoConfiguration.class,
                    AgentWeaveServerAutoConfiguration.class, AgentWeaveMicrometerAutoConfiguration.class,
                    AgentWeaveActuatorAutoConfiguration.class));

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
                    assertThat(context).doesNotHaveBean(AgentWeaveEndpointRegistrar.class);
                });
    }

    @Test
    void localToolsNameIsConfigurableAndDiscoveryCanBeDisabled() {
        runner.withBean(TestTools.class).withPropertyValues("agentweave.local-tools.name=mine").run(context ->
                assertThat(context.getBean(ToolRegistry.class).sources()).containsOnlyKeys("mine"));
        runner.withBean(TestTools.class).withPropertyValues("agentweave.local-tools.enabled=false").run(context -> {
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
        AgentWeaveConnectors[] connectors = new AgentWeaveConnectors[1];
        runner.withPropertyValues(TestStdioServer.clientProperties("files"))
                .withBean(TestObservationRegistry.class, () -> observations)
                .withBean(ObservationHandler.class, () -> own)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolRegistry registry = context.getBean(ToolRegistry.class);
                    assertThat(registry.listTools()).extracting(ToolSpec::name).containsExactly("files.echo");
                    assertThat(registry.invoke(ToolInvocation.of("files.echo", Map.of("message", "hi"))).text())
                            .isEqualTo("hi");
                    connectors[0] = context.getBean(AgentWeaveConnectors.class);
                    assertThat(connectors[0].connector("files", McpClientConnector.class).compliance().negotiated())
                            .isPresent();
                    assertThat(context.getBean(AgentWeaveHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
                    Info.Builder info = new Info.Builder();
                    context.getBean(AgentWeaveInfoContributor.class).contribute(info);
                    assertThat(info.build().getDetails()).extractingByKey("agentweave").asString()
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
        assertThat(connectors[0].status().get("files").state()).isEqualTo(AgentWeaveConnectors.State.CLOSED);
    }

    @Test
    void aConnectorThatFailsToStartFailsStartupByDefault() {
        runner.withPropertyValues("agentweave.mcp.clients.down.url=http://127.0.0.1:1",
                        "agentweave.mcp.clients.down.request-timeout=2s")
                .run(context -> assertThat(context).hasFailed().getFailure().cause()
                        .hasMessageContaining("connector 'down' failed to start")
                        .hasMessageContaining("agentweave.fail-fast=false"));
    }

    @Test
    void withoutFailFastAFailedConnectorIsLeftOutAndReportedDown() {
        runner.withBean(TestTools.class)
                .withPropertyValues("agentweave.fail-fast=false", "agentweave.mcp.clients.down.url=http://127.0.0.1:1",
                        "agentweave.mcp.clients.down.request-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ToolRegistry.class).sources()).containsOnlyKeys("local");
                    AgentWeaveConnectors.Status status = context.getBean(AgentWeaveConnectors.class).status().get("down");
                    assertThat(status.state()).isEqualTo(AgentWeaveConnectors.State.FAILED);
                    assertThat(status.failure()).isNotNull();
                    assertThat(context.getBean(AgentWeaveHealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Test
    void invalidClientPropertiesAreRejectedAtStartup() {
        assertInvalid("agentweave.mcp.clients.x", "agentweave.mcp.clients.x.command=npx",
                "agentweave.mcp.clients.x.url=https://mcp.example.com");
        assertInvalid("agentweave.mcp.clients.x", "agentweave.mcp.clients.x.endpoint=/mcp");
        assertInvalid("agentweave.mcp.clients.x.headers", "agentweave.mcp.clients.x.command=npx",
                "agentweave.mcp.clients.x.headers.X-Team=a");
        assertInvalid("agentweave.utcp.manuals.w", "agentweave.utcp.manuals.w.variables.KEY=v");
        assertInvalid("agentweave.a2a.agents.t.url", "agentweave.a2a.agents.t.streaming=false");
        assertInvalid("agentweave.utcp.manuals.dup", "agentweave.mcp.clients.dup.url=https://mcp.example.com",
                "agentweave.utcp.manuals.dup.url=https://api.example.com/utcp");
        assertInvalid("agentweave.mcp.clients.local", "agentweave.mcp.clients.local.url=https://mcp.example.com");
    }

    @Test
    void aProtocolWhoseModuleIsMissingIsReportedClearly() {
        runner.withClassLoader(new FilteredClassLoader(McpClientConnector.class))
                .withPropertyValues("agentweave.mcp.clients.x.url=https://mcp.example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.agentweave:agentweave-mcp"));
        runner.withClassLoader(new FilteredClassLoader(McpClientConnector.class)).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ToolRegistry.class));
    }

    private void assertInvalid(String property, String... properties) {
        runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining(property));
    }
}
