package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.tool.ToolEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Collects a subscription's events; can block in {@code onNext} to test serial processing. */
final class EventCollector implements Flow.Subscriber<ToolEvent> {

    final List<ToolEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean blocking;
    volatile Throwable error;
    volatile Flow.Subscription subscription;

    void block() {
        blocking = true;
    }

    void release() {
        release.countDown();
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ToolEvent item) {
        events.add(item);
        entered.countDown();
        if (blocking) {
            await(release);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        error = throwable;
        done.countDown();
    }

    @Override
    public void onComplete() {
        done.countDown();
    }

    List<String> texts() {
        return events.stream().map(ToolEvent::text).toList();
    }

    void awaitEntered() {
        await(entered);
    }

    void awaitDone() {
        await(done);
    }

    boolean isDone() {
        return done.getCount() == 0;
    }

    void awaitCount(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (events.size() < n) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + n + " events, got " + texts());
            }
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
