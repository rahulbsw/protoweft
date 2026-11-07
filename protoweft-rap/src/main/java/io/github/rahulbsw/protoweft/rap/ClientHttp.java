package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** The RAP client's outgoing HTTP: credentials, timeouts and 5xx retries. */
final class ClientHttp {

    record Response(int status, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpClient client;
    private final Duration requestTimeout;
    private final CredentialProvider credentials;
    private final HttpAuthorizer authorizer;

    ClientHttp(HttpClient client, Duration requestTimeout, CredentialProvider credentials, HttpAuthorizer authorizer) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.credentials = credentials;
        this.authorizer = authorizer;
    }

    /** One GET, with one retry after an authorizer obtained new credentials. */
    Response get(URI uri, String serverUrl) {
        return send("GET", uri, null, serverUrl, RapRetryPolicy.NONE);
    }

    /**
     * POSTs JSON, retrying connection failures and 5xx per {@code retry}; never 4xx (except once
     * after the authorizer obtained new credentials for a 401/403).
     */
    Response post(URI uri, Object json, String serverUrl, RapRetryPolicy retry) {
        return send("POST", uri, Json.writeBytes(json), serverUrl, retry);
    }

    private Response send(String method, URI uri, byte[] body, String serverUrl, RapRetryPolicy retry) {
        boolean reauthorized = false;
        int attempt = 1;
        while (true) {
            HttpRequest request = request(method, uri, body, serverUrl);
            Response response;
            try {
                HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                response = new Response(r.statusCode(), r.body());
                if ((r.statusCode() == 401 || r.statusCode() == 403) && authorizer != null && !reauthorized
                        && authorizer.onChallenge(uri, r.statusCode(),
                        r.headers().firstValue("WWW-Authenticate").orElse(null))) {
                    reauthorized = true;
                    continue;
                }
            } catch (HttpTimeoutException e) {
                if (attempt >= retry.maxAttempts()) {
                    throw new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.RAP,
                            method + " " + uri + " timed out after " + requestTimeout, e);
                }
                response = null;
            } catch (IOException e) {
                if (attempt >= retry.maxAttempts()) {
                    throw new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.RAP,
                            method + " " + uri + " failed: " + e.getMessage(), e);
                }
                response = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.RAP, method + " " + uri
                        + " interrupted", e);
            }
            if (response != null && (response.status() < 500 || attempt >= retry.maxAttempts())) {
                return response;
            }
            sleep(retry.delayBefore(attempt));
            attempt++;
        }
    }

    private HttpRequest request(String method, URI uri, byte[] body, String serverUrl) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        String target = sameOrigin(uri, URI.create(serverUrl)) ? serverUrl : uri.toString();
        for (Credential credential : credentials.credentialsFor(ProtocolId.RAP, target)) {
            if (credential instanceof Credential.BearerToken bearer) {
                b.setHeader("Authorization", "Bearer " + bearer.token());
            } else if (credential instanceof Credential.NamedSecret secret) {
                b.setHeader(secret.name(), secret.value());
            }
        }
        if (authorizer != null) {
            authorizer.authorization(uri).ifPresent(value -> b.setHeader("Authorization", value));
        }
        return b.build();
    }

    static boolean sameOrigin(URI a, URI b) {
        return Objects.equals(lower(a.getScheme()), lower(b.getScheme()))
                && Objects.equals(lower(a.getHost()), lower(b.getHost())) && port(a) == port(b);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    private static int port(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    static void sleep(Duration d) {
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.RAP, "interrupted while retrying", e);
        }
    }
}
