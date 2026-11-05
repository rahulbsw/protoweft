package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoveryQuery;
import io.agentweave.discovery.DiscoveryTools;
import io.agentweave.discovery.SourcePolicy;
import io.agentweave.server.jetty.AgentWeaveServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The discovery meta-tools ({@link DiscoveryTools}) served by our MCP server on embedded Jetty and
 * used through our MCP client: search, describe and call end to end, with the caller established by
 * an {@link InboundAuthenticator} reaching the catalog's permission filter.
 */
class McpDiscoveryToolsTest {

    static final String ADMIN_TOKEN = "admin-token";
    static final String USER_TOKEN = "user-token";

    /** Admin holds {@code secrets:read}; user holds no scopes; anything else is 401. */
    static final InboundAuthenticator AUTH = request -> {
        if (request.bearerToken().filter(ADMIN_TOKEN::equals).isPresent()) {
            return AuthDecision.allow(new CallerIdentity("admin",
                    Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("secrets:read"))));
        }
        if (request.bearerToken().filter(USER_TOKEN::equals).isPresent()) {
            return AuthDecision.allow(CallerIdentity.of("user"));
        }
        return AuthDecision.unauthenticated("missing or invalid credentials");
    };

    private static AgentWeaveServer server;
    private McpClientConnector connector;

    @BeforeAll
    static void startServer() {
        LocalTools vault = LocalTools.builder()
                .tool("read_secret", "Reads a secret value from the vault by key",
                        Map.of("type", "object", "properties", Map.of("key", Map.of("type", "string")),
                                "required", List.of("key")),
                        (args, caller) -> ToolOutcome.text("secret " + args.get("key") + " for " + caller.name()))
                .build();
        CapabilityCatalog catalog = CapabilityCatalog.builder()
                .source("local", TestTools.create())
                .source("vault", vault, SourcePolicy.builder().requiredScopes("secrets:read").build())
                .build();
        McpToolServer.Http mcp = McpToolServer.http(DiscoveryTools.of(catalog))
                .serverInfo("agentweave-discovery-test", "1.0.0");
        server = AgentWeaveServer.builder().port(0).authenticator(AUTH).endpoints(mcp).start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void closeConnector() {
        if (connector != null) {
            connector.close();
        }
    }

    @Test
    void listsExactlyTheThreeMetaTools() {
        connector = client(USER_TOKEN);

        assertThat(connector.listTools()).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder(DiscoveryTools.SEARCH, DiscoveryTools.DESCRIBE, DiscoveryTools.CALL);
    }

    @Test
    void searchDescribeAndCallEndToEnd() {
        connector = client(USER_TOKEN);

        ToolOutcome found = connector.invoke(ToolInvocation.of(DiscoveryTools.SEARCH,
                Map.of("query", "add two integers")));
        assertThat(found.error()).isFalse();
        assertThat(found.text()).contains("local.add").contains("confidence:");
        assertThat(capabilityNames(found)).first().isEqualTo("local.add");

        ToolOutcome described = connector.invoke(ToolInvocation.of(DiscoveryTools.DESCRIBE,
                Map.of("names", List.of("local.add"))));
        assertThat(described.text()).contains("local.add").contains("input_schema");
        Map<?, ?> structured = (Map<?, ?>) described.structuredContent().orElseThrow();
        Map<?, ?> add = (Map<?, ?>) ((List<?>) structured.get("capabilities")).get(0);
        assertThat(add.get("name")).isEqualTo("local.add");
        Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) add.get("input_schema")).get("properties");
        assertThat(properties.keySet()).map(String::valueOf).containsExactlyInAnyOrder("a", "b");

        ToolOutcome sum = connector.invoke(ToolInvocation.of(DiscoveryTools.CALL,
                Map.of("name", "local.add", "arguments", Map.of("a", 2, "b", 3))));
        assertThat(sum.error()).isFalse();
        assertThat(sum.text()).isEqualTo("5");
    }

    @Test
    void callerFromTheAuthenticatorReachesTheCapability() {
        connector = client(ADMIN_TOKEN);

        ToolOutcome who = connector.invoke(ToolInvocation.of(DiscoveryTools.CALL,
                Map.of("name", "local.whoami", "arguments", Map.of())));

        assertThat(who.text()).isEqualTo("admin");
    }

    @Test
    void scopedCapabilityIsVisibleOnlyWithTheRightBearerToken() {
        Map<String, Object> search = Map.of("query", "read a secret from the vault");
        Map<String, Object> call = Map.of("name", "vault.read_secret", "arguments", Map.of("key", "db"));

        connector = client(USER_TOKEN);
        ToolOutcome hidden = connector.invoke(ToolInvocation.of(DiscoveryTools.SEARCH, search));
        assertThat(capabilityNames(hidden)).doesNotContain("vault.read_secret");
        assertThat(hidden.text()).doesNotContain("vault");
        ToolOutcome notDescribed = connector.invoke(ToolInvocation.of(DiscoveryTools.DESCRIBE,
                Map.of("names", List.of("vault.read_secret"))));
        assertThat(((Map<?, ?>) notDescribed.structuredContent().orElseThrow()).get("not_found"))
                .isEqualTo(List.of("vault.read_secret"));
        assertThatThrownBy(() -> connector.invoke(ToolInvocation.of(DiscoveryTools.CALL, call)))
                .isInstanceOf(AgentWeaveException.class)
                .hasMessageContaining("unknown capability: vault.read_secret");
        connector.close();

        connector = client(ADMIN_TOKEN);
        ToolOutcome visible = connector.invoke(ToolInvocation.of(DiscoveryTools.SEARCH, search));
        assertThat(capabilityNames(visible)).first().isEqualTo("vault.read_secret");
        ToolOutcome secret = connector.invoke(ToolInvocation.of(DiscoveryTools.CALL, call));
        assertThat(secret.text()).isEqualTo("secret db for admin");
    }

    @Test
    void unauthenticatedClientsAreRejected() {
        McpClientConnector anonymous = McpClientConnector.http(server.baseUrl())
                .requestTimeout(Duration.ofSeconds(10)).build();
        try {
            assertThatThrownBy(anonymous::start).isInstanceOf(AgentWeaveException.class);
        } finally {
            anonymous.close();
        }
    }

    private static List<String> capabilityNames(ToolOutcome search) {
        Map<?, ?> structured = (Map<?, ?>) search.structuredContent().orElseThrow();
        return ((List<?>) structured.get("capabilities")).stream()
                .map(c -> String.valueOf(((Map<?, ?>) c).get("name"))).toList();
    }

    private static McpClientConnector client(String token) {
        McpClientConnector c = McpClientConnector.http(server.baseUrl())
                .requestTimeout(Duration.ofSeconds(10))
                .credentials((p, t) -> List.of(new Credential.BearerToken(token)))
                .build();
        c.start();
        return c;
    }
}
