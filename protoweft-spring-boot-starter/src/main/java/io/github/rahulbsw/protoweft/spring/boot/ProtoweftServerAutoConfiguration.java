package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.a2a.A2aAgentServer;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSource;
import io.github.rahulbsw.protoweft.mcp.McpToolServer;
import io.github.rahulbsw.protoweft.rap.RapCallbackEndpoint;
import io.github.rahulbsw.protoweft.rap.RapCallbackPolicy;
import io.github.rahulbsw.protoweft.rap.RapStore;
import io.github.rahulbsw.protoweft.rap.RapToolServer;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import io.github.rahulbsw.protoweft.spring.boot.ProtoweftServerProperties.Exposure;
import io.github.rahulbsw.protoweft.utcp.UtcpManualProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/**
 * Opt-in Protoweft server endpoints in a servlet web application: MCP
 * ({@code protoweft.server.mcp.enabled}), UTCP ({@code protoweft.server.utcp.enabled}), A2A
 * ({@code protoweft.server.a2a.enabled}) and RAP ({@code protoweft.server.rap.enabled}). Each is
 * mounted by an {@link ProtoweftEndpointRegistrar} behind the {@link InboundAuthenticator} bean, or
 * {@link InboundAuthenticator#ALLOW_ALL} without one. When RAP connectors are declared, their
 * callback endpoint is mounted too, exempt from the authenticator (its URL tokens authenticate).
 */
@AutoConfiguration(after = ProtoweftAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ServletRegistrationBean.class, jakarta.servlet.Servlet.class})
@EnableConfigurationProperties(ProtoweftServerProperties.class)
public class ProtoweftServerAutoConfiguration {

    private static final Log log = LogFactory.getLog(ProtoweftServerAutoConfiguration.class);

    public ProtoweftServerAutoConfiguration(ProtoweftServerProperties properties, ResourceLoader resourceLoader) {
        PropertiesValidator.validateServers(properties, resourceLoader.getClassLoader());
    }

    static ProtoweftEndpointRegistrar registrar(List<ServletEndpoint> endpoints, ProtoweftServerProperties properties,
                                                 ObjectProvider<InboundAuthenticator> authenticator) {
        return new ProtoweftEndpointRegistrar(endpoints, authenticator.getIfAvailable(() -> InboundAuthenticator.ALLOW_ALL),
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
                    "no local tools: enable protoweft.local-tools or define a LocalTools bean");
        }
        return tools;
    }

    /** Without an InboundAuthenticator bean, callers authenticated by Spring Security reach the tools by name. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SpringSecurityCaller {
        @Bean
        @ConditionalOnMissingBean
        InboundAuthenticator protoweftSpringSecurityAuthenticator() {
            return new SpringSecurityInboundAuthenticator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.rahulbsw.protoweft.mcp.McpToolServer")
    @ConditionalOnProperty(prefix = "protoweft.server.mcp", name = "enabled", havingValue = "true")
    static class Mcp {

        @Bean
        @ConditionalOnMissingBean
        McpToolServer.Http protoweftMcpServer(ProtoweftServerProperties properties, ObjectProvider<LocalTools> local,
                                               ObjectProvider<ToolRegistry> registry,
                                               ObjectProvider<ObservationHandler> observations) {
            ProtoweftServerProperties.McpServer mcp = properties.getMcp();
            McpToolServer.Http server = McpToolServer.http(tools(mcp.getExpose(), "protoweft.server.mcp.expose", local, registry))
                    .serverInfo(mcp.getServerName(), mcp.getServerVersion())
                    .observations(ProtoweftAutoConfiguration.observations(observations))
                    .endpointPath(mcp.getPath())
                    .allowedOrigins(mcp.getAllowedOrigins().toArray(String[]::new));
            if (mcp.getRequestTimeout() != null) {
                server.requestTimeout(mcp.getRequestTimeout());
            }
            return server;
        }

        /** Over the registry, the server (and its tool snapshot) is built once the connectors have started. */
        @Bean
        ProtoweftEndpointRegistrar protoweftMcpEndpoints(McpToolServer.Http server, ProtoweftServerProperties properties,
                                                           ObjectProvider<InboundAuthenticator> authenticator,
                                                           ProtoweftConnectors connectors) {
            if (properties.getMcp().getExpose() != Exposure.REGISTRY) {
                return registrar(server.endpoints(), properties, authenticator);
            }
            DeferredServlet servlet = new DeferredServlet(() -> server.endpoints().get(0).servlet(), connectors::isRunning);
            return registrar(List.of(new ServletEndpoint("mcp", ProtocolId.MCP, List.of(properties.getMcp().getPath()),
                    servlet, true)), properties, authenticator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.rahulbsw.protoweft.utcp.UtcpManualProvider")
    @ConditionalOnProperty(prefix = "protoweft.server.utcp", name = "enabled", havingValue = "true")
    static class Utcp {

        @Bean
        @ConditionalOnMissingBean
        UtcpManualProvider protoweftUtcpProvider(ProtoweftServerProperties properties, ObjectProvider<LocalTools> local,
                                                  ObjectProvider<ToolRegistry> registry) {
            ProtoweftServerProperties.UtcpServer utcp = properties.getUtcp();
            UtcpManualProvider.Builder b = UtcpManualProvider
                    .builder(tools(utcp.getExpose(), "protoweft.server.utcp.expose", local, registry))
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
        ProtoweftEndpointRegistrar protoweftUtcpEndpoints(UtcpManualProvider provider, ProtoweftServerProperties properties,
                                                            ObjectProvider<InboundAuthenticator> authenticator) {
            return registrar(provider.endpoints(), properties, authenticator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.rahulbsw.protoweft.a2a.A2aAgentServer")
    @ConditionalOnProperty(prefix = "protoweft.server.a2a", name = "enabled", havingValue = "true")
    static class A2a {

        @Bean
        @ConditionalOnMissingBean
        A2aAgentServer protoweftA2aServer(ProtoweftServerProperties properties, ObjectProvider<AgentHandler> handler,
                                           ObjectProvider<AgentDescriptor> descriptor,
                                           ObjectProvider<ObservationHandler> observations, Environment environment) {
            ProtoweftServerProperties.A2aServer a2a = properties.getA2a();
            AgentHandler agent = handler.getIfAvailable();
            if (agent == null) {
                throw new InvalidConfigurationPropertyValueException("protoweft.server.a2a.enabled", true,
                        "serving A2A needs an AgentHandler bean");
            }
            A2aAgentServer.Builder b = A2aAgentServer
                    .builder(agent, descriptor.getIfAvailable(() -> A2aDescriptors.fromProperties(a2a, environment)))
                    .path(a2a.getPath())
                    .streaming(a2a.isStreaming())
                    .pushNotifications(a2a.isPushNotifications())
                    .observations(ProtoweftAutoConfiguration.observations(observations));
            if (a2a.getAgentTimeout() != null) {
                b.agentTimeout(a2a.getAgentTimeout());
            }
            if (a2a.getPublicUrl() != null) {
                b.publicUrl(a2a.getPublicUrl());
            } else {
                log.warn("protoweft.server.a2a.public-url is not set; the agent card advertises http://localhost:<port>, "
                        + "which only local clients can reach");
                b.publicUrl(() -> A2aDescriptors.localUrl(environment));
            }
            return b.build();
        }

        @Bean
        ProtoweftEndpointRegistrar protoweftA2aEndpoints(A2aAgentServer server, ProtoweftServerProperties properties,
                                                           ObjectProvider<InboundAuthenticator> authenticator) {
            return registrar(server.endpoints(), properties, authenticator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = PropertiesValidator.RAP_SERVER_CLASS)
    @ConditionalOnProperty(prefix = "protoweft.server.rap", name = "enabled", havingValue = "true")
    static class Rap {

        @Bean
        @ConditionalOnMissingBean
        RapToolServer protoweftRapServer(ProtoweftServerProperties properties, ObjectProvider<LocalTools> local,
                                         ObjectProvider<ToolRegistry> registry, ObjectProvider<RapStore> store) {
            ProtoweftServerProperties.RapServer rap = properties.getRap();
            RapToolServer.Builder b = RapToolServer
                    .builder(tools(rap.getExpose(), "protoweft.server.rap.expose", local, registry))
                    .name(rap.getName())
                    .basePath(rap.getBasePath());
            if (rap.getDescription() != null) {
                b.description(rap.getDescription());
            }
            if (rap.getBaseUrl() != null) {
                b.baseUrl(rap.getBaseUrl());
            }
            if (!rap.getAllowedCallbackHosts().isEmpty()) {
                b.callbackPolicy(RapCallbackPolicy.DEFAULT.allowingHosts(
                        rap.getAllowedCallbackHosts().toArray(String[]::new)));
            }
            if (rap.getChoiceTimeout() != null) {
                b.choiceTimeout(rap.getChoiceTimeout());
            }
            RapStore rapStore = store.getIfAvailable();
            if (rapStore != null) {
                b.store(rapStore);
            }
            return b.build();
        }

        @Bean
        ProtoweftEndpointRegistrar protoweftRapEndpoints(RapToolServer server, ProtoweftServerProperties properties,
                                                           ObjectProvider<InboundAuthenticator> authenticator) {
            return registrar(server.endpoints(), properties, authenticator);
        }
    }

    /** The callback endpoint of the RAP connectors declared in properties, unless they use the built-in listener. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = PropertiesValidator.RAP_CLASS)
    @Conditional(RapCallbacks.ConnectorsUseServletCallbacks.class)
    static class RapCallbacks {

        @Bean
        @ConditionalOnMissingBean
        RapCallbackEndpoint protoweftRapCallbackEndpoint(ProtoweftProperties properties, Environment environment) {
            String publicBaseUrl = properties.getRap().getCallback().getPublicBaseUrl();
            if (publicBaseUrl != null) {
                return RapCallbackEndpoint.create(publicBaseUrl);
            }
            log.warn("protoweft.rap.callback.public-base-url is not set; RAP callbacks are addressed to "
                    + "http://localhost:<port>, which only local tool servers can reach");
            return RapCallbackEndpoint.create(() -> A2aDescriptors.localUrl(environment));
        }

        /** Callback requests carry no credentials: the unguessable token in their URL authenticates them. */
        @Bean
        ProtoweftEndpointRegistrar protoweftRapCallbackEndpoints(RapCallbackEndpoint endpoint,
                                                                   ProtoweftServerProperties properties,
                                                                   ObjectProvider<InboundAuthenticator> authenticator) {
            return new ProtoweftEndpointRegistrar(endpoint.endpoints(), RapCallbackEndpoint.exemptCallbacks(
                    authenticator.getIfAvailable(() -> InboundAuthenticator.ALLOW_ALL)), properties.getAuthFilterOrder());
        }

        /** {@code protoweft.rap.servers} has entries and {@code protoweft.rap.callback.listener.enabled} is not set. */
        static final class ConnectorsUseServletCallbacks extends SpringBootCondition {
            @Override
            public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
                Binder binder = Binder.get(context.getEnvironment());
                boolean declared = binder.bind("protoweft.rap.servers",
                                Bindable.mapOf(String.class, ProtoweftProperties.RapRemote.class))
                        .map(servers -> !servers.isEmpty()).orElse(false);
                boolean listener = binder.bind("protoweft.rap.callback.listener.enabled", Boolean.class).orElse(false);
                if (!declared) {
                    return ConditionOutcome.noMatch("no protoweft.rap.servers declared");
                }
                return listener ? ConditionOutcome.noMatch("protoweft.rap.callback.listener.enabled is set")
                        : ConditionOutcome.match("RAP connectors receive callbacks on the servlet container");
            }
        }
    }
}
