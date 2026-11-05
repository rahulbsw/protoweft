package io.agentweave.evals.discovery.cost;

import io.agentweave.evals.discovery.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cumulative spend across runs, so the global budget holds over many invocations. Persisted as
 * JSON after every recorded call (written to a temp file, then moved into place).
 */
public final class SpendLedger {

    private final Path file;
    private double totalUsd;
    private long calls;
    private final Map<String, Double> byModel = new LinkedHashMap<>();

    private SpendLedger(Path file) {
        this.file = file;
    }

    /** A ledger that is never persisted (tests, dry runs). */
    public static SpendLedger inMemory() {
        return new SpendLedger(null);
    }

    /** Opens (or starts) the ledger at {@code file}. */
    public static SpendLedger open(Path file) {
        SpendLedger l = new SpendLedger(file);
        if (Files.isRegularFile(file)) {
            Map<String, Object> m = Json.asMap(Json.read(file));
            l.totalUsd = m.get("totalUsd") instanceof Number n ? n.doubleValue() : 0;
            l.calls = m.get("calls") instanceof Number n ? n.longValue() : 0;
            Json.asMap(m.get("byModel")).forEach((k, v) -> l.byModel.put(k, v instanceof Number n ? n.doubleValue() : 0.0));
        }
        return l;
    }

    public synchronized double totalUsd() {
        return totalUsd;
    }

    public synchronized long calls() {
        return calls;
    }

    public synchronized void record(String modelKey, double usd) {
        totalUsd += usd;
        calls++;
        byModel.merge(modelKey, usd, Double::sum);
        persist();
    }

    private void persist() {
        if (file == null) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalUsd", totalUsd);
        m.put("calls", calls);
        m.put("byModel", byModel);
        m.put("updated", Instant.now().toString());
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "ledger", ".tmp");
            Files.writeString(tmp, Json.writePretty(m), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write spend ledger " + file, e);
        }
    }
}
