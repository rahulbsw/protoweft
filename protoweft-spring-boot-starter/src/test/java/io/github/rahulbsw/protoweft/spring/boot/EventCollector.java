package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.tool.ToolEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Collects a subscription's events until it completes. */
final class EventCollector implements Flow.Subscriber<ToolEvent> {

    final List<ToolEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ToolEvent item) {
        events.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
        done.countDown();
    }

    @Override
    public void onComplete() {
        done.countDown();
    }

    List<String> awaitTexts() throws InterruptedException {
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("subscription did not complete; got " + events);
        }
        return events.stream().map(ToolEvent::text).toList();
    }
}
