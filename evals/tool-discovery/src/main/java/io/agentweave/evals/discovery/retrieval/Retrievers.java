package io.agentweave.evals.discovery.retrieval;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/** Resolves retriever names: built-ins first, then {@link RetrieverFactory} services. */
public final class Retrievers {

    private Retrievers() {
    }

    public static Retriever create(String name, Map<String, String> options) {
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        switch (name) {
            case "random":
                return new RandomRetriever(seed);
            case "oracle":
                return new OracleRetriever();
            default:
                for (RetrieverFactory f : ServiceLoader.load(RetrieverFactory.class)) {
                    if (f.names().contains(name)) {
                        return f.create(name, options);
                    }
                }
                throw new IllegalArgumentException("unknown retriever '" + name + "'; available: " + available());
        }
    }

    public static Set<String> available() {
        Set<String> names = new TreeSet<>(Set.of("random", "oracle"));
        ServiceLoader.load(RetrieverFactory.class).forEach(f -> names.addAll(f.names()));
        return names;
    }
}
