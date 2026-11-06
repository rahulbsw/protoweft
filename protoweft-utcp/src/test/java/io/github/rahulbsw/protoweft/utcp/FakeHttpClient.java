package io.github.rahulbsw.protoweft.utcp;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** An in-memory HttpClient standing in for remote (non-loopback) hosts that tests cannot bind. */
final class FakeHttpClient extends HttpClient {

    record Answer(int status, Map<String, List<String>> headers, String body) {
        static Answer json(String body) {
            return new Answer(200, Map.of("Content-Type", List.of("application/json")), body);
        }

        static Answer redirect(String location) {
            return new Answer(302, Map.of("Location", List.of(location)), "");
        }
    }

    final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    private final Function<HttpRequest, Answer> handler;

    FakeHttpClient(Function<HttpRequest, Answer> handler) {
        this.handler = handler;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        requests.add(request);
        Answer a = handler.apply(request);
        byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = new HttpResponse<>() {
            @Override
            public int statusCode() {
                return a.status();
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(a.headers(), (k, v) -> true);
            }

            @Override
            public byte[] body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }
        };
        return CompletableFuture.completedFuture((HttpResponse<T>) response);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> push) {
        return sendAsync(request, handler);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return sendAsync(request, handler).join();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
