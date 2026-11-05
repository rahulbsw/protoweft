package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.utcp.FakeHttpClient.Answer;
import io.agentweave.utcp.TestBackend.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.agentweave.utcp.TestManuals.http;
import static io.agentweave.utcp.TestManuals.manual;
import static io.agentweave.utcp.TestManuals.started;
import static io.agentweave.utcp.TestManuals.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The trust boundary of utcp_http/_security.py. */
class SecurityTest {

    private TestBackend backend;
    private TestBackend other;

    @BeforeEach
    void setUp() throws Exception {
        backend = new TestBackend();
        other = new TestBackend().route("/landing", r -> Reply.json(200, "{}"));
    }

    @AfterEach
    void tearDown() {
        backend.close();
        other.close();
    }

    private static ErrorCategory category(Throwable e) {
        return ((AgentWeaveException) e).category();
    }

    @Test
    void classifiesLoopbackLiteralsWithoutDns() {
        for (String host : new String[] {"localhost", "LOCALHOST.", "127.0.0.1", "127.1", "2130706433", "0177.0.0.1",
                "0x7f000001", "127.255.0.9", "::1", "[::1]", "::ffff:127.0.0.1", "0.0.0.0", "::", "0"}) {
            assertThat(UrlSecurity.isLoopbackHost(host)).as(host).isTrue();
        }
        for (String host : new String[] {"localhost.evil.com", "127.0.0.1.attacker.example", "example.com",
                "10.0.0.1", "128.0.0.1", "face", "::2"}) {
            assertThat(UrlSecurity.isLoopbackHost(host)).as(host).isFalse();
        }
        assertThat(UrlSecurity.isSecure("https://example.com/x")).isTrue();
        assertThat(UrlSecurity.isSecure("http://example.com/x")).isFalse();
        assertThat(UrlSecurity.isSecure("http://user@localhost:8080/x")).isTrue();
        assertThat(UrlSecurity.isSecure("file:///etc/passwd")).isFalse();
    }

    @Test
    void plainHttpToANonLoopbackManualIsRejectedBeforeAnyRequest() {
        FakeHttpClient client = new FakeHttpClient(r -> Answer.json("{}"));
        assertThatThrownBy(() -> UtcpClientConnector.http("m", "http://example.com/utcp").httpClient(client).build().start())
                .hasMessageStartingWith("Security error during manual discovery")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.CONFIGURATION));
        assertThat(client.requests).isEmpty();
    }

    @Test
    void plainHttpToANonLoopbackToolIsRejected() {
        UtcpClientConnector c = started("m", manual(tool("t", http("GET", "http://example.com/x"))));
        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("t", Map.of())))
                .hasMessageStartingWith("Security error during tool invocation")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.PROTOCOL));
    }

    @Test
    void anArgumentMayNotMoveTheRequestOntoLoopback() {
        UtcpClientConnector c = started("m", manual(tool("t", http("GET", "http://{host}:" + backend.port() + "/x"))));
        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("t", Map.of("host", "127.0.0.1"))))
                .hasMessageContaining("does not name a loopback host literally")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
        assertThat(backend.requests).isEmpty();
    }

    @Test
    void aRemoteManualMayNotDeclareLoopbackToolOrTokenUrls() {
        String loopbackTool = manual(tool("t", http("GET", "http://127.0.0.1:1/admin")));
        FakeHttpClient remote = new FakeHttpClient(r -> Answer.json(loopbackTool));
        assertThatThrownBy(() -> UtcpClientConnector.http("m", "https://remote.example/utcp").httpClient(remote).build().start())
                .hasMessageContaining("declares a loopback tool URL")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.PROTOCOL));

        String loopbackToken = manual(tool("t", http("GET", "https://remote.example/x", "auth", Map.of("auth_type",
                "oauth2", "token_url", "http://localhost:9/token", "client_id", "a", "client_secret", "b"))));
        FakeHttpClient remote2 = new FakeHttpClient(r -> Answer.json(loopbackToken));
        assertThatThrownBy(() -> UtcpClientConnector.http("m", "https://remote.example/utcp").httpClient(remote2).build().start())
                .hasMessageContaining("declares a loopback OAuth2 token URL");

        String remoteTool = manual(tool("t", http("GET", "https://remote.example/x")));
        UtcpClientConnector ok = UtcpClientConnector.http("m", "https://remote.example/utcp")
                .httpClient(new FakeHttpClient(r -> Answer.json(remoteTool))).build();
        ok.start();
        assertThat(ok.listTools()).extracting(ToolSpec::name).containsExactly("t");
    }

    @Test
    void aRedirectIsNeverFollowedFromRemoteIntoLoopback() {
        FakeHttpClient remote = new FakeHttpClient(r -> Answer.redirect(backend.url("/utcp")));
        assertThatThrownBy(() -> UtcpClientConnector.http("m", "https://remote.example/utcp").httpClient(remote).build().start())
                .hasMessageContaining("redirected to the loopback address")
                .satisfies(e -> assertThat(category(e)).isEqualTo(ErrorCategory.PROTOCOL));
        assertThat(backend.requests).isEmpty();
    }

    @Test
    void redirectsAreRevalidatedAndCredentialsStrippedAcrossOrigins() {
        backend.route("/same", r -> Reply.redirect(302, "/landing"))
                .route("/landing", r -> Reply.json(200, "{\"here\":true}"))
                .route("/cross", r -> Reply.redirect(307, other.url("/landing")))
                .route("/insecure", r -> Reply.redirect(302, "http://example.com/x"))
                .route("/loop", r -> Reply.redirect(302, "/loop"));
        Map<String, Object> auth = Map.of("auth_type", "api_key", "api_key", "k");

        UtcpClientConnector c = started("m", manual(
                tool("same", http("POST", backend.url("/same"), "auth", auth, "headers", Map.of("X-Plain", "p"))),
                tool("cross", http("POST", backend.url("/cross"), "auth", auth, "headers", Map.of("X-Plain", "p"))),
                tool("insecure", http("GET", backend.url("/insecure"))),
                tool("loop", http("GET", backend.url("/loop")))));

        assertThat(c.invoke(ToolInvocation.of("same", Map.of("body", Map.of("a", 1), "q", "1"))).text()).contains("here");
        TestBackend.Recorded landed = backend.last();
        assertThat(landed.header("X-Api-Key")).isEqualTo("k");
        assertThat(landed.query()).isEqualTo("q=1");
        assertThat(landed.body()).isEqualTo("{\"a\":1}");

        c.invoke(ToolInvocation.of("cross", Map.of("body", Map.of("a", 1), "q", "1")));
        TestBackend.Recorded crossed = other.last();
        assertThat(crossed.header("X-Api-Key")).isNull();
        assertThat(crossed.header("X-Plain")).isEqualTo("p");
        assertThat(crossed.query()).isNull();
        assertThat(crossed.body()).isEmpty();

        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("insecure", Map.of())))
                .hasMessageContaining("(redirect target)");
        assertThatThrownBy(() -> c.invoke(ToolInvocation.of("loop", Map.of())))
                .hasMessageStartingWith("Too many redirects (>5)");
    }
}
