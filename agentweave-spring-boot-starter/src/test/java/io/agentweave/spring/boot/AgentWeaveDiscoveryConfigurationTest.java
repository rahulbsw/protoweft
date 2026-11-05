package io.agentweave.spring.boot;

import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.discovery.CapabilityCard;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryResult;
import io.agentweave.discovery.Embedder;
import io.agentweave.discovery.FallbackPolicy;
import io.agentweave.server.InboundAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWeaveDiscoveryConfigurationTest {

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(AgentWeaveAutoConfiguration.class,
            AgentWeaveServerAutoConfiguration.class, AgentWeaveDiscoveryAutoConfiguration.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(CONFIGS).withBean(TestTools.class);

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(CONFIGS)
            .withClassLoader(new FilteredClassLoader("org.springframework.security"))
            .withBean(TestTools.class);

    @Test
    void discoveryIsOptIn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CapabilityCatalog.class);
        });
        web.withPropertyValues("agentweave.discovery.expose-mcp=true").run(context ->
                assertThat(context).doesNotHaveBean(AgentWeaveEndpointRegistrar.class));
    }

    @Test
    void catalogCoversTheRegistryWithConfiguredFallbackAndScopes() {
        runner.withPropertyValues("agentweave.discovery.enabled=true", "agentweave.discovery.fallback=none",
                        "agentweave.discovery.sources.local.required-scopes=tools:use")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CapabilityCatalog catalog = context.getBean(CapabilityCatalog.class);
                    assertThat(catalog.cards()).extracting(CapabilityCard::name)
                            .containsExactlyInAnyOrder("local.add", "local.whoami");
                    assertThat(catalog.card("local.add").orElseThrow().requiredScopes()).containsExactly("tools:use");
                    assertThat(catalog.fallbackPolicy().mode()).isEqualTo(FallbackPolicy.Mode.NONE);

                    assertThat(catalog.search(DiscoveryQuery.builder("add integers").build()).hits()).isEmpty();
                    CallerIdentity permitted = new CallerIdentity("u",
                            Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("tools:use")));
                    DiscoveryResult result = catalog.search(DiscoveryQuery.builder("add integers").caller(permitted).build());
                    assertThat(result.names()).first().isEqualTo("local.add");
                    assertThat(result.mode()).isEqualTo(DiscoveryResult.Mode.KEYWORD_ONLY);
                    assertThat(catalog.invoke("local.add", Map.of("a", 2, "b", 3), permitted).text()).isEqualTo("5");
                });
    }

    @Test
    void anEmbedderBeanEnablesHybridSearch() {
        runner.withPropertyValues("agentweave.discovery.enabled=true")
                .withBean(Embedder.class, LetterEmbedder::new)
                .run(context -> assertThat(context.getBean(CapabilityCatalog.class)
                        .search(DiscoveryQuery.builder("add").build()).mode()).isEqualTo(DiscoveryResult.Mode.HYBRID));
    }

    @Test
    void declaredConnectorsAreCatalogedOnceTheyHaveStarted() {
        runner.withPropertyValues(TestStdioServer.clientProperties("files"))
                .withPropertyValues("agentweave.discovery.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CapabilityCatalog catalog = context.getBean(CapabilityCatalog.class);
                    CapabilityCard echo = catalog.card("files.echo").orElseThrow();
                    assertThat(echo.protocol().value()).isEqualTo("mcp");
                    assertThat(echo.wireRevision()).isNotNull();
                    assertThat(catalog.unavailableReason("files")).isEmpty();
                    assertThat(catalog.search(DiscoveryQuery.builder("echo the message").build()).names())
                            .first().isEqualTo("files.echo");
                    assertThat(catalog.invoke("files.echo", Map.of("message", "hi"), CallerIdentity.ANONYMOUS).text())
                            .isEqualTo("hi");
                });
    }

    @Test
    void aFailedConnectorIsReportedUnavailable() {
        runner.withPropertyValues("agentweave.discovery.enabled=true", "agentweave.fail-fast=false",
                        "agentweave.mcp.clients.down.url=http://127.0.0.1:1",
                        "agentweave.mcp.clients.down.request-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CapabilityCatalog catalog = context.getBean(CapabilityCatalog.class);
                    assertThat(catalog.sourceNames()).containsExactlyInAnyOrder("local", "down");
                    assertThat(catalog.unavailableReason("down")).hasValueSatisfying(r -> assertThat(r).contains("FAILED"));
                    assertThat(catalog.unavailableReason("local")).isEmpty();
                });
    }

    @Test
    void applicationRegisteredSourcesAreIncluded() {
        runner.withPropertyValues("agentweave.discovery.enabled=true")
                .withBean(ToolRegistry.class, () -> new ToolRegistry().register("extra",
                        io.agentweave.core.tool.LocalTools.builder().tool("ping", "Health check",
                                Map.of("type", "object"), (a, c) -> io.agentweave.core.tool.ToolOutcome.text("pong"))
                                .build()))
                .run(context -> assertThat(context.getBean(CapabilityCatalog.class).card("extra.ping")).isPresent());
    }

    @Test
    void invalidDiscoveryConfigurationIsRejected() {
        runner.withPropertyValues("agentweave.discovery.enabled=true",
                        "agentweave.discovery.sources.nope.required-scopes=x")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                        .hasMessageContaining("agentweave.discovery.sources.nope"));
        web.withPropertyValues("agentweave.discovery.enabled=true", "agentweave.discovery.expose-mcp=true",
                        "agentweave.server.mcp.enabled=true", "agentweave.discovery.mcp-path=/mcp")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                        .hasMessageContaining("agentweave.discovery.mcp-path"));
        web.withPropertyValues("agentweave.discovery.enabled=true", "agentweave.discovery.expose-mcp=true",
                        "agentweave.discovery.mcp-path=relative")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentweave.discovery.mcp-path"));
    }

    @Test
    void aMissingDiscoveryModuleIsReportedClearly() {
        runner.withClassLoader(new FilteredClassLoader("io.agentweave.discovery"))
                .withPropertyValues("agentweave.discovery.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("io.agentweave:agentweave-discovery"));
        runner.withClassLoader(new FilteredClassLoader("io.agentweave.discovery")).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ToolRegistry.class));
    }

    @Test
    void metaToolsAreMountedOverMcpBehindTheAuthFilter() {
        web.withPropertyValues("agentweave.discovery.enabled=true", "agentweave.discovery.expose-mcp=true",
                        "agentweave.server.mcp.enabled=true")
                .withBean(InboundAuthenticator.class, () -> InboundAuthenticator.ALLOW_ALL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AgentWeaveEndpointRegistrar registrar = context.getBean("agentWeaveDiscoveryMcpEndpoints",
                            AgentWeaveEndpointRegistrar.class);
                    ServletRegistrationBean<?> servlet = registrar.servlets().get(0);
                    assertThat(servlet.getUrlMappings()).containsExactly("/mcp-discovery");
                    assertThat(servlet.isAsyncSupported()).isTrue();
                    assertThat(registrar.filters().get(0).getFilter()).isInstanceOf(InboundAuthFilter.class);
                    assertThat(registrar.filters().get(0).getUrlPatterns()).containsExactly("/mcp-discovery");
                    // The regular MCP server is unaffected.
                    assertThat(context.getBean("agentWeaveMcpEndpoints", AgentWeaveEndpointRegistrar.class)
                            .servlets().get(0).getUrlMappings()).containsExactly("/mcp");
                });
    }

    /** A toy embedder: letter counts. */
    static final class LetterEmbedder implements Embedder {
        @Override
        public String name() {
            return "letters";
        }

        @Override
        public int dimension() {
            return 26;
        }

        @Override
        public float[] embedDocument(String text) {
            float[] v = new float[26];
            for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
                if (c >= 'a' && c <= 'z') {
                    v[c - 'a']++;
                }
            }
            v[0] += 0.01f;
            return v;
        }
    }
}
