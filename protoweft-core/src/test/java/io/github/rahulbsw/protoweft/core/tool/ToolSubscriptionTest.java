package io.github.rahulbsw.protoweft.core.tool;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSubscriptionTest {

    /** Records every signal; requests on demand. */
    static final class Recorder implements Flow.Subscriber<ToolEvent> {
        final List<String> signals = new ArrayList<>();
        Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
        }

        @Override
        public void onNext(ToolEvent item) {
            signals.add(item.text());
        }

        @Override
        public void onError(Throwable throwable) {
            signals.add("error:" + throwable.getMessage());
        }

        @Override
        public void onComplete() {
            signals.add("complete");
        }
    }

    @Test
    void buffersUntilSubscribedAndRequestedThenCompletesAfterLast() {
        BufferedToolSubscription sub = new BufferedToolSubscription("s1", null);
        sub.emit(ToolEvent.text("a"));
        sub.emit(ToolEvent.text("b"));
        Recorder r = new Recorder();
        sub.events().subscribe(r);
        assertThat(r.signals).isEmpty();
        r.subscription.request(1);
        assertThat(r.signals).containsExactly("a");
        assertThat(sub.emit(new ToolEvent(List.of(new Content.Text("c")), true, Map.of()))).isTrue();
        assertThat(sub.isActive()).isFalse();
        assertThat(sub.emit(ToolEvent.text("dropped"))).isFalse();
        r.subscription.request(Long.MAX_VALUE);
        assertThat(r.signals).containsExactly("a", "b", "c", "complete");
    }

    @Test
    void cancelRunsHookOnceAndCompletesTheStream() {
        AtomicInteger cancels = new AtomicInteger();
        BufferedToolSubscription sub = new BufferedToolSubscription("s2", cancels::incrementAndGet);
        Recorder r = new Recorder();
        sub.events().subscribe(r);
        r.subscription.request(10);
        sub.emit(ToolEvent.text("x"));
        sub.cancel();
        sub.cancel();
        assertThat(cancels).hasValue(1);
        assertThat(sub.isActive()).isFalse();
        assertThat(r.signals).containsExactly("x", "complete");
    }

    @Test
    void cancellingTheFlowSubscriptionCancelsTheToolSubscription() {
        AtomicInteger cancels = new AtomicInteger();
        BufferedToolSubscription sub = new BufferedToolSubscription("s3", cancels::incrementAndGet);
        Recorder r = new Recorder();
        sub.events().subscribe(r);
        r.subscription.cancel();
        assertThat(cancels).hasValue(1);
        assertThat(sub.isActive()).isFalse();
        sub.emit(ToolEvent.text("late"));
        assertThat(r.signals).isEmpty();
    }

    @Test
    void secondSubscriberIsRejectedAndFailureIsDelivered() {
        BufferedToolSubscription sub = new BufferedToolSubscription("s4", null);
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        sub.events().subscribe(first);
        sub.events().subscribe(second);
        assertThat(second.signals).singleElement().asString().startsWith("error:");
        first.subscription.request(5);
        sub.fail(new IllegalStateException("lost"));
        assertThat(first.signals).containsExactly("error:lost");
        assertThat(sub.isActive()).isFalse();
    }

    @Test
    void outcomeKeepsLegacyConstructorsAndCarriesSubscriptionThroughRegistry() {
        ToolOutcome plain = new ToolOutcome(List.of(new Content.Text("t")), false, null);
        assertThat(plain.subscription()).isEmpty();
        assertThat(ToolOutcome.text("x").subscription()).isEmpty();

        BufferedToolSubscription sub = new BufferedToolSubscription("s5", null);
        ToolOutcome withSub = ToolOutcome.text("subscribed").withSubscription(sub);
        LocalTools tools = LocalTools.builder()
                .tool("watch", "Watches", Map.of("type", "object"), (args, caller) -> withSub)
                .build();
        ToolRegistry registry = new ToolRegistry().register("w", tools);
        ToolOutcome out = registry.invoke(ToolInvocation.of("w.watch", Map.of()), CallerIdentity.ANONYMOUS);
        assertThat(out).isSameAs(withSub);
        assertThat(out.subscription()).containsSame(sub);
        assertThat(out.withSubscription(null).subscription()).isEmpty();
    }
}
