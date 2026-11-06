package io.github.rahulbsw.protoweft.evals.discovery.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The exact commit results are bound to: {@code git rev-parse HEAD} plus a dirty flag from
 * {@code git status --porcelain} (any uncommitted change, including untracked files). Read-only.
 *
 * @param commit full commit id, or {@code unknown} outside a git checkout
 * @param dirty  whether the working tree had uncommitted changes
 */
public record GitInfo(String commit, boolean dirty) {

    public static GitInfo detect(Path dir) {
        String head = run(dir, "git", "rev-parse", "HEAD");
        if (head == null || head.isBlank()) {
            return new GitInfo("unknown", true);
        }
        String status = run(dir, "git", "status", "--porcelain");
        return new GitInfo(head.strip(), status == null || !status.isBlank());
    }

    /** {@code <commit>} or {@code <commit>-dirty}. */
    public String describe() {
        return dirty ? commit + "-dirty" : commit;
    }

    private static String run(Path dir, String... cmd) {
        try {
            Process p = new ProcessBuilder(List.of(cmd)).directory(dir.toFile()).redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
