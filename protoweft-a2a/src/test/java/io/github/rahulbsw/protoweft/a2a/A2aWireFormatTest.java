package io.github.rahulbsw.protoweft.a2a;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw HTTP against the server, without the SDK client: proves the A2A 1.0 JSON-RPC wire format. */
class A2aWireFormatTest {

    static TestServer server;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void agentCardHasTheSpecShape() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create(server.baseUrl() + "/.well-known/agent-card.json")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        JsonObject card = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(card.get("name").getAsString()).isEqualTo("Test Agent");
        assertThat(card.get("description").getAsString()).isEqualTo("Answers scripted test messages");
        assertThat(card.get("version").getAsString()).isEqualTo("1.2.3");
        JsonObject iface = card.getAsJsonArray("supportedInterfaces").get(0).getAsJsonObject();
        assertThat(iface.get("protocolBinding").getAsString()).isEqualTo("JSONRPC");
        assertThat(iface.get("url").getAsString()).isEqualTo(server.baseUrl() + "/a2a");
        assertThat(iface.get("protocolVersion").getAsString()).isEqualTo("1.0");
        JsonObject caps = card.getAsJsonObject("capabilities");
        assertThat(caps.get("streaming").getAsBoolean()).isTrue();
        assertThat(caps.get("pushNotifications").getAsBoolean()).isTrue();
        assertThat(caps.get("extendedAgentCard").getAsBoolean()).isTrue();
        assertThat(strings(card.getAsJsonArray("defaultInputModes"))).containsExactly("text/plain", "application/json");
        assertThat(strings(card.getAsJsonArray("defaultOutputModes"))).containsExactly("text/plain", "application/json");
        JsonObject skill = card.getAsJsonArray("skills").get(0).getAsJsonObject();
        assertThat(skill.get("id").getAsString()).isEqualTo("echo");
        assertThat(strings(skill.getAsJsonArray("tags"))).containsExactly("test");
    }

    @Test
    void sendMessageOverRawJsonRpc() throws Exception {
        JsonObject response = rpc("SendMessage", "{\"message\":{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"report\"}]}}", "1.0");

        assertThat(response.get("jsonrpc").getAsString()).isEqualTo("2.0");
        assertThat(response.get("id").getAsInt()).isEqualTo(1);
        JsonObject task = response.getAsJsonObject("result").getAsJsonObject("task");
        assertThat(task.getAsJsonObject("status").get("state").getAsString()).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(task.getAsJsonArray("artifacts")).hasSize(2);
        assertThat(task.getAsJsonArray("history").get(0).getAsJsonObject().get("messageId").getAsString())
                .isEqualTo("m-1");

        JsonObject reply = rpc("SendMessage", "{\"message\":{\"messageId\":\"m-2\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"hello\"}]}}", "1.0");
        JsonObject message = reply.getAsJsonObject("result").getAsJsonObject("message");
        assertThat(message.get("role").getAsString()).isEqualTo("ROLE_AGENT");
        assertThat(message.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString()).isEqualTo("hi there");
    }

    @Test
    void missingVersionHeaderMeansA2a03AndIsRejected() throws Exception {
        JsonObject response = rpc("GetTask", "{\"id\":\"x\"}", null);
        assertThat(response.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32009);
    }

    @Test
    void unknownMethodIsMethodNotFound() throws Exception {
        JsonObject response = rpc("tasks/get", "{\"id\":\"x\"}", "1.0");
        assertThat(response.getAsJsonObject("error").get("code").getAsInt()).isEqualTo(-32601);
    }

    @Test
    void streamingIsServerSentEvents() throws Exception {
        HttpResponse<String> response = HTTP.send(post("SendStreamingMessage", "{\"message\":{\"messageId\":\"m-3\","
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"report\"}]}}", "1.0"), HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));
        List<JsonObject> events = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            if (line.startsWith("data: ")) {
                events.add(JsonParser.parseString(line.substring(6)).getAsJsonObject().getAsJsonObject("result"));
            }
        }
        assertThat(events.get(0).has("task")).isTrue();
        assertThat(events.get(events.size() - 1).getAsJsonObject("statusUpdate").getAsJsonObject("status")
                .get("state").getAsString()).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(events).anyMatch(e -> e.has("artifactUpdate"));
    }

    @Test
    void pushConfigInAStreamingSendReachesTheWebhook() throws Exception {
        server.pushes.clear();
        HttpResponse<String> stream = HTTP.send(post("SendStreamingMessage", "{\"message\":{\"messageId\":\"m-4\","
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"report\"}]},\"configuration\":"
                + "{\"taskPushNotificationConfig\":{\"url\":\"" + server.baseUrl() + "/webhook\",\"token\":\"tok-1\"}}}",
                "1.0"), HttpResponse.BodyHandlers.ofString());
        String taskId = stream.body().split("\"id\":\"")[1].split("\"")[0];

        List<TestServer.Push> received = awaitPush("TASK_STATE_COMPLETED");
        assertThat(received).isNotEmpty().allMatch(p -> "tok-1".equals(p.token()) && p.body().contains(taskId));
        assertThat(received).anyMatch(p -> p.body().contains("TASK_STATE_COMPLETED"));
    }

    @Test
    void pushConfigCreatedForAPausedTaskReceivesItsCompletion() throws Exception {
        JsonObject paused = rpc("SendMessage", "{\"message\":{\"messageId\":\"m-5\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"book\"}]}}", "1.0").getAsJsonObject("result").getAsJsonObject("task");
        String taskId = paused.get("id").getAsString();
        String contextId = paused.get("contextId").getAsString();
        JsonObject created = rpc("CreateTaskPushNotificationConfig", "{\"taskId\":\"" + taskId + "\",\"url\":\""
                + server.baseUrl() + "/webhook\",\"token\":\"tok-2\"}", "1.0");
        assertThat(created.getAsJsonObject("result").get("taskId").getAsString()).isEqualTo(taskId);
        JsonObject listed = rpc("ListTaskPushNotificationConfigs", "{\"taskId\":\"" + taskId + "\"}", "1.0");
        assertThat(listed.getAsJsonObject("result").getAsJsonArray("configs")).hasSize(1);
        server.pushes.clear();

        rpc("SendMessage", "{\"message\":{\"messageId\":\"m-6\",\"role\":\"ROLE_USER\",\"taskId\":\"" + taskId
                + "\",\"contextId\":\"" + contextId + "\",\"parts\":[{\"text\":\"3\"}]}}", "1.0");

        List<TestServer.Push> received = awaitPush("TASK_STATE_COMPLETED");
        assertThat(received).isNotEmpty().allMatch(p -> "tok-2".equals(p.token()) && p.body().contains(taskId));
        assertThat(received).anyMatch(p -> p.body().contains("TASK_STATE_COMPLETED"));
    }

    private static List<TestServer.Push> awaitPush(String marker) throws InterruptedException {
        List<TestServer.Push> received = new ArrayList<>();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until && received.stream().noneMatch(p -> p.body().contains(marker))) {
            TestServer.Push push = server.pushes.poll(200, TimeUnit.MILLISECONDS);
            if (push != null) {
                received.add(push);
            }
        }
        return received;
    }

    @Test
    void anonymousExtendedCardRequestIsUnauthorized() throws Exception {
        HttpResponse<String> response = HTTP.send(post("GetExtendedAgentCard", "{}", "1.0"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);

        HttpResponse<String> authorized = HTTP.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/a2a"))
                .header("Content-Type", "application/json").header("A2A-Version", "1.0")
                .header("Authorization", "Bearer alice-token")
                .POST(HttpRequest.BodyPublishers.ofString(body("GetExtendedAgentCard", "{}"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(JsonParser.parseString(authorized.body()).getAsJsonObject().getAsJsonObject("result")
                .get("name").getAsString()).isEqualTo("Test Agent (extended)");
    }

    @Test
    void inboundAuthenticatorGuardsTheEndpoint() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/a2a"))
                .header("Content-Type", "application/json").header("A2A-Version", "1.0")
                .header("Authorization", "Bearer nope")
                .POST(HttpRequest.BodyPublishers.ofString(body("GetTask", "{\"id\":\"x\"}"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    private static JsonObject rpc(String method, String params, String version) throws Exception {
        HttpResponse<String> response = HTTP.send(post(method, params, version), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static HttpRequest post(String method, String params, String version) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/a2a"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(method, params)));
        if (version != null) {
            b.header("A2A-Version", version);
        }
        return b.build();
    }

    private static String body(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }
}
