package io.agentweave.evals.discovery.report;

import io.agentweave.evals.discovery.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Writes one run's files: {@code calls.jsonl} (one line per scored unit, flushed immediately so
 * a crash or budget stop keeps everything done so far), {@code summary.json} and
 * {@code report.md}.
 */
public final class ResultsWriter implements AutoCloseable {

    private final Path dir;
    private final BufferedWriter rows;

    public ResultsWriter(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
            rows = Files.newBufferedWriter(dir.resolve("calls.jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create results directory " + dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    public synchronized void row(Map<String, Object> row) {
        try {
            rows.write(Json.write(row));
            rows.newLine();
            rows.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void summary(Map<String, Object> summary) {
        write("summary.json", Json.writePretty(summary));
    }

    public void report(String markdown) {
        write("report.md", markdown);
    }

    private void write(String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            rows.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
