package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The HTTP + SSE binding (spec "HTTP + Server-Sent Events"): {@code POST <path>} with a
 * {@code RunAgentInput} answers {@code 200 text/event-stream}, one event per {@code data:} frame;
 * input rejected before the run starts gets an HTTP error and no stream.
 * {@code GET <path>/capabilities} serves the agent's capabilities document (a Protoweft convention:
 * the spec defines the document but no retrieval endpoint).
 */
final class AgUiServlet extends HttpServlet {

    private static final System.Logger LOG = System.getLogger(AgUiServlet.class.getName());

    private final transient AgUiAgent agent;
    private final transient ExecutorService executor;
    private final transient ObservationHandler observations;
    private final transient Cors cors;
    private final int maxRequestBytes;
    private final boolean protobuf;
    private final byte[] capabilities;

    AgUiServlet(AgUiAgent agent, ExecutorService executor, ObservationHandler observations, Cors cors,
                int maxRequestBytes, Map<String, Object> capabilities, boolean protobuf) {
        this.protobuf = protobuf;
        this.agent = agent;
        this.executor = executor;
        this.observations = observations;
        this.cors = cors;
        this.maxRequestBytes = maxRequestBytes;
        this.capabilities = Json.writeBytes(capabilities);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        cors.preflight(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!req.getRequestURI().endsWith(AgUiAgentServer.CAPABILITIES_SUFFIX)) {
            error(resp, 405, "use POST to start a run");
            return;
        }
        cors.apply(req, resp);
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getOutputStream().write(capabilities);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (req.getRequestURI().endsWith(AgUiAgentServer.CAPABILITIES_SUFFIX)) {
            error(resp, 405, "capabilities are read with GET");
            return;
        }
        cors.apply(req, resp);
        String contentType = req.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            error(resp, 415, "the run input must be sent as application/json");
            return;
        }
        String binding = binding(req.getHeader("Accept"), protobuf);
        if (binding == null) {
            error(resp, 406, "this endpoint answers " + AgUiProtocol.EVENT_STREAM
                    + (protobuf ? " or " + AgUiProtocol.EVENT_PROTOBUF : ""));
            return;
        }
        byte[] body = readLimited(req.getInputStream());
        if (body == null) {
            error(resp, 413, "the run input exceeds " + maxRequestBytes + " bytes");
            return;
        }
        RunAgentInput input;
        List<String> warnings = new ArrayList<>();
        try {
            input = Codec.input(new Decoder(warnings), Json.parse(body), "RunAgentInput");
        } catch (AgUiFormatException e) {
            error(resp, 400, "invalid run input: " + e.getMessage());
            return;
        }
        int major = AgUiProtocol.major(input.protocolVersion());
        if (input.protocolVersion() != null && major != AgUiProtocol.MAJOR) {
            error(resp, 400, "protocol version " + input.protocolVersion() + " is not supported; this agent speaks "
                    + AgUiProtocol.VERSION);
            return;
        }
        if (input.protocolVersion() != null && !input.protocolVersion().equals(AgUiProtocol.VERSION)) {
            warnings.add("client declares protocol " + input.protocolVersion() + "; serving as " + AgUiProtocol.VERSION);
        }
        warnings.forEach(w -> LOG.log(System.Logger.Level.WARNING, "AG-UI run " + input.runId() + ": " + w));
        CallerIdentity caller = InboundAuthFilter.caller(req);
        AsyncContext async = req.startAsync();
        async.setTimeout(0);
        try {
            executor.execute(() -> run(input, caller, async, binding));
        } catch (RejectedExecutionException e) {
            error(resp, 503, "the server is shutting down");
            async.complete();
        }
    }

    private void run(RunAgentInput input, CallerIdentity caller, AsyncContext async, String binding) {
        HttpServletResponse resp = (HttpServletResponse) async.getResponse();
        try {
            resp.setStatus(200);
            boolean binary = binding.equals(AgUiProtocol.EVENT_PROTOBUF);
            // Exactly the media type for the binary binding: consumers select their parser by it.
            resp.setContentType(binding);
            if (!binary) {
                resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            }
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("X-Accel-Buffering", "no");
            resp.setHeader("Vary", "Accept");
            OutputStream out = resp.getOutputStream();
            RunEmitter emitter = new RunEmitter(input, caller, event -> {
                out.write(binary ? ProtoBinding.frame(EventCodec.encode(event)) : Sse.frame(EventCodec.encode(event)));
                resp.flushBuffer();
            });
            async.addListener(new Disconnect(emitter));
            emitter.start();
            try {
                ObservationHandler.observe(observations, new Observation(ProtocolId.AGUI, "agent/run", input.runId()),
                        () -> {
                            try {
                                agent.run(input, emitter);
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new AgentFailure(e);
                            }
                            return null;
                        });
                emitter.endIfOpen();
            } catch (RuntimeException e) {
                Throwable cause = e instanceof AgentFailure ? e.getCause() : e;
                LOG.log(System.Logger.Level.DEBUG, "AG-UI agent failed for run " + input.runId(), cause);
                emitter.failIfOpen(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "AG-UI stream for run " + input.runId() + " ended early", e);
        } finally {
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
                // Already completed by the container after a disconnect.
            }
        }
    }

    /** {@code null} if the body is larger than allowed. */
    private byte[] readLimited(InputStream in) throws IOException {
        byte[] body = in.readNBytes(maxRequestBytes + 1);
        return body.length > maxRequestBytes ? null : body;
    }

    /**
     * The binding to answer in, or {@code null} for 406. Per the spec: Protobuf whenever
     * {@code Accept} admits its media type with a positive quality (wildcards included) and the
     * binding is on; else SSE if admitted. Relative qualities of the two are not compared. No
     * {@code Accept} gets SSE, as from the official encoder.
     */
    static String binding(String accept, boolean protobuf) {
        if (accept == null || accept.isBlank()) {
            return AgUiProtocol.EVENT_STREAM;
        }
        if (protobuf && admits(accept, AgUiProtocol.EVENT_PROTOBUF)) {
            return AgUiProtocol.EVENT_PROTOBUF;
        }
        return admits(accept, AgUiProtocol.EVENT_STREAM) ? AgUiProtocol.EVENT_STREAM : null;
    }

    /**
     * Whether {@code accept} admits {@code mediaType}: the most specific matching range (exact, then
     * {@code type/*}, then {@code *}{@code /*}, as RFC 9110 orders them) has {@code q > 0}.
     */
    static boolean admits(String accept, String mediaType) {
        String main = mediaType.substring(0, mediaType.indexOf('/'));
        int best = 0;
        double quality = 0;
        for (String range : accept.split(",")) {
            String[] parts = range.split(";");
            String type = parts[0].trim().toLowerCase();
            int specificity = type.equals(mediaType) ? 3 : type.equals(main + "/*") ? 2 : type.equals("*/*") ? 1 : 0;
            if (specificity == 0 || specificity < best) {
                continue;
            }
            double q = 1;
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].trim().split("=", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("q")) {
                    try {
                        q = Double.parseDouble(kv[1].trim());
                    } catch (NumberFormatException e) {
                        q = 0;
                    }
                }
            }
            quality = specificity > best ? q : Math.max(quality, q);
            best = specificity;
        }
        return quality > 0;
    }

    private static void error(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getOutputStream().write(Json.writeBytes(Map.of("error", message)));
    }

    private static final class AgentFailure extends RuntimeException {
        AgentFailure(Exception cause) {
            super(cause);
        }
    }

    /** A client that goes away cancels the run: nothing more can reach it. */
    private record Disconnect(RunEmitter emitter) implements AsyncListener {
        @Override
        public void onComplete(AsyncEvent event) {
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            emitter.disconnected();
        }

        @Override
        public void onError(AsyncEvent event) {
            emitter.disconnected();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }
    }
}
