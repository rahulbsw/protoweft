package io.agentweave.evals.discovery.arms;

import io.agentweave.evals.discovery.llm.TokenEstimator;
import io.agentweave.evals.discovery.retrieval.Retrievers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses arm specs {@code kind[:key=value,...]}: built-ins {@code all-tools} and
 * {@code top-k:k=5,retriever=oracle}, then {@link ArmFactory} services.
 */
public final class Arms {

    private Arms() {
    }

    public static Arm create(String spec, Map<String, String> defaults, TokenEstimator estimator) {
        int colon = spec.indexOf(':');
        String kind = colon < 0 ? spec.strip() : spec.substring(0, colon).strip();
        Map<String, String> options = new LinkedHashMap<>(defaults);
        if (colon >= 0) {
            for (String kv : spec.substring(colon + 1).split(",")) {
                String[] p = kv.split("=", 2);
                if (p.length != 2) {
                    throw new IllegalArgumentException("arm option must be key=value: " + kv + " in " + spec);
                }
                options.put(p[0].strip(), p[1].strip());
            }
        }
        int maxOut = Integer.parseInt(options.getOrDefault("maxOutputTokens", "4096"));
        switch (kind) {
            case "all-tools":
                return new AllToolsArm(maxOut, estimator);
            case "top-k":
                return new TopKInjectedArm(Retrievers.create(options.getOrDefault("retriever", "oracle"), options),
                        Integer.parseInt(options.getOrDefault("k", "5")), maxOut, estimator);
            default:
                for (ArmFactory f : ServiceLoader.load(ArmFactory.class)) {
                    if (f.kinds().contains(kind)) {
                        return f.create(kind, options, estimator);
                    }
                }
                throw new IllegalArgumentException("unknown arm '" + kind + "'; available: " + available());
        }
    }

    public static Set<String> available() {
        Set<String> kinds = new TreeSet<>(Set.of("all-tools", "top-k"));
        ServiceLoader.load(ArmFactory.class).forEach(f -> kinds.addAll(f.kinds()));
        return kinds;
    }
}
