package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.server.InboundAuthFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /utcp} (the manual) and {@code POST /utcp/tools/<tool>} (a call) for {@link UtcpManualProvider}. */
final class ManualServlet extends HttpServlet {

    private static final String TOOLS_PREFIX = "/tools/";

    private final transient UtcpManualProvider provider;

    ManualServlet(UtcpManualProvider provider) {
        this.provider = provider;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = path(req);
        if (!path.isEmpty() && !path.equals("/")) {
            error(res, 404, "not found: " + req.getRequestURI());
            return;
        }
        Map<String, Object> manual;
        try {
            manual = provider.manual(baseUrl(req));
        } catch (RuntimeException e) {
            error(res, 500, e.getMessage() != null ? e.getMessage() : "cannot build the manual");
            return;
        }
        write(res, 200, "application/json", Json.write(manual));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = path(req);
        if (!path.startsWith(TOOLS_PREFIX) || path.length() == TOOLS_PREFIX.length()
                || path.indexOf('/', TOOLS_PREFIX.length()) >= 0) {
            error(res, 404, "not found: " + req.getRequestURI());
            return;
        }
        String published = path.substring(TOOLS_PREFIX.length());
        Map<String, Object> arguments;
        try {
            arguments = arguments(req.getInputStream().readAllBytes());
        } catch (IllegalArgumentException e) {
            error(res, 400, e.getMessage());
            return;
        }
        try {
            ToolSpec spec = provider.published().get(published);
            if (spec == null) {
                error(res, 404, "Tool not found: " + published);
                return;
            }
            ToolOutcome outcome = provider.tools().invoke(ToolInvocation.of(spec.name(), arguments),
                    InboundAuthFilter.caller(req));
            if (outcome.error()) {
                String text = outcome.text();
                error(res, 500, text.isEmpty() ? "tool '" + published + "' failed" : text);
            } else {
                respond(res, outcome);
            }
        } catch (AgentWeaveException e) {
            error(res, status(e), e.getMessage());
        } catch (RuntimeException e) {
            error(res, 500, "tool '" + published + "' failed: " + e.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> arguments(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return Map.of();
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("request body is not valid JSON");
        }
        Map<String, Object> args = Json.object(parsed);
        if (args == null) {
            throw new IllegalArgumentException("request body must be a JSON object of tool arguments");
        }
        return args;
    }

    /** Structured content → JSON; text only → text/plain; other parts → a JSON array of parts. */
    private static void respond(HttpServletResponse res, ToolOutcome outcome) throws IOException {
        if (outcome.structured() != null) {
            write(res, 200, "application/json", Json.write(outcome.structured()));
            return;
        }
        if (outcome.content().stream().allMatch(Content.Text.class::isInstance)) {
            write(res, 200, "text/plain", outcome.text());
            return;
        }
        List<Object> parts = new ArrayList<>();
        for (Content c : outcome.content()) {
            Map<String, Object> part = new LinkedHashMap<>();
            if (c instanceof Content.Text t) {
                part.put("type", "text");
                part.put("text", t.text());
            } else if (c instanceof Content.Data d) {
                part.put("type", "data");
                part.put("mime_type", d.mimeType());
                part.put("data", d.base64Data());
                if (d.name() != null) {
                    part.put("name", d.name());
                }
            } else if (c instanceof Content.Json j) {
                part.put("type", "json");
                part.put("value", j.value());
            } else if (c instanceof Content.Link l) {
                part.put("type", "link");
                part.put("uri", l.uri());
                part.put("mime_type", l.mimeType());
                part.put("name", l.name());
            } else if (c instanceof Content.Resource r) {
                part.put("type", "resource");
                part.put("uri", r.uri());
                part.put("mime_type", r.mimeType());
                part.put(r.text() != null ? "text" : "data", r.text() != null ? r.text() : r.base64Blob());
            } else if (c instanceof Content.Unmapped u) {
                part.put("type", "unmapped");
                part.put("protocol_type", u.protocolType());
            }
            parts.add(part);
        }
        write(res, 200, "application/json", Json.write(parts));
    }

    private static int status(AgentWeaveException e) {
        return switch (e.category()) {
            case TOOL_NOT_FOUND -> 404;
            case INVALID_ARGUMENTS -> 400;
            case UNSUPPORTED -> 501;
            case CONNECTION -> 502;
            case TIMEOUT -> 504;
            default -> 500;
        };
    }

    private String baseUrl(HttpServletRequest req) {
        String configured = provider.configuredBaseUrl();
        if (configured != null) {
            return configured;
        }
        String host = req.getServerName();
        host = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return req.getScheme() + "://" + host + ":" + req.getServerPort() + req.getContextPath();
    }

    private static String path(HttpServletRequest req) {
        String uri = req.getRequestURI().substring(req.getContextPath().length());
        return uri.startsWith("/utcp") ? uri.substring("/utcp".length()) : uri;
    }

    private static void error(HttpServletResponse res, int status, String message) throws IOException {
        write(res, status, "application/json", Json.write(Map.of("error", message == null ? "" : message)));
    }

    private static void write(HttpServletResponse res, int status, String contentType, String body) throws IOException {
        res.setStatus(status);
        res.setContentType(contentType);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(body);
    }
}
