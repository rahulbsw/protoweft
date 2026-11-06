package io.github.rahulbsw.protoweft.utcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UtcpManualProvider over LocalTools, hosted by ProtoweftServer, consumed by UtcpClientConnector. */
class ProviderRoundTripTest {

    public record Forecast(String city, int celsius) {
    }

    public static final class Tools {
        @AgentTool(description = "Adds two integers")
        public int add(@ToolParam(description = "first") int a, @ToolParam(description = "second") int b) {
            return a + b;
        }

        @AgentTool(description = "Weather forecast for a city")
        public Forecast forecast(String city) {
            return new Forecast(city, 21);
        }

        @AgentTool(description = "Always fails")
        public String fail() {
            throw new IllegalStateException("boom");
        }

        @AgentTool(description = "Who is calling")
        public String whoami(CallerIdentity caller) {
            return caller.name();
        }
    }

    private ProtoweftServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static LocalTools tools() {
        return LocalTools.builder().scan(new Tools()).build();
    }

    private String serve(UtcpManualProvider provider) {
        server = ProtoweftServer.builder().port(0).endpoints(provider).start();
        return server.baseUrl();
    }

    @Test
    void publishesAManualWhoseToolsTakeTheWholeArgumentObjectAsJsonBody() throws Exception {
        String base = serve(UtcpManualProvider.builder(tools()).manualVersion("3.1.0").tags("add", "math").build());

        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/utcp")).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> manual = Json.object(Json.parse(res.body()));

        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(manual).containsEntry("utcp_version", "1.1.0").containsEntry("manual_version", "3.1.0");
        Map<String, Object> add = Json.array(manual.get("tools")).stream().map(Json::object)
                .filter(t -> t.get("name").equals("add")).findFirst().orElseThrow();
        assertThat(add.get("tags")).isEqualTo(List.of("math"));
        assertThat(add.get("tool_call_template")).isEqualTo(Map.of("call_template_type", "http", "http_method", "POST",
                "url", base + "/utcp/tools/add", "content_type", "application/json", "body_field", "body"));
        Map<String, Object> inputs = Json.object(add.get("inputs"));
        assertThat(inputs).containsEntry("type", "object").containsEntry("required", List.of("body"));
        assertThat(Json.object(Json.object(inputs.get("properties")).get("body"))).containsEntry("type", "object")
                .containsKey("properties");
    }

    @Test
    void roundTripsThroughTheClientAndAToolRegistry() {
        String base = serve(UtcpManualProvider.builder(tools()).build());
        UtcpClientConnector client = UtcpClientConnector.http("local", base + "/utcp").build();
        client.start();

        assertThat(client.listTools()).extracting(ToolSpec::name).containsExactlyInAnyOrder("add", "forecast", "fail", "whoami");
        assertThat(client.invoke(ToolInvocation.of("add", Map.of("body", Map.of("a", 2, "b", 3)))).text()).isEqualTo("5");

        ToolOutcome forecast = client.invoke(ToolInvocation.of("forecast", Map.of("body", Map.of("city", "Oslo"))));
        assertThat(forecast.structuredContent()).contains(Map.of("city", "Oslo", "celsius", 21));

        ToolOutcome failed = client.invoke(ToolInvocation.of("fail", Map.of("body", Map.of())));
        assertThat(failed.error()).isTrue();
        assertThat(failed.text()).isEqualTo("Internal Server Error: boom");

        ToolOutcome badArgs = client.invoke(ToolInvocation.of("add", Map.of("body", Map.of("a", "x"))));
        assertThat(badArgs.error()).isTrue();
        assertThat(badArgs.text()).startsWith("Bad Request: ");

        try (ToolRegistry registry = new ToolRegistry().register("local", client)) {
            assertThat(registry.listTools()).extracting(ToolSpec::name).contains("local.add", "local.forecast");
            assertThat(registry.invoke(ToolInvocation.of("local.add", Map.of("body", Map.of("a", 40, "b", 2)))).text())
                    .isEqualTo("42");
        }
    }

    @Test
    void servesARegistryWithDottedNamesUnderUrlSafeNames() {
        ToolRegistry inner = new ToolRegistry().register("inner", tools());
        String base = serve(UtcpManualProvider.builder(inner).build());
        UtcpClientConnector client = UtcpClientConnector.http("outer", base + "/utcp").build();
        client.start();

        assertThat(client.listTools()).extracting(ToolSpec::name).contains("inner_add").noneMatch(n -> n.contains("."));
        assertThat(client.invoke(ToolInvocation.of("inner_add", Map.of("body", Map.of("a", 1, "b", 1)))).text())
                .isEqualTo("2");
    }

    @Test
    void answersProtocolErrorsWithJsonErrorBodies() throws Exception {
        String base = serve(UtcpManualProvider.builder(tools()).build());
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> badJson = http.send(HttpRequest.newBuilder(URI.create(base + "/utcp/tools/add"))
                .POST(HttpRequest.BodyPublishers.ofString("not json")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(badJson.statusCode()).isEqualTo(400);
        assertThat(Json.parse(badJson.body())).isEqualTo(Map.of("error", "request body is not valid JSON"));

        HttpResponse<String> unknown = http.send(HttpRequest.newBuilder(URI.create(base + "/utcp/tools/nope"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(Json.parse(unknown.body())).isEqualTo(Map.of("error", "Tool not found: nope"));
    }

    @Test
    void anInboundAuthenticatorIsAnnouncedAsApiKeyAuthInTheManual() {
        UtcpManualProvider provider = UtcpManualProvider.builder(tools()).apiKeyAuth("X-Api-Key", "API_KEY").build();
        server = ProtoweftServer.builder().port(0).endpoints(provider)
                .authenticator(req -> req.header("X-Api-Key").filter("s3cret"::equals).isPresent()
                        ? AuthDecision.allow(CallerIdentity.of("alice"))
                        : AuthDecision.unauthenticated("missing or wrong API key"))
                .start();
        String url = server.baseUrl() + "/utcp";

        assertThatThrownBy(() -> UtcpClientConnector.http("secure", url).build().start())
                .hasMessageContaining("Unauthorized: missing or wrong API key")
                .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.AUTHENTICATION));

        UtcpClientConnector client = UtcpClientConnector.http("secure", url)
                .auth(Map.of("auth_type", "api_key", "api_key", "${API_KEY}", "var_name", "X-Api-Key"))
                .variable("secure_API_KEY", "s3cret")
                .environment(k -> null)
                .build();
        client.start();
        assertThat(client.invoke(ToolInvocation.of("whoami", Map.of("body", Map.of()))).text()).isEqualTo("alice");
    }

    @Test
    void usesAConfiguredBaseUrlAndRejectsDollarSigns() {
        UtcpManualProvider provider = UtcpManualProvider.builder(tools()).baseUrl("https://tools.example.com/").build();
        Map<String, Object> add = Json.array(provider.manual("https://tools.example.com/").get("tools")).stream()
                .map(Json::object).filter(t -> t.get("name").equals("add")).findFirst().orElseThrow();
        assertThat(Json.object(add.get("tool_call_template"))).containsEntry("url", "https://tools.example.com/utcp/tools/add");
        assertThatThrownBy(() -> provider.manual("https://$HOST")).isInstanceOf(IllegalStateException.class);
    }
}
