package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.HistoryPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.ResultPolicy;

import java.util.Map;
import java.util.Set;

/**
 * Registers the discovery arms (ServiceLoader): {@code generic-search}, {@code native-anthropic}
 * and {@code native-openai}. Common options: {@code retriever} (a {@code catalog-*} retriever,
 * default {@code catalog-hybrid}), {@code fallback} (default {@code progressive}), plus the
 * retriever options ({@code embedding-cache}, {@code min-cosine}). See each arm for its own.
 */
public final class DiscoveryArmFactory implements ArmFactory {

    public static final String GENERIC = "generic-search";
    public static final String NATIVE_ANTHROPIC = "native-anthropic";
    public static final String NATIVE_OPENAI = "native-openai";

    @Override
    public Set<String> kinds() {
        return Set.of(GENERIC, NATIVE_ANTHROPIC, NATIVE_OPENAI);
    }

    @Override
    public Arm create(String kind, Map<String, String> options, TokenEstimator estimator, MultiTurn multiTurn) {
        int maxOut = Integer.parseInt(options.getOrDefault("maxOutputTokens", "4096"));
        int maxTurns = Integer.parseInt(options.getOrDefault("maxTurns", "6"));
        ResultPolicy resultPolicy = ResultPolicy.parse(options.getOrDefault("result", "raw"));
        HistoryPolicy historyPolicy = HistoryPolicy.parse(options.getOrDefault("history", "full"));
        return switch (kind) {
            case GENERIC -> new GenericSearchArm(DiscoveryArmSupport.retriever(options),
                    Integer.parseInt(options.getOrDefault("inlineSchemas", "0")),
                    Boolean.parseBoolean(options.getOrDefault("load", "false")),
                    Integer.parseInt(options.getOrDefault("limit",
                            String.valueOf(io.github.rahulbsw.protoweft.discovery.DiscoveryTools.DEFAULT_LIMIT))),
                    maxOut, maxTurns, estimator, multiTurn, resultPolicy, historyPolicy);
            case NATIVE_ANTHROPIC -> new NativeAnthropicArm(DiscoveryArmSupport.retriever(options), maxOut, maxTurns,
                    estimator);
            case NATIVE_OPENAI -> new NativeOpenAiArm(DiscoveryArmSupport.retriever(options),
                    options.getOrDefault("deferred", "none"), maxOut, maxTurns, estimator);
            default -> throw new IllegalArgumentException("unknown discovery arm: " + kind);
        };
    }
}
