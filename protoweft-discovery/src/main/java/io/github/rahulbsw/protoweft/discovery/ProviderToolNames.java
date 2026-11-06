package io.github.rahulbsw.protoweft.discovery;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Reversible mapping between catalog names ({@code <source>.<tool>}) and the tool names model
 * providers accept ({@code ^[a-zA-Z0-9_-]{1,64}$} for both Anthropic and OpenAI). {@code .}
 * becomes {@code __}, other characters become {@code _}, and over-long names are shortened
 * with a hash. Two catalog names that would map to the same provider name are rejected.
 */
public final class ProviderToolNames {

    public static final Pattern PROVIDER_SAFE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final Map<String, String> toProvider = new ConcurrentHashMap<>();
    private final Map<String, String> toCatalog = new ConcurrentHashMap<>();

    /** The provider-safe name for a catalog name. */
    public synchronized String providerName(String catalogName) {
        String existing = toProvider.get(catalogName);
        if (existing != null) {
            return existing;
        }
        String mapped = sanitize(catalogName);
        String owner = toCatalog.putIfAbsent(mapped, catalogName);
        if (owner != null && !owner.equals(catalogName)) {
            throw new IllegalStateException("tool names '" + owner + "' and '" + catalogName
                    + "' both map to provider name '" + mapped + "'");
        }
        toProvider.put(catalogName, mapped);
        return mapped;
    }

    /** The catalog name for a provider name produced by this mapping. */
    public Optional<String> catalogName(String providerName) {
        return Optional.ofNullable(toCatalog.get(providerName));
    }

    static String sanitize(String name) {
        String s = name.replace(".", "__").replaceAll("[^a-zA-Z0-9_-]", "_");
        if (s.isEmpty()) {
            s = "_";
        }
        if (s.length() > 64) {
            String hash = CanonicalJson.sha256(name).substring(0, 8);
            s = s.substring(0, 55) + "_" + hash;
        }
        return s;
    }
}
