package io.github.rahulbsw.protoweft.core.server;

import io.github.rahulbsw.protoweft.core.ProtocolId;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A protocol-neutral view of an incoming request, given to an {@link InboundAuthenticator}.
 *
 * @param protocol      the protocol whose endpoint was hit
 * @param method        the HTTP method, for example {@code POST}
 * @param path          the request path, for example {@code /mcp}
 * @param headers       request headers; names are matched case-insensitively
 * @param remoteAddress the client address as seen by the server
 */
public record InboundRequest(ProtocolId protocol, String method, String path,
                             Map<String, List<String>> headers, String remoteAddress) {

    public InboundRequest {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        TreeMap<String, List<String>> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((k, v) -> ci.put(k, List.copyOf(v)));
        }
        headers = java.util.Collections.unmodifiableMap(ci);
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
    }

    /** The first value of a header, if present. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /** The token of an {@code Authorization: Bearer <token>} header, if present. */
    public Optional<String> bearerToken() {
        return header("Authorization")
                .filter(v -> v.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(v -> v.substring(7).trim());
    }
}
