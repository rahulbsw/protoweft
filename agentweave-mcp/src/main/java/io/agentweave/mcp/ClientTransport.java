package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.auth.Credential;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** How {@link McpClientConnector} reaches its server: one implementation per MCP transport. */
sealed interface ClientTransport permits ClientTransport.Stdio, ClientTransport.Http {

    /** The {@link McpFeatures} id of this transport. */
    String featureId();

    /** The {@code target} handed to the credential provider. */
    String credentialTarget();

    /** The {@code target} reported to observations; never carries secrets. */
    String observationTarget();

    /** Wire revisions, read from the SDK transport itself. */
    List<String> protocolVersions();

    /**
     * Creates a fresh SDK transport presenting {@code credentials}.
     *
     * @throws AgentWeaveException with {@code AUTHENTICATION} for a credential this transport can't present
     */
    McpClientTransport open(List<Credential> credentials, Duration timeout);

    /** Launches a subprocess; credentials become its environment (see SPEC.md, "Credentials"). */
    record Stdio(String command, List<String> args, Map<String, String> environment) implements ClientTransport {

        public Stdio {
            args = List.copyOf(args);
            environment = Map.copyOf(environment);
        }

        @Override
        public String featureId() {
            return McpFeatures.TRANSPORT_STDIO;
        }

        @Override
        public String credentialTarget() {
            return command;
        }

        @Override
        public String observationTarget() {
            return command; // arguments may carry secrets
        }

        @Override
        public List<String> protocolVersions() {
            return new StdioClientTransport(ServerParameters.builder(command).build(), McpJsonDefaults.getMapper())
                    .protocolVersions();
        }

        @Override
        public McpClientTransport open(List<Credential> credentials, Duration timeout) {
            Map<String, String> env = new HashMap<>(environment);
            for (Credential credential : credentials) {
                if (credential instanceof Credential.NamedSecret secret) {
                    env.put(secret.name(), secret.value());
                } else {
                    throw new AgentWeaveException(ErrorCategory.AUTHENTICATION, ProtocolId.MCP,
                            "MCP stdio transport only accepts NamedSecret credentials (passed as environment "
                                    + "variables); got " + credential.getClass().getSimpleName());
                }
            }
            return new StdioClientTransport(ServerParameters.builder(command).args(args).env(env).build(),
                    McpJsonDefaults.getMapper());
        }
    }

    /** Streamable HTTP; credentials become request headers. */
    record Http(String baseUrl, String endpoint, Map<String, String> headers) implements ClientTransport {

        public Http {
            headers = Map.copyOf(headers);
        }

        @Override
        public String featureId() {
            return McpFeatures.TRANSPORT_STREAMABLE_HTTP;
        }

        @Override
        public String credentialTarget() {
            return baseUrl;
        }

        @Override
        public String observationTarget() {
            return withoutSecrets(baseUrl);
        }

        @Override
        public List<String> protocolVersions() {
            return Revisions.HTTP;
        }

        /** Read once: probing builds an {@code HttpClient}, and the revisions don't depend on configuration. */
        private static final class Revisions {
            static final List<String> HTTP = HttpClientStreamableHttpTransport.builder("http://localhost")
                    .build().protocolVersions();
        }

        @Override
        public McpClientTransport open(List<Credential> credentials, Duration timeout) {
            Map<String, String> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); // HTTP header names
            all.putAll(headers);
            for (Credential credential : credentials) {
                if (credential instanceof Credential.BearerToken bearer) {
                    all.put("Authorization", "Bearer " + bearer.token());
                } else if (credential instanceof Credential.NamedSecret secret) {
                    all.put(secret.name(), secret.value());
                } else {
                    throw new AgentWeaveException(ErrorCategory.AUTHENTICATION, ProtocolId.MCP,
                            "unsupported credential for MCP Streamable HTTP: " + credential.getClass().getSimpleName());
                }
            }
            return builder(timeout, Collections.unmodifiableMap(all)).build();
        }

        private HttpClientStreamableHttpTransport.Builder builder(Duration connectTimeout, Map<String, String> send) {
            HttpClientStreamableHttpTransport.Builder b = HttpClientStreamableHttpTransport.builder(baseUrl)
                    .endpoint(endpoint)
                    .connectTimeout(connectTimeout);
            if (!send.isEmpty()) {
                b.addHttpRequestCustomizer((request, method, uri, body, context) -> send.forEach(request::setHeader));
            }
            return b;
        }

        /** Scheme, host, port and path only: user info, query and fragment may carry secrets. */
        static String withoutSecrets(String url) {
            URI u = URI.create(url);
            try {
                return new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null).toString();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("invalid URL: " + url, e);
            }
        }
    }
}
