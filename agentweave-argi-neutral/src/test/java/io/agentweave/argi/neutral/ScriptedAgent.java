package io.agentweave.argi.neutral;

import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A neutral ARGI {@link Agent} with no model: each run plays a script on its own thread. Script
 * items: an {@link AgentEvent} is emitted, a {@link Throwable} ends the run with {@code onError},
 * {@link #HANG} waits until the subscription is cancelled; the end of the script is {@code onComplete}.
 */
final class ScriptedAgent implements Agent {

    static final Object HANG = new Object();

    final List<AgentRequest> requests = new CopyOnWriteArrayList<>();
    final List<AgentExecutionContext> contexts = new CopyOnWriteArrayList<>();
    final CountDownLatch cancelled = new CountDownLatch(1);
    private final Function<AgentRequest, List<Object>> script;

    ScriptedAgent(Function<AgentRequest, List<Object>> script) {
        this.script = script;
    }

    @Override
    public Flow.Publisher<AgentEvent> stream(AgentRequest request, AgentExecutionContext context) {
        requests.add(request);
        contexts.add(context);
        return subscriber -> {
            CountDownLatch stop = new CountDownLatch(1);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                    stop.countDown();
                    cancelled.countDown();
                }
            });
            Thread thread = new Thread(() -> play(script.apply(request), subscriber, stop), "scripted-agent");
            thread.setDaemon(true);
            thread.start();
        };
    }

    private static void play(List<Object> items, Flow.Subscriber<? super AgentEvent> subscriber, CountDownLatch stop) {
        for (Object item : items) {
            if (stop.getCount() == 0) {
                return;
            }
            if (item == HANG) {
                try {
                    stop.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (item instanceof Throwable error) {
                subscriber.onError(error);
                return;
            }
            subscriber.onNext((AgentEvent) item);
        }
        subscriber.onComplete();
    }
}
