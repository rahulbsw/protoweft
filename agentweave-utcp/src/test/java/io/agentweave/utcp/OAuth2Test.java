package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.utcp.TestBackend.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.agentweave.utcp.TestManuals.http;
import static io.agentweave.utcp.TestManuals.manual;
import static io.agentweave.utcp.TestManuals.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OAuth2 client credentials: body credentials, Basic fallback, caching and {@code expires_in}. */
class OAuth2Test {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2025-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private TestBackend backend;
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger issued = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        backend = new TestBackend().route("/api", r -> Reply.json(200, "{\"auth\":\"" + r.header("Authorization") + "\"}"));
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    private UtcpClientConnector connector(String scope) {
        Map<String, Object> auth = new java.util.LinkedHashMap<>(Map.of("auth_type", "oauth2",
                "token_url", backend.url("/token"), "client_id", "cid", "client_secret", "${SECRET}"));
        if (scope != null) {
            auth.put("scope", scope);
        }
        UtcpClientConnector c = UtcpClientConnector.text("m", manual(tool("t", http("GET", backend.url("/api"), "auth", auth))))
                .allowedCommunicationProtocols("http")
                .variable("m_SECRET", "s3")
                .environment(k -> null)
                .clock(clock)
                .build();
        c.start();
        return c;
    }

    private List<TestBackend.Recorded> tokenRequests() {
        return backend.requests.stream().filter(r -> r.path().equals("/token")).toList();
    }

    @Test
    void fetchesWithBodyCredentialsCachesAndHonoursExpiresIn() {
        backend.route("/token", r -> Reply.json(200,
                "{\"access_token\":\"tok" + issued.incrementAndGet() + "\",\"expires_in\":3600}"));
        UtcpClientConnector c = connector("read write");

        assertThat(c.invoke(ToolInvocation.of("t", Map.of())).structuredContent()).contains(Map.of("auth", "Bearer tok1"));
        assertThat(c.invoke(ToolInvocation.of("t", Map.of())).text()).contains("Bearer tok1");
        assertThat(tokenRequests()).hasSize(1);
        TestBackend.Recorded token = tokenRequests().get(0);
        assertThat(token.method()).isEqualTo("POST");
        assertThat(token.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(token.body()).isEqualTo("grant_type=client_credentials&client_id=cid&client_secret=s3&scope=read+write");

        clock.now = clock.now.plus(Duration.ofSeconds(3600));
        assertThat(c.invoke(ToolInvocation.of("t", Map.of())).text()).contains("Bearer tok2");
        assertThat(tokenRequests()).hasSize(2);
    }

    @Test
    void withoutExpiresInTheTokenIsCachedLikeTheReference() {
        backend.route("/token", r -> Reply.json(200, "{\"access_token\":\"tok" + issued.incrementAndGet() + "\"}"));
        UtcpClientConnector c = connector(null);
        c.invoke(ToolInvocation.of("t", Map.of()));
        clock.now = clock.now.plus(Duration.ofDays(30));
        assertThat(c.invoke(ToolInvocation.of("t", Map.of())).text()).contains("Bearer tok1");
        assertThat(tokenRequests()).hasSize(1);
        assertThat(tokenRequests().get(0).body()).doesNotContain("scope");
    }

    @Test
    void retriesWithBasicClientAuthentication() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("cid:s3".getBytes());
        backend.route("/token", r -> basic.equals(r.header("Authorization"))
                ? Reply.json(200, "{\"access_token\":\"viaBasic\"}")
                : Reply.json(401, "{\"error\":\"invalid_client\"}"));

        assertThat(connector("s").invoke(ToolInvocation.of("t", Map.of())).text()).contains("Bearer viaBasic");
        assertThat(tokenRequests()).hasSize(2);
        assertThat(tokenRequests().get(1).body()).isEqualTo("grant_type=client_credentials&scope=s");
    }

    @Test
    void failingTokenEndpointIsAnAuthenticationErrorNotBearerNone() {
        backend.route("/token", r -> Reply.json(401, "{\"error\":\"invalid_client\"}"));
        UtcpClientConnector c = connector(null);

        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("t", Map.of())))
                .isInstanceOf(AgentWeaveException.class)
                .hasMessageContaining("Unauthorized: invalid_client")
                .extracting(e -> ((AgentWeaveException) e).category()).isEqualTo(ErrorCategory.AUTHENTICATION);
        assertThat(backend.requests).noneMatch(r -> r.path().equals("/api"));
    }
}
