package io.agentweave.evals.discovery.run;

import io.agentweave.evals.discovery.report.GitInfo;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/** Run ids: {@code <kind>-<UTC timestamp>-<short commit>[-dirty]-<counter>}. */
final class RunIds {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private RunIds() {
    }

    static String next(String kind, Path repoDir) {
        GitInfo git = GitInfo.detect(repoDir);
        String commit = git.commit().length() > 10 ? git.commit().substring(0, 10) : git.commit();
        return kind + "-" + ZonedDateTime.now(ZoneOffset.UTC).format(TS) + "-" + commit + (git.dirty() ? "-dirty" : "")
                + "-" + COUNTER.incrementAndGet();
    }
}
