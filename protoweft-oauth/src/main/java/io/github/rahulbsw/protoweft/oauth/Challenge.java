package io.github.rahulbsw.protoweft.oauth;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code Bearer} challenge of a {@code WWW-Authenticate} header (RFC 6750 §3, RFC 9728 §5.1).
 *
 * @param resourceMetadata the {@code resource_metadata} URL, or {@code null}
 * @param scope            the {@code scope} parameter (space-delimited), or {@code null}
 * @param error            the {@code error} code, for example {@code insufficient_scope}, or {@code null}
 * @param errorDescription the {@code error_description}, or {@code null}
 */
public record Challenge(URI resourceMetadata, String scope, String error, String errorDescription) {

    /** No challenge: discovery falls back to the well-known URIs and scope selection to the metadata. */
    public static final Challenge NONE = new Challenge(null, null, null, null);

    public Challenge {
        scope = scope == null || scope.trim().isEmpty() ? null : scope.trim();
    }

    /**
     * Parses the Bearer challenge from a {@code WWW-Authenticate} value that may hold several
     * challenges ({@code Basic realm="x", Bearer scope="a b"}). Returns {@link #NONE} when the value
     * is {@code null} or has no Bearer challenge.
     */
    public static Challenge parse(String header) {
        if (header == null || header.isBlank()) {
            return NONE;
        }
        Map<String, String> bearer = null;
        Map<String, String> current = null;
        int i = 0;
        int n = header.length();
        while (i < n) {
            while (i < n && (header.charAt(i) == ',' || Character.isWhitespace(header.charAt(i)))) {
                i++;
            }
            int start = i;
            while (i < n && !isDelimiter(header.charAt(i))) {
                i++;
            }
            if (start == i) {
                i++;
                continue;
            }
            String token = header.substring(start, i);
            int j = i;
            while (j < n && Character.isWhitespace(header.charAt(j))) {
                j++;
            }
            if (j < n && header.charAt(j) == '=' && current != null) {
                j++;
                while (j < n && Character.isWhitespace(header.charAt(j))) {
                    j++;
                }
                StringBuilder value = new StringBuilder();
                if (j < n && header.charAt(j) == '"') {
                    j++;
                    while (j < n && header.charAt(j) != '"') {
                        if (header.charAt(j) == '\\' && j + 1 < n) {
                            j++;
                        }
                        value.append(header.charAt(j++));
                    }
                    j++;
                } else {
                    while (j < n && header.charAt(j) != ',' && !Character.isWhitespace(header.charAt(j))) {
                        value.append(header.charAt(j++));
                    }
                }
                current.putIfAbsent(token.toLowerCase(Locale.ROOT), value.toString());
                i = j;
            } else {
                // A new auth-scheme; anything else (token68) is skipped by the loop.
                current = new HashMap<>();
                if (token.equalsIgnoreCase("Bearer") && bearer == null) {
                    bearer = current;
                }
                i = j;
            }
        }
        if (bearer == null) {
            return NONE;
        }
        String rm = bearer.get("resource_metadata");
        URI resourceMetadata = rm == null ? null : Uris.parse(rm);
        if (resourceMetadata != null && !resourceMetadata.isAbsolute()) {
            resourceMetadata = null;
        }
        return new Challenge(resourceMetadata, bearer.get("scope"), bearer.get("error"), bearer.get("error_description"));
    }

    private static boolean isDelimiter(char c) {
        return c == ',' || c == '=' || c == '"' || Character.isWhitespace(c);
    }

    /** Whether the server reported {@code error="insufficient_scope"}. */
    public boolean insufficientScope() {
        return "insufficient_scope".equals(error);
    }
}
