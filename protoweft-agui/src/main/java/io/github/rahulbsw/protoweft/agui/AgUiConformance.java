package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunError;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunFinished;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunStarted;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Checks an AG-UI agent endpoint against the producer rules of specification 1.0 (the HTTP + SSE
 * binding, the event model, the run lifecycle, versioning and input handling). It sends a few
 * runs and judges what comes back; it needs nothing from the agent beyond answering a user message.
 *
 * <pre>{@code
 * AgUiConformance.Report report = AgUiConformance.builder(URI.create("http://localhost:8080/agui")).build().run();
 * System.out.println(report.text());
 * }</pre>
 *
 * <p>Command line: {@code AgUiConformance <url> [--bearer TOKEN] [--header Name:Value]
 * [--message TEXT] [--timeout SECONDS] [--json]}; exit code 0 when every check passes, 1 otherwise.
 *
 * <p>A producer must emit only shapes the specification describes, so an event or member it does
 * not describe, or a {@code null} sent for an absent optional field, fails the event check here,
 * where a consumer would only warn.
 */
public final class AgUiConformance {

    /** {@code SKIP}: the check does not apply to what the agent sent (for example a run that failed before it started). */
    public enum Status { PASS, FAIL, SKIP }

    /**
     * One check.
     *
     * @param id     stable identifier, for baselines (for example {@code binding/content-type})
     * @param rule   the specification rule it checks
     * @param detail what was observed when it failed
     */
    public record Result(String id, String rule, Status status, String detail) {
    }

    public record Report(URI endpoint, List<Result> results) {
        public Report {
            results = List.copyOf(results);
        }

        /** No check failed (skipped checks do not count against the agent). */
        public boolean passed() {
            return results.stream().noneMatch(r -> r.status() == Status.FAIL);
        }

        public List<Result> failures() {
            return results.stream().filter(r -> r.status() == Status.FAIL).toList();
        }

        /** A human-readable summary, one line per check. */
        public String text() {
            StringBuilder sb = new StringBuilder("AG-UI " + AgUiProtocol.VERSION + " conformance: " + endpoint + "\n");
            for (Result r : results) {
                sb.append("  ").append(r.status()).append("  ").append(r.id()).append(" — ").append(r.rule());
                if (r.detail() != null) {
                    sb.append("\n          ").append(r.detail().replace("\n", "\n          "));
                }
                sb.append('\n');
            }
            long failed = failures().size();
            long skipped = results.stream().filter(r -> r.status() == Status.SKIP).count();
            return sb.append(results.size() - failed - skipped).append(" passed, ").append(failed).append(" failed, ")
                    .append(skipped).append(" skipped\n").toString();
        }

        /** The report as JSON: {@code {"endpoint":…,"results":[{"id","rule","status","detail"}]}}. */
        public String json() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("endpoint", endpoint.toString());
            out.put("results", results.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id());
                m.put("rule", r.rule());
                m.put("status", r.status().name());
                Codec.put(m, "detail", r.detail());
                return m;
            }).toList());
            return Json.write(out);
        }
    }

    private final URI endpoint;
    private final Map<String, String> headers;
    private final String message;
    private final Duration timeout;
    private final HttpClient http;

    private AgUiConformance(Builder b) {
        this.endpoint = b.endpoint;
        this.headers = Map.copyOf(b.headers);
        this.message = b.message;
        this.timeout = b.timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    /** Runs every check. */
    public Report run() {
        List<Result> results = new ArrayList<>();
        String threadId = "conformance-" + UUID.randomUUID();
        String runId = UUID.randomUUID().toString();
        Map<String, Object> input = Codec.input(RunAgentInput.of(threadId, runId,
                List.of(UserMessage.text(UUID.randomUUID().toString(), message))));
        checkRun(results, input, threadId, runId);

        Map<String, Object> newer = Codec.input(RunAgentInput.of(threadId, UUID.randomUUID().toString(),
                List.of(UserMessage.text(UUID.randomUUID().toString(), message))));
        newer.put("protocolVersion", AgUiProtocol.MAJOR + ".999");
        served(results, "version/newer-minor-served",
                "a producer meeting a newer minor of its line MUST serve the run", Json.write(newer));

        Map<String, Object> extra = Codec.input(RunAgentInput.of(threadId, UUID.randomUUID().toString(),
                List.of(UserMessage.text(UUID.randomUUID().toString(), message))));
        extra.put("xConformanceProbe", Map.of("ignored", true));
        served(results, "input/unknown-member-served",
                "unrecognised run-input material does not make the input invalid", Json.write(extra));

        rejected(results, "input/malformed-rejected",
                "input that cannot be parsed is rejected before RUN_STARTED, outside the stream", "{\"threadId\":");
        rejected(results, "input/missing-required-rejected",
                "a run input without its required messages is rejected before RUN_STARTED, outside the stream",
                "{\"threadId\":\"t\",\"runId\":\"" + UUID.randomUUID() + "\"}");
        return new Report(endpoint, results);
    }

    private void checkRun(List<Result> results, Map<String, Object> input, String threadId, String runId) {
        Response resp;
        try {
            resp = post(Json.write(input));
        } catch (IOException e) {
            results.add(fail("binding/run-starts", "a run that starts answers 200 with text/event-stream",
                    "request failed: " + e.getMessage()));
            return;
        }
        boolean stream = resp.status == 200 && resp.contentType.toLowerCase().startsWith(AgUiProtocol.EVENT_STREAM);
        results.add(check("binding/run-starts", "a run that starts answers 200 with text/event-stream", stream,
                "HTTP " + resp.status + ", Content-Type '" + resp.contentType + "'"));
        if (!stream) {
            return;
        }
        String body = new String(resp.body, StandardCharsets.UTF_8);
        results.add(check("binding/lf-framing", "the producer frames the stream with LF line endings only",
                !body.contains("\r"), "the stream contains CR characters"));

        List<String> framing = new ArrayList<>();
        List<String> shape = new ArrayList<>();
        List<String> sequence = new ArrayList<>();
        List<AgUiEvent> events = new ArrayList<>();
        ChunkExpander expander = new ChunkExpander();
        StreamVerifier verifier = new StreamVerifier();
        boolean framingOk = true;
        SseReader reader = new SseReader(new ByteArrayInputStream(resp.body), Integer.MAX_VALUE);
        try {
            String data;
            int n = 0;
            while ((data = reader.next()) != null) {
                n++;
                Object json;
                try {
                    json = Json.parse(data);
                } catch (AgUiFormatException e) {
                    framingOk = false;
                    framing.add("frame " + n + " is not one JSON value: " + abbreviate(data));
                    continue;
                }
                if (!(json instanceof Map)) {
                    framingOk = false;
                    framing.add("frame " + n + " is not a JSON object");
                    continue;
                }
                List<String> warnings = new ArrayList<>();
                AgUiEvent event;
                try {
                    event = EventCodec.decode(new Decoder(warnings), json);
                } catch (AgUiFormatException e) {
                    shape.add("frame " + n + ": " + e.getMessage());
                    continue;
                }
                int frame = n;
                warnings.forEach(w -> shape.add("frame " + frame + ": " + w));
                if (event == null) {
                    continue;
                }
                events.add(event);
                try {
                    for (AgUiEvent e : expander.expand(event)) {
                        verifier.accept(e);
                    }
                } catch (AgUiFormatException e) {
                    sequence.add("frame " + n + " (" + event.type() + "): " + e.getMessage());
                }
            }
        } catch (IOException e) {
            framingOk = false;
            framing.add("reading the stream failed: " + e.getMessage());
        }
        results.add(check("binding/one-event-per-frame", "each SSE data payload is exactly one event object",
                framingOk, String.join("\n", framing)));
        results.add(check("events/described-shapes",
                "every event matches the schema: known types, no undescribed members, absent fields omitted not null",
                shape.isEmpty(), String.join("\n", shape)));
        results.add(check("lifecycle/sequence",
                "the stream follows the ordering rules (starts with RUN_STARTED or RUN_ERROR, items opened before "
                        + "continued, closed before RUN_FINISHED, attribution agrees)", sequence.isEmpty(),
                String.join("\n", sequence)));
        AgUiEvent last = events.isEmpty() ? null : events.get(events.size() - 1);
        results.add(check("lifecycle/terminal", "the stream ends with the run's terminal event (RUN_FINISHED or RUN_ERROR)",
                last instanceof RunFinished || last instanceof RunError,
                last == null ? "no events" : "last event is " + last.type()));
        List<AgUiEvent> boundaries = events.stream()
                .filter(e -> e instanceof RunStarted || e instanceof RunFinished).toList();
        RunStarted requested = events.stream().filter(RunStarted.class::isInstance).map(RunStarted.class::cast)
                .reduce((a, b) -> b).orElse(null);
        if (requested == null && last instanceof RunError error) {
            // A valid stream: the run failed before it started (RUN_ERROR carries no identity or version).
            String why = "the agent reported RUN_ERROR before starting the run: " + error.message();
            results.add(new Result("lifecycle/identity", IDENTITY_RULE, Status.SKIP, why));
            results.add(new Result("version/declared", VERSION_RULE, Status.SKIP, why));
            return;
        }
        results.add(identity(boundaries, threadId, runId));
        String declared = requested == null ? null : requested.protocolVersion();
        results.add(check("version/declared", VERSION_RULE, declared != null && declared.matches("\\d+\\.\\d+"),
                declared == null ? "no protocolVersion on RUN_STARTED" : "declared '" + declared + "'"));
    }

    private static final String IDENTITY_RULE = "every run boundary carries the input's threadId; the last run is the "
            + "requested one (its runId) and earlier, replayed runs use other runIds";
    private static final String VERSION_RULE = "RUN_STARTED declares the producer's protocolVersion as MAJOR.MINOR";

    /**
     * Spec "Run Input", identity: every run on the stream carries the input's {@code threadId} on its
     * boundary events; the requested run (the last) echoes the input's {@code runId}; a replayed run
     * carries its own, and a {@code runId} is never reused on a thread.
     */
    private static Result identity(List<AgUiEvent> boundaries, String threadId, String runId) {
        List<String> problems = new ArrayList<>();
        for (AgUiEvent e : boundaries) {
            String thread = e instanceof RunStarted s ? s.threadId() : ((RunFinished) e).threadId();
            if (!threadId.equals(thread)) {
                problems.add(e.type() + " for thread '" + thread + "' (expected '" + threadId + "')");
            }
        }
        List<RunStarted> starts = boundaries.stream().filter(RunStarted.class::isInstance).map(RunStarted.class::cast)
                .toList();
        if (starts.isEmpty()) {
            problems.add("no RUN_STARTED");
        } else {
            RunStarted lastStart = starts.get(starts.size() - 1);
            if (!runId.equals(lastStart.runId())) {
                problems.add("the last run is '" + lastStart.runId() + "', not the requested '" + runId + "'");
            }
            starts.subList(0, starts.size() - 1).stream().filter(s -> runId.equals(s.runId())).findFirst()
                    .ifPresent(s -> problems.add("an earlier run reuses the requested runId '" + runId + "'"));
        }
        if (!boundaries.isEmpty() && boundaries.get(boundaries.size() - 1) instanceof RunFinished f
                && !runId.equals(f.runId())) {
            problems.add("the final RUN_FINISHED is for run '" + f.runId() + "'");
        }
        return check("lifecycle/identity", IDENTITY_RULE, problems.isEmpty(), String.join("\n", problems));
    }

    private void served(List<Result> results, String id, String rule, String body) {
        try {
            Response resp = post(body);
            String text = new String(resp.body, StandardCharsets.UTF_8);
            boolean ok = resp.status == 200 && resp.contentType.toLowerCase().startsWith(AgUiProtocol.EVENT_STREAM)
                    && (text.contains("\"RUN_STARTED\"") || text.contains("\"RUN_ERROR\""));
            results.add(check(id, rule, ok, "HTTP " + resp.status + ": " + abbreviate(text)));
        } catch (IOException e) {
            results.add(fail(id, rule, "request failed: " + e.getMessage()));
        }
    }

    private void rejected(List<Result> results, String id, String rule, String body) {
        try {
            Response resp = post(body);
            boolean ok = resp.status >= 400 && resp.status < 500
                    && !resp.contentType.toLowerCase().startsWith(AgUiProtocol.EVENT_STREAM);
            results.add(check(id, rule, ok, "HTTP " + resp.status + ", Content-Type '" + resp.contentType + "'"));
        } catch (IOException e) {
            results.add(fail(id, rule, "request failed: " + e.getMessage()));
        }
    }

    private record Response(int status, String contentType, byte[] body) {
    }

    /** One POST, read to the end of the stream or until the timeout. */
    private Response post(String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", AgUiProtocol.EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::setHeader);
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        InputStream in = resp.body();
        CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
            try (in) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        try {
            byte[] bytes = read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse(""), bytes);
        } catch (TimeoutException e) {
            in.close();
            throw new IOException("the stream did not end within " + timeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause() == null ? "read failed" : e.getCause().getMessage(), e.getCause());
        }
    }

    private static Result check(String id, String rule, boolean ok, String detail) {
        return new Result(id, rule, ok ? Status.PASS : Status.FAIL, ok ? null : detail);
    }

    private static Result fail(String id, String rule, String detail) {
        return new Result(id, rule, Status.FAIL, detail);
    }

    private static String abbreviate(String s) {
        String one = s.replace("\n", "\\n");
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    public static final class Builder {
        private final URI endpoint;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String message = "Hello! Please reply with a short greeting.";
        private Duration timeout = Duration.ofSeconds(120);

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        }

        /** Sends {@code Authorization: Bearer <token>}. */
        public Builder bearer(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** The user message each run sends. */
        public Builder message(String message) {
            this.message = Objects.requireNonNull(message, "message");
            return this;
        }

        /** How long one run may take, from request to the end of its stream. Default 120 s. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public AgUiConformance build() {
            return new AgUiConformance(this);
        }
    }

    /** {@code AgUiConformance <url> [--bearer TOKEN] [--header Name:Value] [--message TEXT] [--timeout SECONDS] [--json]}. */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("usage: AgUiConformance <agent-url> [--bearer TOKEN] [--header Name:Value] "
                    + "[--message TEXT] [--timeout SECONDS] [--json]");
            System.exit(2);
        }
        Builder b = builder(URI.create(args[0]));
        boolean json = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--bearer" -> b.bearer(args[++i]);
                case "--header" -> {
                    String[] kv = args[++i].split(":", 2);
                    b.header(kv[0].trim(), kv.length > 1 ? kv[1].trim() : "");
                }
                case "--message" -> b.message(args[++i]);
                case "--timeout" -> b.timeout(Duration.ofSeconds(Long.parseLong(args[++i])));
                case "--json" -> json = true;
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(2);
                }
            }
        }
        Report report = b.build().run();
        System.out.print(json ? report.json() + "\n" : report.text());
        System.exit(report.passed() ? 0 : 1);
    }
}
