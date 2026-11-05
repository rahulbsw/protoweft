package io.agentweave.evals.discovery.calibration;

import io.agentweave.discovery.Confidence;
import io.agentweave.discovery.Confidence.Level;
import io.agentweave.discovery.Confidence.Policy;
import io.agentweave.discovery.DiscoveryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Confidence calibration: how {@link Confidence} levels relate to whether retrieval actually
 * succeeded, and a grid search for {@link Policy} thresholds. Suggestions are reported, never
 * applied.
 *
 * <p>{@link #level} re-derives a level from a search's recorded signals under any policy; it
 * mirrors {@code Confidence.Policy.assess} in {@code agentweave-discovery} (keyword-only: coverage
 * and margin; hybrid: coverage, top cosine and keyword/vector agreement), which is checked against
 * the catalog's own levels in the tests.
 *
 * <p>The objective, on the dev split: precision of HIGH (gold at rank 1 when HIGH) plus recall of
 * LOW among misses (LOW when no gold is in the top 5, so the fallback would trigger), subject to
 * HIGH covering at least {@code minHighShare} of queries and LOW firing on at most
 * {@code maxFalseAlarm} of the queries that did hit in the top 5. Ties prefer more HIGH, then the
 * policy closest to the default.
 */
public final class Calibration {

    /**
     * One search's confidence signals and outcome.
     *
     * @param semantic  ranking used vectors
     * @param empty     the search returned no hits
     * @param reported  the level the catalog reported (under the policy it was built with)
     * @param hit1      a gold tool is ranked first
     * @param hit5      a gold tool is in the top 5
     */
    public record Signals(boolean semantic, boolean empty, double coverage, double margin, boolean agreement,
                          double cosine, Level reported, boolean hit1, boolean hit5) {

        public static Signals of(DiscoveryResult r, boolean hit1, boolean hit5) {
            Confidence c = r.confidence();
            return new Signals(r.mode() == DiscoveryResult.Mode.HYBRID, r.hits().isEmpty(), c.coverage(), c.margin(),
                    c.agreement(), c.cosine(), c.level(), hit1, hit5);
        }
    }

    /** Level counts and hit rates of one policy over a set of searches. */
    public record Metrics(int n, Map<Level, Integer> counts, Map<Level, Double> hit1Rate, Map<Level, Double> hit5Rate,
                          double highShare, double precisionHigh, double lowRecallOfMisses, double lowFalseAlarm) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("queries", n);
            Map<String, Object> levels = new LinkedHashMap<>();
            for (Level l : Level.values()) {
                levels.put(l.name(), Map.of("count", counts.get(l), "hit@1", nan(hit1Rate.get(l)),
                        "hit@5", nan(hit5Rate.get(l))));
            }
            m.put("levels", levels);
            m.put("highShare", nan(highShare));
            m.put("precisionHigh(hit@1|HIGH)", nan(precisionHigh));
            m.put("lowRecallOfMisses(LOW|miss@5)", nan(lowRecallOfMisses));
            m.put("lowFalseAlarm(LOW|hit@5)", nan(lowFalseAlarm));
            return m;
        }

        private static Object nan(double d) {
            return Double.isNaN(d) ? "NaN" : d;
        }
    }

    /** The search outcome for one retriever. */
    public record Suggestion(Policy defaultPolicy, Policy policy, boolean feasible, int gridSize, double objective, Metrics defaultDev,
                             Metrics suggestedDev, Metrics defaultTest, Metrics suggestedTest) {
    }

    private Calibration() {
    }

    /** The level {@code policy} gives these signals; mirrors {@code Confidence.Policy.assess}. */
    public static Level level(Policy p, Signals s) {
        if (s.empty()) {
            return Level.LOW;
        }
        if (!s.semantic()) {
            if (s.coverage() < p.lowCoverage()) {
                return Level.LOW;
            }
            return s.coverage() >= p.highCoverage() && s.margin() >= p.minMargin() ? Level.HIGH : Level.MEDIUM;
        }
        boolean strongVector = s.cosine() >= p.highCosine();
        boolean weakVector = Double.isNaN(s.cosine()) || s.cosine() < p.lowCosine();
        if (s.coverage() < p.lowCoverage() && weakVector) {
            return Level.LOW;
        }
        if ((s.agreement() && (strongVector || s.coverage() >= p.highCoverage()))
                || (strongVector && s.coverage() >= p.highCoverage())) {
            return Level.HIGH;
        }
        return Level.MEDIUM;
    }

    public static Metrics metrics(Policy p, List<Signals> signals) {
        Map<Level, Integer> counts = new LinkedHashMap<>();
        Map<Level, Integer> h1 = new LinkedHashMap<>();
        Map<Level, Integer> h5 = new LinkedHashMap<>();
        for (Level l : Level.values()) {
            counts.put(l, 0);
            h1.put(l, 0);
            h5.put(l, 0);
        }
        int misses = 0;
        int hits = 0;
        int lowMisses = 0;
        int lowHits = 0;
        for (Signals s : signals) {
            Level l = level(p, s);
            counts.merge(l, 1, Integer::sum);
            if (s.hit1()) {
                h1.merge(l, 1, Integer::sum);
            }
            if (s.hit5()) {
                h5.merge(l, 1, Integer::sum);
                hits++;
                lowHits += l == Level.LOW ? 1 : 0;
            } else {
                misses++;
                lowMisses += l == Level.LOW ? 1 : 0;
            }
        }
        Map<Level, Double> r1 = new LinkedHashMap<>();
        Map<Level, Double> r5 = new LinkedHashMap<>();
        for (Level l : Level.values()) {
            r1.put(l, ratio(h1.get(l), counts.get(l)));
            r5.put(l, ratio(h5.get(l), counts.get(l)));
        }
        return new Metrics(signals.size(), counts, r1, r5, ratio(counts.get(Level.HIGH), signals.size()),
                r1.get(Level.HIGH), ratio(lowMisses, misses), ratio(lowHits, hits));
    }

    /** Grid-searches policies on {@code dev} and reports default vs suggested on dev and {@code test}. */
    public static Suggestion suggest(List<Signals> dev, List<Signals> test, double minHighShare, double maxFalseAlarm) {
        return suggest(dev, test, minHighShare, maxFalseAlarm, Policy.DEFAULT);
    }

    /** As {@link #suggest(List, List, double, double)}, comparing against the retriever's product default {@code def}. */
    public static Suggestion suggest(List<Signals> dev, List<Signals> test, double minHighShare, double maxFalseAlarm,
                                     Policy def) {
        boolean semantic = dev.stream().anyMatch(Signals::semantic);
        List<Policy> grid = grid(semantic);
        Policy best = null;
        double bestObjective = Double.NEGATIVE_INFINITY;
        double bestShare = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Policy p : grid) {
            Metrics m = metrics(p, dev);
            if (m.highShare() < minHighShare || (!Double.isNaN(m.lowFalseAlarm()) && m.lowFalseAlarm() > maxFalseAlarm)) {
                continue;
            }
            double objective = zero(m.precisionHigh()) + zero(m.lowRecallOfMisses());
            double distance = distance(p, def);
            boolean better = objective > bestObjective + 1e-12
                    || (Math.abs(objective - bestObjective) <= 1e-12 && (m.highShare() > bestShare + 1e-12
                    || (Math.abs(m.highShare() - bestShare) <= 1e-12 && distance < bestDistance)));
            if (better) {
                best = p;
                bestObjective = objective;
                bestShare = m.highShare();
                bestDistance = distance;
            }
        }
        boolean feasible = best != null;
        Policy chosen = feasible ? best : def;
        return new Suggestion(def, chosen, feasible, grid.size(), feasible ? bestObjective : Double.NaN, metrics(def, dev),
                metrics(chosen, dev), metrics(def, test), metrics(chosen, test));
    }

    /**
     * Candidate policies. Keyword-only confidence reads coverage and margin; hybrid reads coverage
     * and cosine (not margin), so the unused thresholds stay at their defaults.
     */
    static List<Policy> grid(boolean semantic) {
        Policy d = Policy.DEFAULT;
        List<Policy> out = new ArrayList<>();
        for (int hc = 2; hc <= 10; hc++) {
            for (int lc = 0; lc <= hc && lc <= 9; lc++) {
                double high = hc / 10.0;
                double low = lc / 10.0;
                if (!semantic) {
                    for (double margin : new double[] {0, 0.01, 0.02, 0.05, 0.1, 0.15, 0.2, 0.3, 0.5}) {
                        out.add(new Policy(high, low, d.highCosine(), d.lowCosine(), margin));
                    }
                } else {
                    for (int hv = 8; hv <= 19; hv++) {
                        for (int lv = 4; lv <= hv; lv++) {
                            out.add(new Policy(high, low, hv * 0.05, lv * 0.05, d.minMargin()));
                        }
                    }
                }
            }
        }
        return out;
    }

    public static Map<String, Object> policyMap(Policy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("highCoverage", round(p.highCoverage()));
        m.put("lowCoverage", round(p.lowCoverage()));
        m.put("highCosine", round(p.highCosine()));
        m.put("lowCosine", round(p.lowCosine()));
        m.put("minMargin", round(p.minMargin()));
        return m;
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    private static double distance(Policy a, Policy b) {
        return Math.abs(a.highCoverage() - b.highCoverage()) + Math.abs(a.lowCoverage() - b.lowCoverage())
                + Math.abs(a.highCosine() - b.highCosine()) + Math.abs(a.lowCosine() - b.lowCosine())
                + Math.abs(a.minMargin() - b.minMargin());
    }

    private static double ratio(int a, int b) {
        return b == 0 ? Double.NaN : (double) a / b;
    }

    private static double zero(double d) {
        return Double.isNaN(d) ? 0 : d;
    }
}
