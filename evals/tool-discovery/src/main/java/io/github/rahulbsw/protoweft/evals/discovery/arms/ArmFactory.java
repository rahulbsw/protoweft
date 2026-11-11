package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;

import java.util.Map;
import java.util.Set;

/**
 * Service-provider hook for arms, discovered with {@link java.util.ServiceLoader} from
 * {@code META-INF/services/io.github.rahulbsw.protoweft.evals.discovery.arms.ArmFactory}. The discovery
 * integration registers the generic search-tool arm and the native provider arms here.
 */
public interface ArmFactory {

    /** Arm kinds this factory builds, e.g. {@code search-tool}, {@code native}. */
    Set<String> kinds();

    /**
     * Builds an arm.
     *
     * @param kind      one of {@link #kinds()}
     * @param options   parsed from the arm spec {@code kind:key=value,key=value}, plus
     *                  {@code maxOutputTokens}, {@code maxTurns} and {@code seed}; result/history
     *                  shaping rides in as {@code result=}/{@code history=}
     * @param multiTurn the run-wide multi-turn context, or {@code null} for single-turn
     */
    Arm create(String kind, Map<String, String> options, TokenEstimator estimator, MultiTurn multiTurn);
}
