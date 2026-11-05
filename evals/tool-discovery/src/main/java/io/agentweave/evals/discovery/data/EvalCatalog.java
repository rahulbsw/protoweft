package io.agentweave.evals.discovery.data;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * The set of tools one evaluation cell offers: every required (gold) tool of the selected tasks
 * plus seeded random distractors, up to a target size.
 *
 * <p>Construction is deterministic for a given (required set, pool, size, seed):
 * <ol>
 *   <li>Required tools are added first, sorted by id. A size smaller than the required set is
 *       an error.</li>
 *   <li>The distractor pool is sorted by id, then shuffled with {@code java.util.Random(seed)};
 *       distractors are taken in that order until the size is reached. Ids already present and
 *       <em>content duplicates</em> (same case-folded name, description and schema as a tool
 *       already in the catalog) are skipped, so a distractor is never indistinguishable from a
 *       gold tool. With the same seed, a smaller catalog is a subset of a larger one.</li>
 *   <li>Provider-safe names are assigned in that order (gold first, so gold names never get a
 *       dedupe suffix), see {@link ToolNames}.</li>
 *   <li>The presentation order is a second seeded shuffle, so gold tools are not always first.</li>
 * </ol>
 * A size of {@code 0} or less means "required tools plus the whole pool".
 */
public final class EvalCatalog {

    private final List<EvalTool> tools;
    private final Map<String, EvalTool> byId;
    private final ToolNames names;
    private final Set<String> requiredIds;
    private final long seed;
    private final String fingerprint;

    private EvalCatalog(List<EvalTool> tools, ToolNames names, Set<String> requiredIds, long seed) {
        this.tools = List.copyOf(tools);
        Map<String, EvalTool> map = new LinkedHashMap<>();
        tools.forEach(t -> map.put(t.id(), t));
        this.byId = Collections.unmodifiableMap(map);
        this.names = names;
        this.requiredIds = Set.copyOf(requiredIds);
        this.seed = seed;
        this.fingerprint = sha256(tools.stream().map(EvalTool::id).sorted().toList());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A catalog of exactly these tools (no sampling), in this order. */
    public static EvalCatalog of(Collection<EvalTool> tools) {
        return builder().distractorPool(tools).size(0).seed(0).keepPoolOrder().build();
    }

    /** Tools in presentation order. */
    public List<EvalTool> tools() {
        return tools;
    }

    public int size() {
        return tools.size();
    }

    public boolean contains(String toolId) {
        return byId.containsKey(toolId);
    }

    /** The tool with this id, or {@code null}. */
    public EvalTool tool(String toolId) {
        return byId.get(toolId);
    }

    /** The provider-safe name of a tool in this catalog, or {@code null}. */
    public String providerName(String toolId) {
        return byId.containsKey(toolId) ? names.nameOf(toolId) : null;
    }

    /** The tool id behind a provider-facing name, or {@code null} if no catalog tool has that name. */
    public String toolIdForName(String providerName) {
        return names.idOf(providerName);
    }

    /** The tool's spec renamed to its provider-safe name. */
    public ToolSpec providerSpec(String toolId) {
        EvalTool t = byId.get(toolId);
        if (t == null) {
            throw new IllegalArgumentException("not in catalog: " + toolId);
        }
        return t.spec().withName(names.nameOf(toolId));
    }

    /** All provider specs, in presentation order. */
    public List<ToolSpec> providerSpecs() {
        return tools.stream().map(t -> providerSpec(t.id())).toList();
    }

    /** Provider specs of the given tool ids, in the given order (unknown ids are skipped). */
    public List<ToolSpec> providerSpecs(Collection<String> toolIds) {
        return toolIds.stream().filter(byId::containsKey).map(this::providerSpec).toList();
    }

    public Set<String> requiredIds() {
        return requiredIds;
    }

    public long seed() {
        return seed;
    }

    /** SHA-256 over the sorted tool ids; equal catalogs have equal fingerprints. */
    public String fingerprint() {
        return fingerprint;
    }

    /** Tool id to provider-safe name, for reports. */
    public Map<String, String> nameMap() {
        return names.asMap();
    }

    private static String sha256(List<String> parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update(p.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds an {@link EvalCatalog}. */
    public static final class Builder {
        private final List<EvalTool> required = new ArrayList<>();
        private final List<EvalTool> pool = new ArrayList<>();
        private int size;
        private long seed;
        private boolean keepPoolOrder;

        /** Tools that must be in the catalog (the gold tools of the selected tasks). */
        public Builder required(Collection<EvalTool> tools) {
            required.addAll(tools);
            return this;
        }

        /** Candidates for random distractors. Tools also listed as required are ignored here. */
        public Builder distractorPool(Collection<EvalTool> tools) {
            pool.addAll(tools);
            return this;
        }

        /** Target number of tools; {@code <= 0} means required plus the whole pool. */
        public Builder size(int size) {
            this.size = size;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /** Skip both shuffles (used by {@link #of}). */
        Builder keepPoolOrder() {
            this.keepPoolOrder = true;
            return this;
        }

        public EvalCatalog build() {
            Map<String, EvalTool> chosen = new LinkedHashMap<>();
            Set<String> signatures = new HashSet<>();
            List<EvalTool> req = new ArrayList<>(dedupeById(required));
            req.sort(Comparator.comparing(EvalTool::id));
            for (EvalTool t : req) {
                chosen.put(t.id(), t);
                signatures.add(signature(t));
            }
            if (size > 0 && req.size() > size) {
                throw new IllegalArgumentException("catalog size " + size + " is smaller than the "
                        + req.size() + " required gold tools; use a larger size or fewer tasks");
            }
            List<EvalTool> candidates = new ArrayList<>(dedupeById(pool));
            if (!keepPoolOrder) {
                candidates.sort(Comparator.comparing(EvalTool::id));
                Collections.shuffle(candidates, new Random(seed));
            }
            int target = size > 0 ? size : Integer.MAX_VALUE;
            for (EvalTool t : candidates) {
                if (chosen.size() >= target) {
                    break;
                }
                if (chosen.containsKey(t.id()) || !signatures.add(signature(t))) {
                    continue;
                }
                chosen.put(t.id(), t);
            }
            if (size > 0 && chosen.size() < size) {
                throw new IllegalArgumentException("distractor pool too small: wanted " + size
                        + " tools, only " + chosen.size() + " available");
            }
            ToolNames names = new ToolNames();
            chosen.values().forEach(t -> names.assign(t.id(), t.spec().name()));
            List<EvalTool> presented = new ArrayList<>(chosen.values());
            if (!keepPoolOrder) {
                Collections.shuffle(presented, new Random(seed ^ 0x5DEECE66DL));
            }
            Set<String> requiredIds = new HashSet<>();
            req.forEach(t -> requiredIds.add(t.id()));
            return new EvalCatalog(presented, names, requiredIds, seed);
        }

        private static Collection<EvalTool> dedupeById(List<EvalTool> tools) {
            Map<String, EvalTool> m = new LinkedHashMap<>();
            tools.forEach(t -> m.putIfAbsent(Objects.requireNonNull(t, "tool").id(), t));
            return m.values();
        }

        private static String signature(EvalTool t) {
            ToolSpec s = t.spec();
            return s.name().toLowerCase(Locale.ROOT) + "\u0000" + s.description().strip() + "\u0000"
                    + Json.write(new java.util.TreeMap<>(s.inputSchema()));
        }
    }
}
