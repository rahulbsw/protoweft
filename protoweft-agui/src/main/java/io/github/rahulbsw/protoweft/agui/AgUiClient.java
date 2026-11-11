package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunError;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunFinished;
import io.github.rahulbsw.protoweft.agui.AgUiEvent.RunStarted;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ActivityMessage;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Consumer;

/**
 * Calls one remote AG-UI agent over the HTTP + SSE binding.
 *
 * <pre>{@code
 * AgUiClient agent = AgUiClient.builder("https://agents.example.com/agent").build();
 * RunResult result = agent.run(RunAgentInput.of(threadId, runId, messages), event -> {
 *     if (event instanceof AgUiEvent.TextMessageContent text) {
 *         System.out.print(text.delta());
 *     }
 * });
 * }</pre>
 *
 * <p>Every stream goes through the spec's consumer pipeline before an event reaches the listener:
 * unrecognised events are dropped and unrecognised fields stripped (each reported in
 * {@link RunResult#warnings()}), chunks are expanded into start/content/end, and the sequence is
 * verified. A malformed value or a sequence violation fails the run with a
 * {@link ProtoweftException} ({@code PROTOCOL}); an agent's own {@code RUN_ERROR} is a
 * {@link RunResult} like any other ending. Activity messages are stripped from outgoing input, and
 * the input declares this module's protocol version when it declares none.
 */
public final class AgUiClient {

    private final URI endpoint;
    private final HttpClient http;
    private final CredentialProvider credentials;
    private final HttpAuthorizer authorizer;
    private final Duration responseTimeout;
    private final int maxEventBytes;
    private final Consumer<String> warnings;
    private final boolean preferProtobuf;

    private AgUiClient(Builder b) {
        if (b.preferProtobuf) {
            ProtoBinding.require("AgUiClient.preferProtobuf");
        }
        this.preferProtobuf = b.preferProtobuf;
        this.endpoint = b.endpoint;
        this.http = b.http != null ? b.http : HttpClient.newBuilder().connectTimeout(b.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.credentials = b.credentials;
        this.authorizer = b.authorizer;
        this.responseTimeout = b.responseTimeout;
        this.maxEventBytes = b.maxEventBytes;
        this.warnings = b.warnings;
    }

    public static Builder builder(String endpoint) {
        return builder(URI.create(endpoint));
    }

    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    public URI endpoint() {
        return endpoint;
    }

    /** Runs the agent, delivering each event to {@code listener} on the calling thread; blocks until the stream ends. */
    public RunResult run(RunAgentInput input, Consumer<AgUiEvent> listener) {
        return new Call(input).execute(listener);
    }

    /** Starts a run that the caller can cancel (closing the stream) from another thread. */
    public Call call(RunAgentInput input) {
        return new Call(input);
    }

    /**
     * The agent's capabilities document from {@code GET <endpoint>/capabilities}, a Protoweft
     * convention: the spec defines the document but no endpoint, so other servers usually have none
     * (empty).
     */
    public Optional<Map<String, Object>> capabilities() {
        URI uri = URI.create(endpoint + AgUiAgentServer.CAPABILITIES_SUFFIX);
        try {
            HttpResponse<String> resp = send(uri, null, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = Json.parse(resp.body()) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            return Optional.ofNullable(doc);
        } catch (IOException | AgUiFormatException e) {
            return Optional.empty();
        }
    }

    public ComplianceReport compliance() {
        return AgUiFeatures.clientReport(ProtoBinding.available());
    }

    /**
     * The run as a cold {@link Flow.Publisher}: each subscription starts its own run. The stream is
     * read on a dedicated daemon thread and events are delivered on {@code executor}, so a small or
     * single-thread executor cannot deadlock against the reader. Events arrive with backpressure;
     * the publisher completes after the run's terminal event and fails with
     * {@link ProtoweftException} on a malformed or truncated stream. Cancelling the subscription
     * closes the HTTP stream at once, even while the agent sends nothing.
     */
    public Flow.Publisher<AgUiEvent> publisher(RunAgentInput input, Executor executor) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(executor, "executor");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            SubmissionPublisher<AgUiEvent> out = new SubmissionPublisher<>(executor, Flow.defaultBufferSize());
            Call call = call(input);
            out.subscribe(new CancellingSubscriber(subscriber, call));
            Thread reader = new Thread(() -> {
                try {
                    RunResult result = call.execute(event -> {
                        if (out.hasSubscribers()) {
                            out.submit(event);
                        }
                    });
                    if (result.truncated()) {
                        out.closeExceptionally(new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.AGUI,
                                "the stream ended without the run's outcome"));
                    } else {
                        out.close();
                    }
                } catch (RuntimeException e) {
                    out.closeExceptionally(e);
                }
            }, "protoweft-agui-reader");
            reader.setDaemon(true);
            reader.start();
        };
    }

    /** Passes everything through, and makes {@code cancel()} also close the run's stream. */
    private record CancellingSubscriber(Flow.Subscriber<? super AgUiEvent> delegate, Call call)
            implements Flow.Subscriber<AgUiEvent> {

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscription.request(n);
                }

                @Override
                public void cancel() {
                    subscription.cancel();
                    call.cancel();
                }
            });
        }

        @Override
        public void onNext(AgUiEvent item) {
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    Consumer<String> warningSink() {
        return warnings;
    }

    /** One run in flight. */
    public final class Call {
        private final RunAgentInput input;
        private volatile InputStream stream;
        private volatile boolean cancelled;

        private Call(RunAgentInput input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        /** Stops reading: the run becomes {@link RunResult#truncated() truncated}; the agent sees a disconnect. */
        public void cancel() {
            cancelled = true;
            InputStream s = stream;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Closing is best effort.
                }
            }
        }

        /** Runs to the end of the stream on the calling thread. */
        public RunResult execute(Consumer<AgUiEvent> listener) {
            List<String> found = new ArrayList<>();
            byte[] body = Json.writeBytes(Codec.input(outgoing(input, found)));
            HttpResponse<InputStream> resp;
            try {
                resp = send(endpoint, body, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.AGUI,
                        "POST " + endpoint + " failed: " + e.getMessage(), e);
            }
            stream = resp.body();
            if (cancelled) {
                cancel();
            }
            try (InputStream in = resp.body()) {
                checkResponse(resp, in);
                return read(source(resp, new BufferedInputStream(in)), listener, found);
            } catch (IOException e) {
                if (cancelled) {
                    return truncated(found);
                }
                throw new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.AGUI,
                        "reading the event stream failed: " + e.getMessage(), e);
            }
        }

        private RunResult read(Source source, Consumer<AgUiEvent> listener, List<String> found) throws IOException {
            ConsumerPipeline pipeline = new ConsumerPipeline(found);
            RunFinished finished = null;
            RunError error = null;
            List<String> unanswered = List.of();
            boolean closed = false;
            int reported = 0;
            while (true) {
                List<AgUiEvent> events;
                try {
                    events = source.next(pipeline);
                    if (events == null) {
                        break;
                    }
                } catch (AgUiFormatException e) {
                    throw new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.AGUI,
                            "the agent sent a malformed stream: " + e.getMessage(), e);
                }
                reported = report(found, reported);
                for (AgUiEvent e : events) {
                    if (e instanceof RunStarted) {
                        finished = null;
                        error = null;
                        closed = false;
                    } else if (e instanceof RunFinished f) {
                        unanswered = pipeline.unansweredToolCalls();
                        finished = f;
                        closed = true;
                    } else if (e instanceof RunError r) {
                        error = r;
                        closed = true;
                    }
                    listener.accept(e);
                }
            }
            if (!closed) {
                return truncated(found);
            }
            return new RunResult(finished, error, false, unanswered, found);
        }

        private int report(List<String> found, int reported) {
            for (int i = reported; i < found.size(); i++) {
                warnings.accept(found.get(i));
            }
            return found.size();
        }

        private RunResult truncated(List<String> found) {
            return new RunResult(null, null, true, List.of(), found);
        }
    }

    /** One binding's reader: the events of its next frame, or {@code null} at the end of the stream. */
    private interface Source {
        List<AgUiEvent> next(ConsumerPipeline pipeline) throws IOException;
    }

    /** The reader for the binding the response declares: the consumer selects its parser by Content-Type. */
    private Source source(HttpResponse<InputStream> resp, InputStream in) {
        if (protobufResponse(resp)) {
            ProtoBinding.require("Reading the agent's " + AgUiProtocol.EVENT_PROTOBUF + " response");
            ProtoBinding.FrameReader frames = new ProtoBinding.FrameReader(in, maxEventBytes);
            return pipeline -> {
                byte[] frame = frames.next();
                return frame == null ? null : pipeline.acceptFrame(frame);
            };
        }
        SseReader sse = new SseReader(in, maxEventBytes);
        return pipeline -> {
            String data = sse.next();
            return data == null ? null : pipeline.accept(data);
        };
    }

    private static String mediaType(HttpResponse<?> resp) {
        return resp.headers().firstValue("Content-Type").orElse("").split(";")[0].trim().toLowerCase();
    }

    private static boolean protobufResponse(HttpResponse<?> resp) {
        return mediaType(resp).equals(AgUiProtocol.EVENT_PROTOBUF);
    }

    /** Applies the outgoing rules: activity messages stripped, the protocol version declared. */
    private static RunAgentInput outgoing(RunAgentInput in, List<String> found) {
        List<AgUiMessage> messages = in.messages().stream().filter(m -> !(m instanceof ActivityMessage)).toList();
        return new RunAgentInput(in.threadId(), in.runId(),
                in.protocolVersion() == null ? AgUiProtocol.VERSION : in.protocolVersion(), in.parentRunId(),
                in.state(), messages, in.tools(), in.context(), in.forwardedProps(), in.resume());
    }

    private void checkResponse(HttpResponse<InputStream> resp, InputStream in) throws IOException {
        int status = resp.statusCode();
        if (status == 200 && (mediaType(resp).equals(AgUiProtocol.EVENT_STREAM) || protobufResponse(resp))) {
            return;
        }
        String detail = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
        ErrorCategory category = status == 401 || status == 403 ? ErrorCategory.AUTHENTICATION
                : status == 400 || status == 413 || status == 415 ? ErrorCategory.INVALID_ARGUMENTS
                : ErrorCategory.PROTOCOL;
        throw new ProtoweftException(category, ProtocolId.AGUI, String.valueOf(status),
                "the agent refused the run (HTTP " + status + "): " + detail, null);
    }

    private <T> HttpResponse<T> send(URI uri, byte[] body, HttpResponse.BodyHandler<T> handler) throws IOException {
        boolean reauthorized = false;
        while (true) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(responseTimeout);
            if (body == null) {
                b.header("Accept", "application/json").GET();
            } else {
                b.header("Content-Type", "application/json").header("Accept", preferProtobuf
                                ? AgUiProtocol.EVENT_PROTOBUF + ", " + AgUiProtocol.EVENT_STREAM : AgUiProtocol.EVENT_STREAM)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            }
            for (Credential credential : credentials.credentialsFor(ProtocolId.AGUI, endpoint.toString())) {
                if (credential instanceof Credential.BearerToken bearer) {
                    b.setHeader("Authorization", "Bearer " + bearer.token());
                } else if (credential instanceof Credential.NamedSecret secret) {
                    b.setHeader(secret.name(), secret.value());
                }
            }
            if (authorizer != null) {
                authorizer.authorization(uri).ifPresent(value -> b.setHeader("Authorization", value));
            }
            HttpResponse<T> resp;
            try {
                resp = http.send(b.build(), handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            int status = resp.statusCode();
            if ((status == 401 || status == 403) && authorizer != null && !reauthorized
                    && authorizer.onChallenge(uri, status, resp.headers().firstValue("WWW-Authenticate").orElse(null))) {
                reauthorized = true;
                if (resp.body() instanceof InputStream s) {
                    s.close();
                }
                continue;
            }
            return resp;
        }
    }

    public static final class Builder {
        private final URI endpoint;
        private HttpClient http;
        private CredentialProvider credentials = CredentialProvider.NONE;
        private HttpAuthorizer authorizer;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(60);
        private int maxEventBytes = 8 * 1024 * 1024;
        private Consumer<String> warnings = w -> LOG.log(System.Logger.Level.WARNING, "AG-UI: " + w);
        private boolean preferProtobuf;

        private static final System.Logger LOG = System.getLogger(AgUiClient.class.getName());

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("the agent endpoint must be an http(s) URL: " + endpoint);
            }
        }

        public Builder credentials(CredentialProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        /** Per-request authorization that can react to a 401/403 challenge (for example OAuth). */
        public Builder authorizer(HttpAuthorizer authorizer) {
            this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
            return this;
        }

        /** A custom HTTP client (redirects should stay off: a run must not be replayed elsewhere). */
        public Builder httpClient(HttpClient http) {
            this.http = Objects.requireNonNull(http, "http");
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** How long to wait for the response to start; the stream itself may run longer. Default 60 s. */
        public Builder responseTimeout(Duration timeout) {
            this.responseTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Largest accepted single event; larger fails the stream. Default 8 MiB. */
        public Builder maxEventBytes(int bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("maxEventBytes must be positive");
            }
            this.maxEventBytes = bytes;
            return this;
        }

        /** Receives each warning (dropped or stripped material); default the JDK logger. */
        public Builder warnings(Consumer<String> warnings) {
            this.warnings = Objects.requireNonNull(warnings, "warnings");
            return this;
        }

        /**
         * Asks agents for the HTTP + Protobuf binding (smaller frames) by listing
         * {@value AgUiProtocol#EVENT_PROTOBUF} before {@code text/event-stream} in {@code Accept}.
         * Default off: SSE is lossless, while the binary wire drops what its schema does not model
         * (unknown fields, JSON Patch extension members) and carries numbers inside open JSON as
         * doubles. Either way the client reads whichever binding the agent answers with. Needs
         * protobuf-java 4.36.2 or newer on the classpath; {@link #build()} fails without it.
         */
        public Builder preferProtobuf(boolean prefer) {
            this.preferProtobuf = prefer;
            return this;
        }

        public AgUiClient build() {
            return new AgUiClient(this);
        }
    }
}
