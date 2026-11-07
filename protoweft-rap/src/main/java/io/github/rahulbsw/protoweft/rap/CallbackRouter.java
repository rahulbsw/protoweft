package io.github.rahulbsw.protoweft.rap;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Routes callbacks by the token in their URL ({@code <base>/rap/callback/<token>}) to the
 * connector that issued it. Shared by {@link RapCallbackEndpoint} and {@link RapCallbackListener}.
 */
final class CallbackRouter {

    /** The path callbacks are posted under. */
    static final String PATH = "/rap/callback/";
    /** Largest accepted callback body; images arrive base64-encoded. */
    static final int MAX_BODY = 32 * 1024 * 1024;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    /** Handles one callback for a token; returns the HTTP status to answer with. */
    interface Target {
        int handle(String token, byte[] body);
    }

    private final Supplier<String> publicBaseUrl;
    private final Map<String, Target> routes = new ConcurrentHashMap<>();

    CallbackRouter(Supplier<String> publicBaseUrl) {
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
    }

    String callbackUrl(String token) {
        String base = publicBaseUrl.get();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("the RAP callback base URL is not known yet");
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + PATH + token;
    }

    void register(String token, Target target) {
        routes.put(token, target);
    }

    void unregister(String token) {
        routes.remove(token);
    }

    /**
     * @param path the request path after the context path, for example {@code /rap/callback/abc}
     * @return the HTTP status: 404 for an unknown or malformed token, else the target's answer
     */
    int dispatch(String path, byte[] body) {
        if (path == null || !path.startsWith(PATH)) {
            return 404;
        }
        String token = path.substring(PATH.length());
        if (!TOKEN.matcher(token).matches()) {
            return 404;
        }
        Target target = routes.get(token);
        return target == null ? 404 : target.handle(token, body);
    }
}
