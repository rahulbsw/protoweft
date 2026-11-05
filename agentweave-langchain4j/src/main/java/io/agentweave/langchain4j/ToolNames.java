package io.agentweave.langchain4j;

import java.util.regex.Pattern;

/**
 * Maps AgentWeave tool names onto the names LLM providers accept ({@code ^[a-zA-Z0-9_-]{1,64}$},
 * the OpenAI limit; the strictest common one).
 *
 * <p>The mapping is not inverted by parsing: {@link AgentWeaveToolProvider} binds each sanitized
 * name to its original name, so the round trip is exact even when a name already contains
 * {@code __}.
 */
public final class ToolNames {

    /** Names every supported LLM provider accepts. */
    public static final Pattern LLM_SAFE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static final int MAX = 64;
    private static final int HASH_LENGTH = 8;

    private ToolNames() {
    }

    /**
     * Returns a provider-safe name: {@code .} (the {@code ToolRegistry} separator) becomes
     * {@code __}, any other disallowed character becomes {@code _}, and a name longer than 64
     * characters is cut to 55 and suffixed with {@code _} plus 8 hex digits of the original
     * name's hash. Names that are already safe are returned unchanged.
     */
    public static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("tool name must not be empty");
        }
        if (LLM_SAFE.matcher(name).matches()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                out.append("__");
            } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        if (out.length() <= MAX) {
            return out.toString();
        }
        String hash = String.format("%08x", name.hashCode());
        return out.substring(0, MAX - HASH_LENGTH - 1) + "_" + hash;
    }
}
