package io.github.rahulbsw.protoweft.evals.discovery.report;

import io.github.rahulbsw.protoweft.evals.discovery.data.DatasetInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report header facts: what ran, on which commit and data, with which settings.
 *
 * @param runId     directory name of the run
 * @param kind      {@code retrieval} or {@code llm}
 * @param startedAt ISO-8601 start time
 * @param git       commit and dirty flag
 * @param datasets  provenance of every dataset read
 * @param config    effective settings (never secrets)
 */
public record RunInfo(String runId, String kind, String startedAt, GitInfo git, List<DatasetInfo> datasets,
                      Map<String, Object> config) {

    public RunInfo {
        datasets = List.copyOf(datasets);
        config = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("kind", kind);
        m.put("startedAt", startedAt);
        m.put("gitCommit", git.commit());
        m.put("gitDirty", git.dirty());
        m.put("datasets", datasets.stream().map(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("name", d.name());
            dm.put("source", d.source());
            dm.put("revision", d.revision());
            dm.put("sha256", d.sha256());
            dm.put("counts", d.counts());
            return dm;
        }).toList());
        m.put("config", config);
        return m;
    }
}
