package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.auth.HttpAuthorizer;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A {@link RapClientConnector}'s configuration, fixed at build time. */
record ClientSettings(List<String> serverUrls, CallbackRouter router, Duration timeout, Duration requestTimeout,
                      CredentialProvider credentials, HttpAuthorizer authorizer, RapPromptHandler promptHandler,
                      RapStore store, String storeKey, ExecutorService executor, boolean ownsExecutor,
                      RapRetryPolicy retry, String threadId, Duration dedupRetention, HttpClient httpClient,
                      Consumer<RapRecoveredResult> recoveredListener, Clock clock) {

    private static final System.Logger LOG = System.getLogger(RapClientConnector.class.getName());

    static ExecutorService daemonPool(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs a task on the executor; {@code false} if the executor rejected it. */
    boolean execute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "RAP task rejected by the executor: " + e.getMessage());
            return false;
        }
    }

    void shutdownExecutor() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
