package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.utcp.TestBackend.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.agentweave.utcp.TestManuals.http;
import static io.agentweave.utcp.TestManuals.manual;
import static io.agentweave.utcp.TestManuals.started;
import static io.agentweave.utcp.TestManuals.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The http call template algorithm against a recording backend. */
class HttpCallTemplateTest {

    private TestBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        backend = new TestBackend().route("/ok", r -> Reply.json(200, "{\"ok\":true}"));
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    private ToolOutcome call(Map<String, Object> template, Map<String, Object> args) {
        UtcpClientConnector c = started("m", manual(tool("t", template)));
        return c.invoke(ToolInvocation.of("t", args));
    }

    @Test
    void placesPathHeaderBodyAndQueryArgumentsLikeTheReferenceClient() {
        backend.route("/items/a%20b%2Fc%C3%A9", r -> Reply.json(200, "{}"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", "a b/cé");
        args.put("X-Trace", "t1");
        args.put("payload", Map.of("x", 1, "flags", List.of(true)));
        args.put("limit", 5);
        args.put("tags", List.of("p", "q"));
        args.put("q", "x&y z");

        call(http("POST", backend.url("/items/{id}"), "header_fields", List.of("X-Trace"), "body_field", "payload",
                "headers", Map.of("X-Static", "s")), args);

        TestBackend.Recorded r = backend.last();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/items/a%20b%2Fc%C3%A9");
        assertThat(r.header("X-Trace")).isEqualTo("t1");
        assertThat(r.header("X-Static")).isEqualTo("s");
        assertThat(r.header("Content-Type")).isEqualTo("application/json");
        assertThat(Json.parse(r.body())).isEqualTo(Map.of("x", 1, "flags", List.of(true)));
        assertThat(r.query()).isEqualTo("limit=5&tags=p&tags=q&q=x%26y+z");
    }

    @Test
    void withoutTheBodyFieldArgumentThereIsNoBodyAndNoContentType() {
        call(http("GET", backend.url("/ok")), Map.of("a", "1"));

        TestBackend.Recorded r = backend.last();
        assertThat(r.body()).isEmpty();
        assertThat(r.header("Content-Type")).isNull();
        assertThat(r.query()).isEqualTo("a=1");
    }

    @Test
    void defaultBodyFieldIsBodyAndNonJsonContentTypeSendsRawText() {
        call(http("PUT", backend.url("/ok"), "content_type", "text/plain"), Map.of("body", "raw text"));

        assertThat(backend.last().body()).isEqualTo("raw text");
        assertThat(backend.last().header("Content-Type")).isEqualTo("text/plain");
    }

    @Test
    void missingPathParameterIsAnArgumentError() {
        assertThatThrownBy(() -> call(http("GET", backend.url("/items/{id}")), Map.of()))
                .isInstanceOf(AgentWeaveException.class)
                .hasMessage("Missing required path parameter: id")
                .extracting(e -> ((AgentWeaveException) e).category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
    }

    @Test
    void apiKeyGoesToHeaderQueryOrCookie() {
        call(http("GET", backend.url("/ok"), "auth", Map.of("auth_type", "api_key", "api_key", "k1")), Map.of());
        assertThat(backend.last().header("X-Api-Key")).isEqualTo("k1");

        call(http("GET", backend.url("/ok"), "auth",
                Map.of("auth_type", "api_key", "api_key", "k2", "var_name", "key", "location", "query")), Map.of("a", "b"));
        assertThat(backend.last().query()).isEqualTo("a=b&key=k2");

        call(http("GET", backend.url("/ok"), "auth",
                Map.of("auth_type", "api_key", "api_key", "k3", "var_name", "sid", "location", "cookie")), Map.of());
        assertThat(backend.last().header("Cookie")).isEqualTo("sid=k3");
    }

    @Test
    void emptyApiKeyIsAnAuthenticationError() {
        assertThatThrownBy(() -> call(http("GET", backend.url("/ok"), "auth",
                Map.of("auth_type", "api_key", "api_key", "")), Map.of()))
                .hasMessage("API key for ApiKeyAuth not found.")
                .extracting(e -> ((AgentWeaveException) e).category()).isEqualTo(ErrorCategory.AUTHENTICATION);
    }

    @Test
    void basicAuthSendsAnAuthorizationHeader() {
        call(http("GET", backend.url("/ok"), "auth", Map.of("auth_type", "basic", "username", "u", "password", "p")),
                Map.of());
        assertThat(backend.last().header("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("u:p".getBytes()));
    }

    @Test
    void statusAtLeast400IsAToolFailureWithTheReferenceMessage() {
        backend.route("/forbidden", r -> Reply.json(403, "{\"error\":\"nope\"}"))
                .route("/boom", r -> Reply.text(500, "boom\nwith\u0007control"))
                .route("/gone", r -> new Reply(404, null, "", Map.of()))
                .route("/detail", r -> Reply.json(400, "{\"message\":\" \",\"detail\":\"bad id\"}"))
                .route("/structured", r -> Reply.json(422, "{\"error\":{\"code\":7}}"));

        assertThat(call(http("GET", backend.url("/forbidden")), Map.of()))
                .satisfies(o -> assertThat(o.error()).isTrue())
                .satisfies(o -> assertThat(o.text()).isEqualTo("Forbidden: nope"));
        assertThat(call(http("GET", backend.url("/boom")), Map.of()).text())
                .isEqualTo("Internal Server Error: boom with control");
        assertThat(call(http("GET", backend.url("/gone")), Map.of()).text()).isEqualTo("Not Found");
        assertThat(call(http("GET", backend.url("/detail")), Map.of()).text()).isEqualTo("Bad Request: bad id");
        assertThat(call(http("GET", backend.url("/structured")), Map.of()).text())
                .isEqualTo("Unprocessable Content: {\"error\":{\"code\":7}}");
    }

    @Test
    void mapsJsonObjectsArraysAndTextResponses() {
        backend.route("/obj", r -> Reply.json(200, "{\"a\":1}"))
                .route("/arr", r -> Reply.json(200, "[1,2]"))
                .route("/txt", r -> Reply.text(200, "plain"))
                .route("/badjson", r -> Reply.json(200, "not json"));

        ToolOutcome obj = call(http("GET", backend.url("/obj")), Map.of());
        assertThat(obj.structuredContent()).contains(Map.of("a", 1));
        assertThat(Json.parse(obj.text())).isEqualTo(Map.of("a", 1));

        ToolOutcome arr = call(http("GET", backend.url("/arr")), Map.of());
        assertThat(arr.text()).isEqualTo("[1,2]");
        assertThat(arr.structuredContent()).isEmpty();

        assertThat(call(http("GET", backend.url("/txt")), Map.of()).text()).isEqualTo("plain");
        assertThat(call(http("GET", backend.url("/badjson")), Map.of()).text()).isEqualTo("not json");
    }

    @Test
    void textCallTemplateReturnsItsContent() {
        ToolOutcome outcome = call(Map.of("call_template_type", "text", "content", "hello"), Map.of());
        assertThat(outcome.text()).isEqualTo("hello");
    }

    @Test
    void connectionFailureIsAConnectionError() {
        int port = backend.port();
        backend.close();
        assertThatThrownBy(() -> call(http("GET", "http://127.0.0.1:" + port + "/x"), Map.of()))
                .isInstanceOf(AgentWeaveException.class)
                .extracting(e -> ((AgentWeaveException) e).category()).isEqualTo(ErrorCategory.CONNECTION);
    }
}
