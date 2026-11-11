package io.github.rahulbsw.protoweft.oauth;

import com.code_intelligence.jazzer.junit.FuzzTest;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Fuzzes the {@code WWW-Authenticate} header a resource server returns: {@link Challenge#parse}
 * never throws and yields only an absolute {@code resource_metadata} and a trimmed, non-blank
 * {@code scope}; and {@link OAuthClient#handleUnauthorized} (which starts discovery from the
 * challenge) answers any header with a {@link TokenResult} rather than an exception. The HTTP
 * client is offline: every request fails with an {@link IOException}, after the request was built.
 *
 * <p>Regression mode (the default) runs the seeds under {@code ChallengeFuzzTestInputs/}; fuzz with
 * {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class ChallengeFuzzTest {

    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");
    private static final HttpClient OFFLINE = new OfflineHttpClient();

    @FuzzTest(maxDuration = "2m")
    void wwwAuthenticate(byte[] input) {
        String header = new String(input, StandardCharsets.UTF_8);
        Challenge challenge = Challenge.parse(header);
        check(challenge.resourceMetadata() == null || challenge.resourceMetadata().isAbsolute(),
                "resource_metadata is absolute");
        check(challenge.scope() == null || (!challenge.scope().isBlank()
                && challenge.scope().equals(challenge.scope().trim())), "scope is trimmed and non-blank");
        challenge.insufficientScope();

        OAuthClient client = OAuthClient.builder().httpClient(OFFLINE).requestTimeout(Duration.ofSeconds(1))
                .browserLauncher(uri -> {
                    throw new IllegalStateException("an offline fuzz run reached the browser: " + uri);
                }).build();
        for (int status : new int[] {401, 403}) {
            TokenResult result = client.handleUnauthorized(RESOURCE, header, status);
            check(result != null && !result.shouldRetry(), "offline discovery gives up");
        }
    }


    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("violated: " + what);
        }
    }

    /** Builds nothing and connects nowhere: every exchange fails as an unreachable server would. */
    private static final class OfflineHttpClient extends HttpClient {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
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

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            throw new IOException("offline: " + request.uri());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new IOException("offline: " + request.uri()));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }
    }
}
