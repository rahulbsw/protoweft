package io.github.rahulbsw.protoweft.evals.discovery.data;

import java.util.Map;

/**
 * Provenance of loaded data, printed in every report header.
 *
 * @param name     dataset name
 * @param source   where it came from (URL)
 * @param revision upstream revision (git commit / HF dataset sha) from the fetch manifest, or
 *                 {@code "unknown"}
 * @param sha256   SHA-256 over the files actually read (sorted by path, content-only)
 * @param counts   e.g. {@code tools}, {@code tasks}
 */
public record DatasetInfo(String name, String source, String revision, String sha256, Map<String, Integer> counts) {

    public DatasetInfo {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }
}
