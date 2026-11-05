package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * One HTTP exchange with every redirect hop re-validated ({@code safe_request_with_redirects} in
 * {@code utcp_http/_security.py}) and the reference error-message format ({@code _errors.py}).
 */
final class HttpExchange {

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_DETAIL_CHARS = 2000;
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f]+");

    /**
     * @param query            query parameters appended to the URL (kept separate, as aiohttp
     *                         {@code params}, so a cross-origin redirect can drop them)
     * @param extraAuthHeaders header names that carry a secret beyond the built-in list
     */
    record Request(String method, String url, List<Map.Entry<String, String>> query, Map<String, String> headers,
                   byte[] body, Set<String> extraAuthHeaders) {
    }

    record Response(int status, String finalUrl, String contentType, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private HttpExchange() {
    }

    static Response send(HttpClient client, Request request, Duration timeout, String context) {
        String url = request.url();
        String method = request.method();
        Map<String, String> headers = new LinkedHashMap<>(request.headers());
        List<Map.Entry<String, String>> query = request.query();
        byte[] body = request.body();
        for (int hops = 0; ; hops++) {
            HttpResponse<byte[]> response = exchange(client, method, withQuery(url, query), headers, body, timeout, context);
            String location = response.headers().firstValue("Location").orElse(null);
            if (!REDIRECTS.contains(response.statusCode()) || location == null) {
                return new Response(response.statusCode(), url,
                        response.headers().firstValue("Content-Type").orElse(""), response.body());
            }
            if (hops >= MAX_REDIRECTS) {
                throw new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.UTCP, "Too many redirects (>"
                        + MAX_REDIRECTS + ") during " + context + " starting from '" + request.url() + "'.");
            }
            String next = toUri(url).resolve(location.trim()).toString();
            UrlSecurity.ensureSecure(next, context + " (redirect target)", ErrorCategory.PROTOCOL);
            if (UrlSecurity.isLoopbackUrl(next) && !UrlSecurity.isLoopbackUrl(url)) {
                throw new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.UTCP, "Security error during "
                        + context + " (redirect target): '" + url + "' redirected to the loopback address '" + next
                        + "'. A redirect is never followed into loopback from a non-loopback origin.");
            }
            if (!UrlSecurity.sameOrigin(url, next)) {
                headers.keySet().removeIf(h -> UrlSecurity.isAuthSensitiveHeader(h)
                        || request.extraAuthHeaders().contains(h.toLowerCase(Locale.ROOT)));
                query = List.of();
                body = null;
            }
            if (response.statusCode() == 303) {
                method = "GET";
                body = null;
            }
            url = next;
        }
    }

    private static HttpResponse<byte[]> exchange(HttpClient client, String method, String url, Map<String, String> headers,
                                                 byte[] body, Duration timeout, String context) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(toUri(url)).timeout(timeout)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.UTCP,
                    "cannot build the " + context + " request: " + e.getMessage(), e);
        }
        CompletableFuture<HttpResponse<byte[]>> future =
                client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentWeaveException(ErrorCategory.TIMEOUT, ProtocolId.UTCP,
                    context + " timed out after " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentWeaveException(ErrorCategory.INTERNAL, ProtocolId.UTCP, context + " interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new AgentWeaveException(ErrorCategory.TIMEOUT, ProtocolId.UTCP, context + " timed out", cause);
            }
            if (cause instanceof AgentWeaveException awe) {
                throw awe;
            }
            String detail = cause instanceof IOException && cause.getMessage() != null ? cause.getMessage()
                    : String.valueOf(cause);
            throw new AgentWeaveException(ErrorCategory.CONNECTION, ProtocolId.UTCP,
                    context + " failed for '" + url + "': " + detail, cause);
        }
    }

    static URI toUri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.UTCP, "invalid URL '" + url + "'", e);
        }
    }

    static String withQuery(String url, List<Map.Entry<String, String>> query) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder out = new StringBuilder(url).append(url.contains("?") ? '&' : '?');
        for (int i = 0; i < query.size(); i++) {
            Map.Entry<String, String> e = query.get(i);
            out.append(i == 0 ? "" : "&").append(formEncode(e.getKey())).append('=').append(formEncode(e.getValue()));
        }
        return out.toString();
    }

    static String formEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The reference error message: {@code "<reason>: <detail>"}, or just the reason without a body. */
    static String errorMessage(int status, byte[] body) {
        String text = new String(Arrays.copyOf(body, Math.min(body.length, MAX_ERROR_BODY_BYTES)), StandardCharsets.UTF_8);
        String detail = errorDetail(text);
        String reason = reasonPhrase(status);
        return detail != null ? reason + ": " + detail : reason;
    }

    /** First non-empty string of error/message/detail in a JSON object body; otherwise the raw body. */
    static String errorDetail(String text) {
        String body = text.strip();
        if (body.isEmpty()) {
            return null;
        }
        Object data;
        try {
            data = Json.parse(body);
        } catch (RuntimeException e) {
            return clean(body);
        }
        Map<String, Object> obj = Json.object(data);
        if (obj != null) {
            for (String key : List.of("error", "message", "detail")) {
                Object value = obj.get(key);
                if (value == null) {
                    continue;
                }
                if (value instanceof String s) {
                    if (!s.isBlank()) {
                        return clean(s);
                    }
                    continue;
                }
                return clean(body);
            }
        }
        return clean(body);
    }

    private static String clean(String text) {
        String s = CONTROL_CHARS.matcher(text).replaceAll(" ").strip();
        return s.length() > MAX_DETAIL_CHARS ? s.substring(0, MAX_DETAIL_CHARS) : s;
    }

    /** java.net.http does not expose the server's reason phrase, so the RFC 9110 phrase stands in. */
    static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Content";
            case 426 -> "Upgrade Required";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> "HTTP " + status;
        };
    }
}
