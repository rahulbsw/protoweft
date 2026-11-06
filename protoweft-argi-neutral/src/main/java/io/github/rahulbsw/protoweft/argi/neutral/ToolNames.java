package io.github.rahulbsw.protoweft.argi.neutral;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Maps Protoweft tool names ({@code <source>.<tool>} in a registry or catalog) onto the names model
 * providers accept ({@code ^[a-zA-Z0-9_-]{1,64}$}).
 *
 * <p>The algorithm is the one {@code io.github.rahulbsw.protoweft.discovery.ProviderToolNames} uses (copied, because
 * discovery is optional here), so a catalog capability gets the same name in an ARGI registry as in
 * Protoweft's own provider payloads. Names are never parsed back: each ARGI tool is bound to its
 * original name.
 */
public final class ToolNames {

    /** Names every supported model provider accepts. */
    public static final Pattern PROVIDER_SAFE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static final int MAX = 64;
    private static final int HASH_LENGTH = 8;

    private ToolNames() {
    }

    /**
     * Returns a provider-safe name: {@code .} becomes {@code __}, any other disallowed character
     * becomes {@code _}, and a result longer than 64 characters is cut to 55 and suffixed with
     * {@code _} plus the first 8 hex digits of the original name's SHA-256. Safe names are unchanged.
     */
    public static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("tool name must not be empty");
        }
        if (PROVIDER_SAFE.matcher(name).matches()) {
            return name;
        }
        String s = name.replace(".", "__").replaceAll("[^a-zA-Z0-9_-]", "_");
        if (s.length() > MAX) {
            s = s.substring(0, MAX - HASH_LENGTH - 1) + "_" + sha256(name).substring(0, HASH_LENGTH);
        }
        return s;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
