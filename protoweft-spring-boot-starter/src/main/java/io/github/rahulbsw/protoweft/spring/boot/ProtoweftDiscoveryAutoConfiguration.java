package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryTools;
import io.github.rahulbsw.protoweft.discovery.Embedder;
import io.github.rahulbsw.protoweft.discovery.FallbackPolicy;
import io.github.rahulbsw.protoweft.discovery.SourcePolicy;
import io.github.rahulbsw.protoweft.mcp.McpToolServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in tool discovery ({@code protoweft.discovery.enabled=true}): a {@link CapabilityCatalog}
 * over every source of the {@link ToolRegistry} and every declared connector, refreshed once the
 * connectors have started, and with {@code protoweft.discovery.expose-mcp=true} the
 * {@link DiscoveryTools} meta-tools served over MCP behind the {@link InboundAuthenticator}.
 *
 * <p>{@code protoweft-discovery} (and, to serve MCP, {@code protoweft-mcp}) is an optional
 * dependency: enabling discovery without it fails startup naming the artifact to add.
 */
@AutoConfiguration(after = {ProtoweftAutoConfiguration.class, ProtoweftServerAutoConfiguration.class})
@ConditionalOnProperty(prefix = "protoweft.discovery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ProtoweftDiscoveryProperties.class)
public class ProtoweftDiscoveryAutoConfiguration {

    static final String CATALOG_CLASS = "io.github.rahulbsw.protoweft.discovery.CapabilityCatalog";
    static final String MCP_SERVER_CLASS = "io.github.rahulbsw.protoweft.mcp.McpToolServer";

    public ProtoweftDiscoveryAutoConfiguration(ProtoweftDiscoveryProperties properties, ResourceLoader resourceLoader) {
        PropertiesValidator.validateDiscovery(properties, resourceLoader.getClassLoader());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = CATALOG_CLASS)
    static class Catalog {

        /** Registry sources as they are now (local tools, application-registered) plus every declared connector. */
        @Bean
        @ConditionalOnMissingBean
        CapabilityCatalog protoweftCapabilityCatalog(ProtoweftDiscoveryProperties properties, ToolRegistry registry,
                                                      ProtoweftConnectors connectors, ObjectProvider<Embedder> embedder,
                                                      ObjectProvider<ObservationHandler> observations) {
            Map<String, ToolSource> sources = new LinkedHashMap<>(registry.sources());
            Map<String, DeclaredConnectorSource> declared = new LinkedHashMap<>();
            for (String name : connectors.status().keySet()) {
                ToolConnector connector = connectors.connector(name).orElseThrow();
                DeclaredConnectorSource source = new DeclaredConnectorSource(name, connector, connectors);
                declared.put(name, source);
                sources.putIfAbsent(name, source);
            }
            properties.getSources().keySet().stream().filter(name -> !sources.containsKey(name)).findFirst()
                    .ifPresent(name -> {
                        throw new InvalidConfigurationPropertyValueException("protoweft.discovery.sources." + name,
                                name, "no such source; known sources: " + sources.keySet());
                    });
            CapabilityCatalog.Builder catalog = CapabilityCatalog.builder()
                    .fallback(FallbackPolicy.of(FallbackPolicy.Mode.valueOf(properties.getFallback().name())))
                    .observations(ProtoweftAutoConfiguration.observations(observations))
                    .embedder(embedder.getIfAvailable());
            sources.forEach((name, source) -> {
                SourcePolicy.Builder policy = SourcePolicy.builder();
                ProtoweftDiscoveryProperties.Source configured = properties.getSources().get(name);
                if (configured != null) {
                    policy.requiredScopes(configured.getRequiredScopes().toArray(String[]::new));
                }
                DeclaredConnectorSource connector = declared.get(name);
                if (connector == source) {
                    policy.availability(connector::up);
                }
                catalog.source(name, source, policy.build());
            });
            return catalog.build();
        }

        @Bean
        CatalogRefresher protoweftCatalogRefresher(CapabilityCatalog catalog, ProtoweftConnectors connectors) {
            return new CatalogRefresher(catalog, connectors);
        }
    }

    /** The meta-tools over MCP; their tool list is fixed, so the server needn't wait for the connectors. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(value = ServletRegistrationBean.class, name = {CATALOG_CLASS, MCP_SERVER_CLASS})
    @ConditionalOnProperty(prefix = "protoweft.discovery", name = "expose-mcp", havingValue = "true")
    @EnableConfigurationProperties(ProtoweftServerProperties.class)
    static class DiscoveryMcp {

        /** Server info, allowed origins and request timeout follow {@code protoweft.server.mcp}. */
        @Bean
        ProtoweftEndpointRegistrar protoweftDiscoveryMcpEndpoints(CapabilityCatalog catalog,
                                                                    ProtoweftDiscoveryProperties properties,
                                                                    ProtoweftServerProperties server,
                                                                    ObjectProvider<InboundAuthenticator> authenticator,
                                                                    ObjectProvider<ObservationHandler> observations) {
            PropertiesValidator.validateDiscoveryPath(properties, server);
            ProtoweftServerProperties.McpServer mcp = server.getMcp();
            McpToolServer.Http http = McpToolServer.http(DiscoveryTools.of(catalog))
                    .serverInfo(mcp.getServerName(), mcp.getServerVersion())
                    .observations(ProtoweftAutoConfiguration.observations(observations))
                    .endpointPath(properties.getMcpPath())
                    .allowedOrigins(mcp.getAllowedOrigins().toArray(String[]::new));
            if (mcp.getRequestTimeout() != null) {
                http.requestTimeout(mcp.getRequestTimeout());
            }
            // The endpoint's servlet closes the MCP server when the container destroys it.
            return ProtoweftServerAutoConfiguration.registrar(List.copyOf(http.endpoints()), server, authenticator);
        }
    }
}
