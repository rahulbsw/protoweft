package io.github.rahulbsw.protoweft.agui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Posts run inputs and reads the SSE answer the way the spec's consumer would, checking the wire
 * format on the way: one {@code data:} line per event, LF framing, every event valid against the
 * official schema, and the whole stream accepted by {@link StreamVerifier}.
 */
final class TestClient {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    record Response(int status, Map<String, List<String>> headers, String body, List<AgUiEvent> events) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    static Response post(String url, Object input, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(input instanceof String s ? s : Json.write(input)));
        for (int i = 0; i < headers.length; i += 2) {
            b.setHeader(headers[i], headers[i + 1]);
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        List<AgUiEvent> events = new ArrayList<>();
        if (resp.statusCode() == 200 && resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            events = parse(resp.body());
        }
        return new Response(resp.statusCode(), resp.headers().map(), resp.body(), events);
    }

    static Response post(String url, RunAgentInput input, String... headers) throws Exception {
        return post(url, (Object) Codec.input(input), headers);
    }

    static Response request(String method, String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            b.setHeader(headers[i], headers[i + 1]);
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.headers().map(), resp.body(), List.of());
    }

    static List<AgUiEvent> parse(String body) {
        assertThat(body).doesNotContain("\r");
        assertThat(body).endsWith("\n\n");
        List<AgUiEvent> events = new ArrayList<>();
        StreamVerifier verifier = new StreamVerifier();
        for (String frame : body.split("\n\n")) {
            assertThat(frame).startsWith("data: ").doesNotContain("\n");
            Object json = Json.parse(frame.substring("data: ".length()));
            assertThat(OfficialSchema.errors("Event", json)).as(frame).isEmpty();
            Decoder d = new Decoder();
            AgUiEvent event = EventCodec.decode(d, json);
            assertThat(d.warnings()).as(frame).isEmpty();
            verifier.accept(event);
            events.add(event);
        }
        return events;
    }

    static RunAgentInput input(String thread, String run, AgUiMessage... messages) {
        return RunAgentInput.of(thread, run, List.of(messages));
    }

    static List<String> types(List<AgUiEvent> events) {
        return events.stream().map(AgUiEvent::type).toList();
    }

    private TestClient() {
    }
}
