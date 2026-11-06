package io.github.rahulbsw.protoweft.evals.discovery.cost;

import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * USD prices per million tokens, keyed {@code provider:model}. The harness ships <b>no</b>
 * prices: the user supplies them (JSON file or {@code --price} flags) from the providers'
 * pricing pages, so reports never rest on guessed numbers. Without a price, a model's cost is
 * not reported (tokens only) and the cost guard refuses paid calls unless explicitly allowed.
 *
 * <p>File format: {@code {"anthropic:claude-sonnet-5-5": {"input": 0.0, "output": 0.0,
 * "cachedInput": 0.0, "cacheWrite": 0.0}}} — {@code cachedInput} and {@code cacheWrite} are
 * optional and default to {@code input} (an over-estimate, never an under-estimate).
 */
public final class PriceTable {

    /** Prices in USD per million tokens. */
    public record Price(double input, double output, double cachedInput, double cacheWrite) {

        public Price {
            if (input < 0 || output < 0 || cachedInput < 0 || cacheWrite < 0) {
                throw new IllegalArgumentException("prices must be non-negative");
            }
        }

        /** Cost of the usage in USD. */
        public double cost(Usage u) {
            return (u.uncachedInputTokens() * input + u.cachedInputTokens() * cachedInput
                    + u.cacheWriteTokens() * cacheWrite + u.outputTokens() * output) / 1_000_000.0;
        }

        /** Worst-case cost of a call: every input token uncached, the full output cap used. */
        public double worstCase(long inputTokens, long maxOutputTokens) {
            return (inputTokens * Math.max(input, cacheWrite) + maxOutputTokens * output) / 1_000_000.0;
        }
    }

    private final Map<String, Price> prices = new LinkedHashMap<>();

    public static PriceTable empty() {
        return new PriceTable();
    }

    /** Reads a price file (format in the class comment). */
    public static PriceTable load(Path file) {
        PriceTable t = new PriceTable();
        Json.asMap(Json.read(file)).forEach((key, v) -> {
            if (key.startsWith("_")) {
                return; // comment keys
            }
            Map<String, Object> m = Json.asMap(v);
            Double in = number(m.get("input"));
            Double out = number(m.get("output"));
            if (in == null || out == null) {
                throw new IllegalArgumentException("price for " + key + " needs numeric input and output (USD/Mtok)");
            }
            Double cached = number(m.get("cachedInput"));
            Double write = number(m.get("cacheWrite"));
            t.put(key, new Price(in, out, cached == null ? in : cached, write == null ? in : write));
        });
        return t;
    }

    /** Parses {@code provider:model=input,output[,cachedInput[,cacheWrite]]}. */
    public PriceTable putFlag(String flag) {
        int eq = flag.lastIndexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("--price expects provider:model=input,output[,cached[,cacheWrite]]: " + flag);
        }
        String[] parts = flag.substring(eq + 1).split(",");
        if (parts.length < 2) {
            throw new IllegalArgumentException("--price needs at least input,output: " + flag);
        }
        double in = Double.parseDouble(parts[0].strip());
        double out = Double.parseDouble(parts[1].strip());
        double cached = parts.length > 2 ? Double.parseDouble(parts[2].strip()) : in;
        double write = parts.length > 3 ? Double.parseDouble(parts[3].strip()) : in;
        return put(flag.substring(0, eq).strip(), new Price(in, out, cached, write));
    }

    public PriceTable put(String modelKey, Price price) {
        prices.put(modelKey, price);
        return this;
    }

    public Optional<Price> price(String modelKey) {
        return Optional.ofNullable(prices.get(modelKey));
    }

    public Map<String, Price> asMap() {
        return Map.copyOf(prices);
    }

    private static Double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
