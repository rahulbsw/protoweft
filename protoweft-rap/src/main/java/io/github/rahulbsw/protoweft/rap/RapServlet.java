package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.server.InboundAuthFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/** The tool server's routes, relative to its base path (lifecycle.md "Startup"). */
final class RapServlet extends HttpServlet {

    static final String TOOLSET = "/.well-known/rap-toolset";
    static final String INVOKE = "/invoke";
    static final String CLOSE_THREAD = "/close_thread";
    static final String CANCEL = "/cancel_tool_call";
    static final String CHOICE = "/user_choice_response";
    static final List<String> ROUTES = List.of(TOOLSET, INVOKE, CLOSE_THREAD, CANCEL, CHOICE);

    private final transient ServerState state;

    RapServlet(ServerState state) {
        this.state = state;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!route(req).equals(TOOLSET)) {
            error(res, ROUTES.contains(route(req)) ? 405 : 404, "not found");
            return;
        }
        try {
            write(res, 200, Json.write(manifest(baseUrl(req))));
        } catch (RuntimeException e) {
            error(res, 500, e.getMessage() != null ? e.getMessage() : "cannot build the toolset");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String route = route(req);
        if (!ROUTES.contains(route) || route.equals(TOOLSET)) {
            error(res, route.equals(TOOLSET) ? 405 : 404, "not found");
            return;
        }
        Map<String, Object> body;
        try {
            byte[] bytes = CallbackServlet.readLimited(req.getInputStream());
            if (bytes == null) {
                error(res, 413, "request body too large");
                return;
            }
            body = Json.parseObject(bytes);
        } catch (IllegalArgumentException e) {
            error(res, 400, e.getMessage());
            return;
        }
        try {
            switch (route) {
                case INVOKE -> invoke(req, res, body);
                case CLOSE_THREAD -> closeThread(res, body);
                case CANCEL -> cancel(res, body);
                default -> choice(res, body);
            }
        } catch (IllegalArgumentException e) {
            error(res, 400, e.getMessage());
        } catch (RejectedExecutionException e) {
            error(res, 503, "the server is not accepting work");
        } catch (IllegalStateException e) {
            error(res, 500, e.getMessage());
        }
    }

    /** The toolset definition (toolsets.md "Toolset Definition", "Tool Definition"). */
    Map<String, Object> manifest(String base) {
        List<Object> tools = new ArrayList<>();
        for (Map.Entry<String, ToolSpec> e : state.published().entrySet()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", e.getKey());
            tool.put("description", e.getValue().description());
            tool.put("inputSchema", e.getValue().inputSchema().isEmpty() ? Map.of("type", "object")
                    : e.getValue().inputSchema());
            Map<String, Object> annotations = Toolset.annotationsFor(e.getValue());
            if (annotations != null) {
                tool.put("annotations", annotations);
            }
            tools.add(tool);
        }
        if (tools.isEmpty()) {
            throw new IllegalStateException("a RAP toolset must contain at least one tool");
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", state.name);
        if (state.description != null) {
            manifest.put("description", state.description);
        }
        manifest.put("endpoint", base + INVOKE);
        manifest.put("tools", tools);
        return manifest;
    }

    /**
     * Acknowledges with 200, then runs the call (tool-invocation.md "Response", "Tool Validation").
     * Without a usable {@code callback_url}, {@code id} and {@code group_id} no error can be delivered,
     * so those are rejected with 400.
     */
    private void invoke(HttpServletRequest req, HttpServletResponse res, Map<String, Object> body) throws IOException {
        String id = Json.requireString(body, "id");
        String groupId = Json.requireString(body, "group_id");
        URI callback = Toolset.httpUrl(Json.requireString(body, "callback_url"), "callback_url");
        if (!state.policy.allows(callback)) {
            error(res, 400, "callback_url is not allowed by this server's callback policy");
            return;
        }
        String callId = body.get("call_id") instanceof String s ? s : null;
        String problem = null;
        Object op = body.get("operation");
        Object args = body.get("arguments");
        List<String> ancestors = new ArrayList<>();
        if (!(op instanceof String) || ((String) op).isEmpty()) {
            problem = "'operation' must be a non-empty string";
        } else if (args != null && Json.object(args) == null) {
            problem = "'arguments' must be an object";
        } else if (body.get("user_id") != null && !(body.get("user_id") instanceof String)) {
            problem = "'user_id' must be a string";
        } else if (body.get("thread_ancestors") != null) {
            List<Object> list = Json.array(body.get("thread_ancestors"));
            if (list == null || !list.stream().allMatch(String.class::isInstance)) {
                problem = "'thread_ancestors' must be an array of strings";
            } else {
                list.forEach(a -> ancestors.add((String) a));
            }
        }
        if (problem != null) {
            state.sender.queue(callback).send(ServerResults.errorResult(groupId, id, callId, problem));
            res.setStatus(200);
            return;
        }
        String operation = (String) op;
        ToolSpec spec = state.published().get(operation);
        Map<String, Object> arguments = args == null ? Map.of() : Json.object(args);
        ServerCall call = new ServerCall(state, new ServerCall.Invocation(operation, spec == null ? null : spec.name(),
                arguments, id, callId, callback, groupId, List.copyOf(ancestors),
                (String) body.get("user_id")), InboundAuthFilter.caller(req), baseUrl(req));
        if (state.running.putIfAbsent(call.key(), call) == null) {
            try {
                state.executor.execute(call::run);
            } catch (RejectedExecutionException e) {
                state.running.remove(call.key(), call);
                throw e;
            }
        }
        res.setStatus(200);
    }

    /** thread-closure.md "Response", "Resource Cleanup": 200 at once, subscriptions of the thread end in the background. */
    private void closeThread(HttpServletResponse res, Map<String, Object> body) {
        String threadId = Json.requireString(body, "thread_id");
        List<SubscriptionHandle> ending = state.subscriptions.values().stream()
                .filter(s -> s.threadId().equals(threadId)).toList();
        if (!ending.isEmpty()) {
            state.executor.execute(() -> ending.forEach(SubscriptionHandle::threadClosed));
        }
        res.setStatus(200);
    }

    /** tool-cancellation.md "Response", "Cancellation Behavior": always 200; unknown ids are ignored. */
    private void cancel(HttpServletResponse res, Map<String, Object> body) {
        String key = ServerState.key(Json.requireString(body, "thread_id"), Json.requireString(body, "tool_call_id"));
        ServerCall running = state.running.get(key);
        SubscriptionHandle sub = state.subscriptions.get(key);
        if (running != null || sub != null) {
            state.executor.execute(() -> {
                if (running != null) {
                    running.cancel();
                }
                if (sub != null) {
                    sub.cancelled();
                }
            });
        }
        res.setStatus(200);
    }

    /** user-choice.md "User Choice Response". */
    private void choice(HttpServletResponse res, Map<String, Object> body) {
        String id = Json.requireString(body, "id");
        int selected = Json.requireIndex(body, "selected");
        var pending = state.choices.get(id);
        if (pending != null) {
            pending.complete(selected);
        }
        res.setStatus(200);
    }

    private String route(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return path.startsWith(state.basePath) ? path.substring(state.basePath.length()) : path;
    }

    private String baseUrl(HttpServletRequest req) {
        String configured = state.baseUrl == null ? null : state.baseUrl.get();
        if (configured != null) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        String host = req.getServerName();
        host = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return req.getScheme() + "://" + host + ":" + req.getServerPort() + req.getContextPath() + state.basePath;
    }

    private static void error(HttpServletResponse res, int status, String message) throws IOException {
        write(res, status, Json.write(Map.of("error", message == null ? "" : message)));
    }

    private static void write(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(body);
    }
}
