package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Sends Nimbus-built OAuth messages and metadata GETs over an injectable {@link HttpClient}. */
final class Http {

    private static final Set<String> RESTRICTED = Set.of("content-length", "host", "connection", "expect", "upgrade");

    private final HttpClient client;
    private final Duration timeout;

    Http(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Sends a Nimbus request (token, registration) and adapts the reply back to a Nimbus response. */
    HTTPResponse send(HTTPRequest request) {
        HttpRequest.Builder b = HttpRequest.newBuilder(request.getURI()).timeout(timeout);
        for (Map.Entry<String, List<String>> h : request.getHeaderMap().entrySet()) {
            if (RESTRICTED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String v : h.getValue()) {
                b.header(h.getKey(), v);
            }
        }
        String body = request.getBody();
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        b.method(request.getMethod().name(), publisher);
        HttpResponse<String> reply = exchange(b.build());
        HTTPResponse out = new HTTPResponse(reply.statusCode());
        reply.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":")) {
                out.setHeader(name, values.toArray(String[]::new));
            }
        });
        out.setBody(reply.body());
        return out;
    }

    /** GETs a JSON object; empty on a non-2xx status, an exception on transport or JSON errors. */
    Optional<Map<String, Object>> getJson(URI uri) {
        Optional<String> body = getText(uri);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSONObjectUtils.parse(body.get()));
        } catch (ParseException e) {
            throw new ProtoweftException(ErrorCategory.PROTOCOL, null, "invalid JSON from " + uri, e);
        }
    }

    /** GETs a JSON document as text; empty on a non-2xx status. */
    Optional<String> getText(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> reply = exchange(request);
        if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
            return Optional.empty();
        }
        return Optional.of(reply.body());
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProtoweftException(ErrorCategory.CONNECTION, null,
                    "request to " + request.uri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtoweftException(ErrorCategory.CONNECTION, null, "interrupted calling " + request.uri(), e);
        }
    }
}
