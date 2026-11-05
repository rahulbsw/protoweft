package io.agentweave.mcp;

import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Works around MCP Java SDK 2.1.0 never disposing the non-daemon scheduler behind its
 * Streamable HTTP session sweeper (see agentweave-mcp SPEC.md, "SDK workarounds").
 */
final class SweeperCapture {

    private static final String SWEEPER = "streamable-http-server-transport-session-sweeper";
    private static final String KEY = "io.agentweave.mcp.sweeper-capture";

    private SweeperCapture() {
    }

    /** Builds {@code transport}, returning it with the sweeper executors created while building it. */
    static synchronized <T> Captured<T> build(Supplier<T> transport) {
        List<ScheduledExecutorService> captured = new ArrayList<>();
        Schedulers.addExecutorServiceDecorator(KEY, (scheduler, executor) -> {
            if (String.valueOf(scheduler).contains(SWEEPER)) {
                synchronized (captured) {
                    captured.add(executor);
                }
            }
            return executor;
        });
        try {
            T built = transport.get();
            synchronized (captured) {
                return new Captured<>(built, List.copyOf(captured));
            }
        } finally {
            Schedulers.removeExecutorServiceDecorator(KEY);
        }
    }

    /** A built transport and the sweeper executors to shut down when it closes. */
    record Captured<T>(T value, List<ScheduledExecutorService> sweepers) {
        void shutdownSweepers() {
            sweepers.forEach(ScheduledExecutorService::shutdownNow);
        }
    }
}
