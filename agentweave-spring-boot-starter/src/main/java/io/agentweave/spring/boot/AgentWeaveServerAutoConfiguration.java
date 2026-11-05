package io.agentweave.spring.boot;

import io.agentweave.a2a.A2aAgentServer;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.observe.ObservationHandler;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolRegistry;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.ServletEndpoint;
import io.agentweave.spring.boot.AgentWeaveServerProperties.Exposure;
import io.agentweave.utcp.UtcpManualProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

/**
 * Opt-in AgentWeave server endpoints in a servlet web application: MCP
 * ({@code agentweave.server.mcp.enabled}), UTCP ({@code agentweave.server.utcp.enabled}) and A2A
 * ({@code agentweave.server.a2a.enabled}). Each is mounted by an {@link AgentWeaveEndpointRegistrar}
 * behind the {@link InboundAuthenticator} bean, or {@link InboundAuthenticator#ALLOW_ALL} without one.
 */
@AutoConfiguration(after = AgentWeaveAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ServletRegistrationBean.class, jakarta.servlet.Servlet.class})
@EnableConfigurationProperties(AgentWeaveServerProperties.class)
public class AgentWeaveServerAutoConfiguration {

    private static final Log log = LogFactory.getLog(AgentWeaveServerAutoConfiguration.class);

    public AgentWeaveServerAutoConfiguration(AgentWeaveServerProperties properties, ResourceLoader resourceLoader) {
        PropertiesValidator.validateServers(properties, resourceLoader.getClassLoader());
    }

    static AgentWeaveEndpointRegistrar registrar(List<ServletEndpoint> endpoints, AgentWeaveServerProperties properties,
                                                 ObjectProvider<InboundAuthenticator> authenticator) {
        return new AgentWeaveEndpointRegistrar(endpoints, authenticator.getIfAvailable(() -> InboundAuthenticator.ALLOW_ALL),
                properties.getAuthFilterOrder());
    }

    static ToolSource tools(Exposure exposure, String property, ObjectProvider<LocalTools> local,
                            ObjectProvider<ToolRegistry> registry) {
        if (exposure == Exposure.REGISTRY) {
            return registry.getObject();
        }
        LocalTools tools = local.getIfAvailable();
        if (tools == null) {
            throw new InvalidConfigurationPropertyValueException(property, "local",
                    "no local tools: enable agentweave.local-tools or define a LocalTools bean");
        }
        return tools;
    }

    /** Without an InboundAuthenticator bean, callers authenticated by Spring Security reach the tools by name. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SpringSecurityCaller {
        @Bean
        @ConditionalOnMissingBean
        InboundAuthenticator agentWeaveSpringSecurityAuthenticator() {
            return new SpringSecurityInboundAuthenticator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.agentweave.mcp.McpToolServer")
    @ConditionalOnProperty(prefix = "agentweave.server.mcp", name = "enabled", havingValue = "true")
    static class Mcp {

        @Bean
        @ConditionalOnMissingBean
        McpToolServer.Http agentWeaveMcpServer(AgentWeaveServerProperties properties, ObjectProvider<LocalTools> local,
                                               ObjectProvider<ToolRegistry> registry,
                                               ObjectProvider<ObservationHandler> observations) {
            AgentWeaveServerProperties.McpServer mcp = properties.getMcp();
            McpToolServer.Http server = McpToolServer.http(tools(mcp.getExpose(), "agentweave.server.mcp.expose", local, registry))
                    .serverInfo(mcp.getServerName(), mcp.getServerVersion())
                    .observations(AgentWeaveAutoConfiguration.observations(observations))
                    .endpointPath(mcp.getPath())
                    .allowedOrigins(mcp.getAllowedOrigins().toArray(String[]::new));
            if (mcp.getRequestTimeout() != null) {
                server.requestTimeout(mcp.getRequestTimeout());
            }
            return server;
        }

        /** Over the registry, the server (and its tool snapshot) is built once the connectors have started. */
        @Bean
        AgentWeaveEndpointRegistrar agentWeaveMcpEndpoints(McpToolServer.Http server, AgentWeaveServerProperties properties,
                                                           ObjectProvider<InboundAuthenticator> authenticator,
                                                           AgentWeaveConnectors connectors) {
            if (properties.getMcp().getExpose() != Exposure.REGISTRY) {
                return registrar(server.endpoints(), properties, authenticator);
            }
            DeferredServlet servlet = new DeferredServlet(() -> server.endpoints().get(0).servlet(), connectors::isRunning);
            return registrar(List.of(new ServletEndpoint("mcp", ProtocolId.MCP, List.of(properties.getMcp().getPath()),
                    servlet, true)), properties, authenticator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.agentweave.utcp.UtcpManualProvider")
    @ConditionalOnProperty(prefix = "agentweave.server.utcp", name = "enabled", havingValue = "true")
    static class Utcp {

        @Bean
        @ConditionalOnMissingBean
        UtcpManualProvider agentWeaveUtcpProvider(AgentWeaveServerProperties properties, ObjectProvider<LocalTools> local,
                                                  ObjectProvider<ToolRegistry> registry) {
            AgentWeaveServerProperties.UtcpServer utcp = properties.getUtcp();
            UtcpManualProvider.Builder b = UtcpManualProvider
                    .builder(tools(utcp.getExpose(), "agentweave.server.utcp.expose", local, registry))
                    .manualVersion(utcp.getManualVersion());
            if (utcp.getBaseUrl() != null) {
                b.baseUrl(utcp.getBaseUrl());
            }
            if (utcp.getApiKeyHeader() != null) {
                b.apiKeyAuth(utcp.getApiKeyHeader(), utcp.getApiKeyVariable());
            }
            return b.build();
        }

        @Bean
        AgentWeaveEndpointRegistrar agentWeaveUtcpEndpoints(UtcpManualProvider provider, AgentWeaveServerProperties properties,
                                                            ObjectProvider<InboundAuthenticator> authenticator) {
            return registrar(provider.endpoints(), properties, authenticator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.agentweave.a2a.A2aAgentServer")
    @ConditionalOnProperty(prefix = "agentweave.server.a2a", name = "enabled", havingValue = "true")
    static class A2a {

        @Bean
        @ConditionalOnMissingBean
        A2aAgentServer agentWeaveA2aServer(AgentWeaveServerProperties properties, ObjectProvider<AgentHandler> handler,
                                           ObjectProvider<AgentDescriptor> descriptor,
                                           ObjectProvider<ObservationHandler> observations, Environment environment) {
            AgentWeaveServerProperties.A2aServer a2a = properties.getA2a();
            AgentHandler agent = handler.getIfAvailable();
            if (agent == null) {
                throw new InvalidConfigurationPropertyValueException("agentweave.server.a2a.enabled", true,
                        "serving A2A needs an AgentHandler bean");
            }
            A2aAgentServer.Builder b = A2aAgentServer
                    .builder(agent, descriptor.getIfAvailable(() -> A2aDescriptors.fromProperties(a2a, environment)))
                    .path(a2a.getPath())
                    .streaming(a2a.isStreaming())
                    .pushNotifications(a2a.isPushNotifications())
                    .observations(AgentWeaveAutoConfiguration.observations(observations));
            if (a2a.getAgentTimeout() != null) {
                b.agentTimeout(a2a.getAgentTimeout());
            }
            if (a2a.getPublicUrl() != null) {
                b.publicUrl(a2a.getPublicUrl());
            } else {
                log.warn("agentweave.server.a2a.public-url is not set; the agent card advertises http://localhost:<port>, "
                        + "which only local clients can reach");
                b.publicUrl(() -> A2aDescriptors.localUrl(environment));
            }
            return b.build();
        }

        @Bean
        AgentWeaveEndpointRegistrar agentWeaveA2aEndpoints(A2aAgentServer server, AgentWeaveServerProperties properties,
                                                           ObjectProvider<InboundAuthenticator> authenticator) {
            return registrar(server.endpoints(), properties, authenticator);
        }
    }
}
