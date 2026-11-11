package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An in-memory set of recorded tool outputs, keyed by tool + normalized arguments (see
 * {@link NormalizedArgs}). Backed by JSONL files, one {@link ResultFixture} per line
 * ({@code {"tool","args","content","tokens"}}). Loading from live servers is out of scope here;
 * {@link #record} lets a future recorder append captures through the same store.
 *
 * <p>Not thread-safe; a run uses one store per thread or loads once and only reads.
 */
public final class FixtureStore {

    private final Map<String, ResultFixture> byKey = new LinkedHashMap<>();

    /** An empty store; every lookup misses and falls back to a synthetic payload. */
    public static FixtureStore empty() {
        return new FixtureStore();
    }

    /** Loads every fixture from a JSONL file or a directory of {@code *.jsonl} files. */
    public static FixtureStore load(Path path) {
        FixtureStore store = new FixtureStore();
        if (path == null || !Files.exists(path)) {
            return store;
        }
        try (Stream<Path> files = Files.isDirectory(path)
                ? Files.list(path).filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                : Stream.of(path)) {
            files.sorted().forEach(file -> Json.readJsonl(file).forEach(row -> store.record(fromMap(row))));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load fixtures from " + path, e);
        }
        return store;
    }

    @SuppressWarnings("unchecked")
    private static ResultFixture fromMap(Map<String, Object> row) {
        Object args = row.get("args");
        Object tokens = row.get("tokens");
        return new ResultFixture(String.valueOf(row.get("tool")),
                args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                String.valueOf(row.getOrDefault("content", "")),
                tokens instanceof Number n ? n.intValue() : 0);
    }

    /** Adds (or replaces) a fixture, last write wins for the same tool + normalized arguments. */
    public void record(ResultFixture fixture) {
        byKey.put(NormalizedArgs.key(fixture.tool(), fixture.args()), fixture);
    }

    /** The recorded output for a call, if one was recorded for the same tool and arguments. */
    public Optional<ResultFixture> lookup(String tool, Map<String, Object> arguments) {
        return Optional.ofNullable(byKey.get(NormalizedArgs.key(tool, arguments)));
    }

    public int size() {
        return byKey.size();
    }

    /** The empirical result-size distribution over the loaded fixtures. */
    public SizeDistribution sizes(int fallbackTokens) {
        List<Integer> observed = new ArrayList<>();
        byKey.values().forEach(f -> observed.add(f.tokens()));
        return SizeDistribution.of(observed, fallbackTokens);
    }

    /** Writes every fixture to one JSONL file (creating parent directories). */
    public void save(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder out = new StringBuilder();
            for (ResultFixture f : byKey.values()) {
                out.append(Json.write(f.toMap())).append('\n');
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save fixtures to " + file, e);
        }
    }
}
