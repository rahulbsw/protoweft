package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core Protoweft beans: the {@link LocalTools} discovered from {@code @AgentTool} beans, the
 * {@link ToolRegistry}, and the client connectors declared in properties ({@link ProtoweftConnectors}).
 * Each protocol's connectors are available only when its module is on the classpath.
 */
@AutoConfiguration
@EnableConfigurationProperties(ProtoweftProperties.class)
public class ProtoweftAutoConfiguration {

    /** Every bean's {@code @AgentTool} methods plus any {@link LocalTools.LocalTool} beans. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "protoweft.local-tools", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LocalTools protoweftLocalTools(ListableBeanFactory beanFactory, ObjectProvider<LocalTools.LocalTool> tools) {
        return LocalToolsDiscovery.discover(beanFactory, tools.orderedStream().toList());
    }

    /** Local tools under {@code protoweft.local-tools.name}; connectors are added as they start. */
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public ToolRegistry protoweftToolRegistry(ProtoweftProperties properties, ObjectProvider<LocalTools> localTools) {
        ToolRegistry registry = new ToolRegistry();
        LocalTools local = localTools.getIfAvailable();
        if (local != null && properties.getLocalTools().isEnabled()) {
            registry.register(properties.getLocalTools().getName(), local);
        }
        return registry;
    }

    @Bean
    public ProtoweftConnectors protoweftConnectors(ProtoweftProperties properties, ToolRegistry registry,
                                                     ObjectProvider<ConnectorFactory> factories,
                                                     ObjectProvider<CredentialProvider> credentials,
                                                     ObjectProvider<ObservationHandler> observations,
                                                     ResourceLoader resourceLoader) {
        PropertiesValidator.validateClients(properties, resourceLoader.getClassLoader());
        CredentialProvider creds = credentials.getIfAvailable(() -> CredentialProvider.NONE);
        ObservationHandler handler = observations(observations);
        Map<String, ToolConnector> connectors = new LinkedHashMap<>();
        factories.orderedStream().forEach(f -> connectors.putAll(f.create(properties, creds, handler)));
        return new ProtoweftConnectors(connectors, registry, properties.isFailFast());
    }

    /** All {@link ObservationHandler} beans (user-defined and the Micrometer bridge) as one. */
    static ObservationHandler observations(ObjectProvider<ObservationHandler> handlers) {
        return CompositeObservationHandler.of(handlers.orderedStream().toList());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = PropertiesValidator.MCP_CLASS)
    static class McpClients {
        @Bean
        ConnectorFactory protoweftMcpConnectorFactory() {
            return new McpConnectorFactory();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = PropertiesValidator.UTCP_CLASS)
    static class UtcpClients {
        @Bean
        ConnectorFactory protoweftUtcpConnectorFactory() {
            return new UtcpConnectorFactory();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = PropertiesValidator.A2A_CLASS)
    static class A2aClients {
        @Bean
        ConnectorFactory protoweftA2aConnectorFactory() {
            return new A2aConnectorFactory();
        }
    }
}
