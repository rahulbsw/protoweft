package io.github.rahulbsw.protoweft.evals.discovery.data;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Assigns provider-safe tool names ({@code ^[a-zA-Z0-9_-]{1,64}$}, accepted by both Anthropic
 * and OpenAI) to tool ids, reversibly and without collisions.
 *
 * <p>Rules: NFKD-normalize and drop combining marks; replace every other character outside
 * {@code [a-zA-Z0-9_-]} with {@code _}; collapse runs of {@code _}; trim leading/trailing
 * {@code _}; an empty result becomes {@code tool}; truncate to 64 characters. A name already
 * taken (compared case-insensitively, to stay safe for providers that fold case) gets the
 * suffix {@code _2}, {@code _3}, … with the base shortened so the whole name fits 64 characters.
 * Assignment is first-come: callers register tools in a deterministic order.
 */
public final class ToolNames {

    /** The provider-safe name pattern. */
    public static final Pattern SAFE = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final int MAX = 64;

    private final Map<String, String> idToName = new LinkedHashMap<>();
    private final Map<String, String> nameToId = new HashMap<>();
    private final Map<String, String> foldedTaken = new HashMap<>();

    /** Sanitizes one raw name (no dedupe). */
    public static String sanitize(String raw) {
        String s = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        if (s.isEmpty()) {
            s = "tool";
        }
        return s.length() > MAX ? s.substring(0, MAX) : s;
    }

    /**
     * Registers a tool id under a safe form of {@code rawName}; returns the assigned name.
     * Registering the same id again returns the name assigned the first time.
     */
    public String assign(String toolId, String rawName) {
        Objects.requireNonNull(toolId, "toolId");
        String existing = idToName.get(toolId);
        if (existing != null) {
            return existing;
        }
        String base = sanitize(rawName);
        String name = base;
        for (int n = 2; foldedTaken.containsKey(fold(name)); n++) {
            String suffix = "_" + n;
            String head = base.length() + suffix.length() > MAX ? base.substring(0, MAX - suffix.length()) : base;
            name = head + suffix;
        }
        idToName.put(toolId, name);
        nameToId.put(name, toolId);
        foldedTaken.put(fold(name), toolId);
        return name;
    }

    /** The provider-safe name of a registered tool id, or {@code null}. */
    public String nameOf(String toolId) {
        return idToName.get(toolId);
    }

    /** The tool id behind a provider-facing name, or {@code null} if the name is unknown. */
    public String idOf(String name) {
        return nameToId.get(name);
    }

    /** Tool id to provider-safe name, in registration order. */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(idToName);
    }

    private static String fold(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
