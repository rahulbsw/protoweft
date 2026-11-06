package io.agentweave.evals.discovery.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds clients from {@code provider:model} specs. */
public final class ModelClients {

    /** Documented default: one mid-tier model per provider (see README for how they were chosen). */
    public static final String DEFAULT_MODELS = "anthropic:claude-sonnet-5-5,openai:gpt-6.1-sol";

    /** Environment variables (the SDKs' own names) that point a provider client at another endpoint, such as a proxy. */
    public static final String ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL";
    public static final String OPENAI_BASE_URL = "OPENAI_BASE_URL";

    private ModelClients() {
    }

    /** The base URL set in {@code envVar}, or {@code null} for the provider's default endpoint. */
    static String baseUrl(String envVar) {
        String v = System.getenv(envVar);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** The endpoint for the report header: scheme, host, port and path only, never user info or query. */
    static String describeBaseUrl(String envVar) {
        return describeUrl(baseUrl(envVar));
    }

    static String describeUrl(String v) {
        if (v == null) {
            return "provider default";
        }
        try {
            java.net.URI u = java.net.URI.create(v);
            return new java.net.URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return "custom (unparseable)";
        }
    }

    /**
     * Model settings; {@code null} means "provider default" (the parameter is not sent).
     *
     * @param anthropicCacheTools prompt-cache the tool block on Anthropic (see {@link AnthropicModelClient.Options})
     * @param countTokens wrap Anthropic clients in a {@link TokenCountingClient} using {@code count_tokens}
     */
    public record Settings(Double temperature, String anthropicEffort, String anthropicThinking,
                           boolean anthropicCacheTools, String openaiReasoningEffort, boolean countTokens) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("temperature", temperature == null ? "provider default (not sent)" : temperature);
            m.put("anthropicEffort", anthropicEffort == null ? "default" : anthropicEffort);
            m.put("anthropicThinking", anthropicThinking == null ? "default" : anthropicThinking);
            m.put("anthropicCacheTools", anthropicCacheTools);
            m.put("openaiReasoningEffort", openaiReasoningEffort == null ? "default" : openaiReasoningEffort);
            m.put("countTokens", countTokens);
            m.put("anthropicBaseUrl", describeBaseUrl(ANTHROPIC_BASE_URL));
            m.put("openaiBaseUrl", describeBaseUrl(OPENAI_BASE_URL));
            return m;
        }
    }

    /** Splits {@code provider:model}. */
    public static String[] parse(String spec) {
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) {
            throw new IllegalArgumentException("model must be provider:model, e.g. anthropic:claude-sonnet-5-5: " + spec);
        }
        return new String[] {spec.substring(0, colon), spec.substring(colon + 1)};
    }

    /** A real client using the provider's API key from the environment. */
    public static ModelClient create(String spec, Settings s) {
        String[] pm = parse(spec);
        switch (pm[0]) {
            case "anthropic": {
                AnthropicModelClient c = AnthropicModelClient.fromEnv(new AnthropicModelClient.Options(pm[1],
                        s.temperature(), s.anthropicEffort(), s.anthropicThinking(), s.anthropicCacheTools()));
                if (s.countTokens()) {
                    AnthropicTokenCounter counter = new AnthropicTokenCounter(c.sdk(), pm[1]);
                    return new TokenCountingClient(c, counter::count);
                }
                return c;
            }
            case "openai":
                return OpenAiModelClient.fromEnv(new OpenAiModelClient.Options(pm[1], s.temperature(),
                        s.openaiReasoningEffort()));
            default:
                throw new IllegalArgumentException("unknown provider '" + pm[0] + "' (anthropic or openai)");
        }
    }

    /** A dry-run client (no network). */
    public static ModelClient estimating(String spec, TokenEstimator estimator) {
        String[] pm = parse(spec);
        return new EstimatingModelClient(pm[0], pm[1], estimator);
    }
}
