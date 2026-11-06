package io.github.rahulbsw.protoweft.core.tool;

/**
 * Lets a running tool report progress and log messages to whoever called it. Protocols that
 * support it forward these as they happen: MCP as {@code notifications/progress} and
 * {@code notifications/message}. Elsewhere they are dropped.
 *
 * <p>A tool method receives it by declaring a {@code ToolProgress} parameter, which is not part
 * of the tool's schema. Calls are cheap and never throw.
 */
public interface ToolProgress {

    /** Log severity, from least to most severe (RFC 5424 levels, as MCP uses them). */
    enum Level { DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY }

    /** Discards everything. */
    ToolProgress NOOP = new ToolProgress() {
        @Override
        public void progress(double done, Double total, String message) {
        }

        @Override
        public void log(Level level, String message) {
        }
    };

    /**
     * Reports progress.
     *
     * @param done    work done so far; should increase with each call
     * @param total   total work if known, else {@code null}
     * @param message a short status, or {@code null}
     */
    void progress(double done, Double total, String message);

    /** Sends a log message to the caller. */
    void log(Level level, String message);

    default void info(String message) {
        log(Level.INFO, message);
    }
}
