package io.github.rahulbsw.protoweft.a2a;

import com.google.gson.JsonSyntaxException;
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
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.server.util.sse.SseFormatter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.HEADERS_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.METHOD_NAME_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.TENANT_KEY;

/**
 * {@code POST <path>}: JSON-RPC 2.0 requests of the A2A 1.0 JSON-RPC binding, answered as JSON
 * or, for streaming methods, as Server-Sent Events. Mirrors a2a-java's reference
 * {@code A2AServerRoutes} on the Servlet API.
 */
final class JsonRpcServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(JsonRpcServlet.class);

    private final transient SdkServer sdk;
    private final transient ObservationHandler observations;
    private final boolean extendedConfigured;

    JsonRpcServlet(SdkServer sdk, ObservationHandler observations, boolean extendedConfigured) {
        this.sdk = sdk;
        this.observations = observations;
        this.extendedConfigured = extendedConfigured;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CallerIdentity caller = InboundAuthFilter.caller(req);
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ServerCallContext context = callContext(req, caller);
        AsyncContext async = req.startAsync();
        async.setTimeout(0);
        // Blocking SendMessage waits for the agent; keep container threads free.
        try {
            sdk.events().execute(() -> {
                try {
                    handle(body, context, caller, async);
                } catch (RuntimeException | IOException e) {
                    LOG.debug("A2A request failed", e);
                    async.complete();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            resp.setStatus(503); // the server has been closed
            async.complete();
        }
    }

    private void handle(String body, ServerCallContext context, CallerIdentity caller, AsyncContext async)
            throws IOException {
        HttpServletResponse resp = (HttpServletResponse) async.getResponse();
        A2ARequest<?> request;
        try {
            request = org.a2aproject.sdk.grpc.utils.JSONRPCUtils.parseRequestBody(body, null);
        } catch (InvalidParamsJsonMappingException e) {
            writeJson(resp, async, new A2AErrorResponse(e.getId(), new InvalidParamsError(null, e.getMessage(), null)));
            return;
        } catch (MethodNotFoundJsonMappingException e) {
            writeJson(resp, async, new A2AErrorResponse(e.getId(), new MethodNotFoundError(null, e.getMessage(), null)));
            return;
        } catch (IdJsonMappingException e) {
            writeJson(resp, async, new A2AErrorResponse(e.getId(), new InvalidRequestError(null, e.getMessage(), null)));
            return;
        } catch (JsonMappingException e) {
            writeJson(resp, async, new A2AErrorResponse(new InvalidRequestError(null, e.getMessage(), null)));
            return;
        } catch (A2AError e) {
            writeJson(resp, async, new A2AErrorResponse(e));
            return;
        } catch (JsonSyntaxException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException
                 | IllegalStateException e) {
            writeJson(resp, async, new A2AErrorResponse(new JSONParseError(e.getMessage())));
            return;
        }
        String method = request.getMethod();
        context.getState().put(METHOD_NAME_KEY, method);
        // The extended card is for authenticated callers only: refuse anonymous ones like the auth filter does.
        if (request instanceof GetExtendedAgentCardRequest && extendedConfigured && caller.isAnonymous()) {
            resp.setStatus(401);
            resp.setHeader("WWW-Authenticate", "Bearer");
            resp.setContentType("application/json");
            resp.getOutputStream().write("{\"error\":\"authentication required for the extended agent card\"}"
                    .getBytes(StandardCharsets.UTF_8));
            async.complete();
            return;
        }
        ObservationHandler.observe(observations, new Observation(ProtocolId.A2A, method, ""), () -> {
            try {
                if (request instanceof NonStreamingJSONRPCRequest<?> plain) {
                    writeJson(resp, async, dispatch(plain, context));
                } else {
                    stream(request, context, resp, async);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });
    }

    private A2AResponse<?> dispatch(NonStreamingJSONRPCRequest<?> request, ServerCallContext context) {
        var h = sdk.jsonRpc();
        try {
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest r) {
                return h.onMessageSend(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest r) {
                return h.onGetTask(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest r) {
                return h.onCancelTask(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest r) {
                return h.onListTasks(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest r) {
                return h.setPushNotificationConfig(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest r) {
                return h.getPushNotificationConfig(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest r) {
                return h.listPushNotificationConfigs(r, context);
            }
            if (request instanceof org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest r) {
                return h.deletePushNotificationConfig(r, context);
            }
            if (request instanceof GetExtendedAgentCardRequest r) {
                return h.onGetExtendedCardRequest(r, context);
            }
            return new A2AErrorResponse(request.getId(), new org.a2aproject.sdk.spec.UnsupportedOperationError());
        } catch (A2AError e) {
            return new A2AErrorResponse(request.getId(), e);
        }
    }

    private void stream(A2ARequest<?> request, ServerCallContext context, HttpServletResponse resp, AsyncContext async)
            throws IOException {
        var h = sdk.jsonRpc();
        Flow.Publisher<SendStreamingMessageResponse> publisher;
        try {
            if (request instanceof SendStreamingMessageRequest r) {
                h.authorizeTaskAccess(r.getParams().message().taskId(), context, TaskOperation.MESSAGE_SEND_STREAM);
                publisher = h.onMessageSendStream(r, context);
            } else if (request instanceof SubscribeToTaskRequest r) {
                h.authorizeTaskAccess(r.getParams().id(), context, TaskOperation.SUBSCRIBE_TO_TASK);
                publisher = h.onSubscribeToTask(r, context);
            } else {
                writeJson(resp, async, new A2AErrorResponse(request.getId(),
                        new org.a2aproject.sdk.spec.UnsupportedOperationError()));
                return;
            }
        } catch (A2AError e) {
            writeJson(resp, async, new A2AErrorResponse(request.getId(), e));
            return;
        }
        resp.setStatus(200);
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        resp.flushBuffer();
        publisher.subscribe(new SseWriter(resp, async, context));
    }

    private static void writeJson(HttpServletResponse resp, AsyncContext async, A2AResponse<?> response) throws IOException {
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getOutputStream().write(serialize(response).getBytes(StandardCharsets.UTF_8));
        async.complete();
    }

    private static String serialize(A2AResponse<?> response) {
        if (response.getError() != null) {
            return org.a2aproject.sdk.grpc.utils.JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
        }
        return org.a2aproject.sdk.grpc.utils.JSONRPCUtils.toJsonRPCResultResponse(response.getId(), ProtoResults.toProto(response));
    }

    private static ServerCallContext callContext(HttpServletRequest req, CallerIdentity caller) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            headers.put(name, req.getHeader(name));
        }
        Map<String, Object> state = new HashMap<>();
        state.put(HEADERS_KEY, headers);
        state.put(TENANT_KEY, "");
        state.put(ServerCallContext.TRANSPORT_KEY, TransportProtocol.JSONRPC);
        state.put(HandlerExecutor.CALLER_KEY, caller);
        User user = caller.isAnonymous() ? UnauthenticatedUser.INSTANCE
                : new AuthenticatedUser(caller.name(), caller.attributes());
        Set<String> extensions = A2AExtensions.getRequestedExtensions(
                Collections.list(req.getHeaders(A2AHeaders.A2A_EXTENSIONS)));
        return new ServerCallContext(user, state, extensions, req.getHeader(A2AHeaders.A2A_VERSION));
    }

    /** Writes each streamed response as one SSE event; a client disconnect stops the SDK consumer. */
    private static final class SseWriter implements Flow.Subscriber<SendStreamingMessageResponse> {

        private final HttpServletResponse resp;
        private final AsyncContext async;
        private final ServerCallContext context;
        private final AtomicLong ids = new AtomicLong();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile Flow.Subscription subscription;

        SseWriter(HttpServletResponse resp, AsyncContext async, ServerCallContext context) {
            this.resp = resp;
            this.async = async;
            this.context = context;
            async.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    disconnect();
                }

                @Override
                public void onError(AsyncEvent event) {
                    disconnect();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                }
            });
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(1);
        }

        @Override
        public void onNext(SendStreamingMessageResponse item) {
            try {
                synchronized (this) {
                    resp.getOutputStream().write(SseFormatter.formatResponseAsSSE(item, ids.getAndIncrement())
                            .getBytes(StandardCharsets.UTF_8));
                    resp.flushBuffer();
                }
                subscription.request(1);
            } catch (IOException | RuntimeException e) {
                disconnect();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            LOG.debug("A2A stream failed", throwable);
            try {
                onNext(new SendStreamingMessageResponse(null, new InternalError("Internal error")));
            } finally {
                finish();
            }
        }

        @Override
        public void onComplete() {
            finish();
        }

        private void disconnect() {
            context.invokeEventConsumerCancelCallback();
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
            finish();
        }

        private void finish() {
            if (done.compareAndSet(false, true)) {
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                    // already completed by the container after a disconnect
                }
            }
        }
    }
}
