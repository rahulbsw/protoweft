package io.github.rahulbsw.protoweft.argi.neutral;

import io.github.rahulbsw.protoweft.argi.neutral.ArgiNeutralAgents.SuspendedRun;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentEvent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The {@link AgentHandler} built by {@link ArgiNeutralAgents}. Agent events are queued by the
 * subscriber and handled on the handler's thread, so {@link AgentTaskContext} is called from one thread.
 */
final class NeutralAgentHandler implements AgentHandler {

    private static final long POLL_MILLIS = 100;
    private static final Object COMPLETE = new Object();

    private final Agent agent;
    private final Function<AgentRequest, AgentExecutionContext> executionContext;
    private final BiFunction<AgentRequest, io.github.agentic.ai.agent.AgentRequest,
            io.github.agentic.ai.agent.AgentRequest> requestCustomizer;
    private final Map<String, SuspendedRun> suspensions;
    private final boolean replayHistory;
    private final Map<String, Run> running = new ConcurrentHashMap<>();

    NeutralAgentHandler(ArgiNeutralAgents.Builder builder) {
        this.agent = builder.agent;
        this.executionContext = builder.executionContext;
        this.requestCustomizer = builder.requestCustomizer;
        this.suspensions = builder.suspensions;
        this.replayHistory = builder.replayHistory;
    }

    /** A subscription in progress, cancellable from {@link #onCancel}. */
    private record Run(AtomicReference<Flow.Subscription> subscription, AgentExecutionContext context) {
        void cancel() {
            Flow.Subscription current = subscription.get();
            if (current != null) {
                current.cancel();
            }
            context.control().cancel();
        }
    }

    private record Failure(Throwable error) {
    }

    @Override
    public void handle(AgentRequest request, AgentTaskContext task) {
        String taskId = request.taskId();
        SuspendedRun suspended = request.resumed() && taskId != null ? suspensions.remove(taskId) : null;
        io.github.agentic.ai.agent.AgentRequest argi;
        if (suspended != null) {
            argi = resumeRequest(request, suspended);
        } else if (isBlank(request.message())) {
            task.fail("the message has no content");
            return;
        } else {
            argi = freshRequest(request);
        }
        try {
            argi = Objects.requireNonNull(requestCustomizer.apply(request, argi), "requestCustomizer returned null");
        } catch (RuntimeException e) {
            task.fail(message(e));
            return;
        }
        try (AgentExecutionContext context = Objects.requireNonNull(executionContext.apply(request),
                "executionContext returned null")) {
            run(argi, context, request, task);
        }
    }

    @Override
    public void onCancel(String taskId) {
        if (taskId == null) {
            return;
        }
        suspensions.remove(taskId);
        Run run = running.get(taskId);
        if (run != null) {
            run.cancel();
        }
    }

    private void run(io.github.agentic.ai.agent.AgentRequest argi, AgentExecutionContext context,
                     AgentRequest request, AgentTaskContext task) {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        Run run = new Run(subscription, context);
        String key = request.taskId();
        if (key != null) {
            running.put(key, run);
        }
        boolean terminated = false;
        try {
            Flow.Publisher<AgentEvent> publisher;
            try {
                publisher = Objects.requireNonNull(agent.stream(argi, context), "agent stream must not be null");
                publisher.subscribe(new QueueingSubscriber(subscription, events));
            } catch (RuntimeException e) {
                terminated = true;
                task.fail(message(e));
                return;
            }
            Transcript transcript = new Transcript();
            while (!task.isCancelled()) {
                Object event = poll(events);
                if (event == null) {
                    continue;
                }
                if (event instanceof Failure failure) {
                    terminated = true;
                    if (!task.isCancelled()) {
                        task.fail(message(failure.error()));
                    }
                    return;
                }
                if (event == COMPLETE) {
                    terminated = true;
                    task.fail("the agent finished without an outcome");
                    return;
                }
                AgentEvent agentEvent = (AgentEvent) event;
                if (agentEvent instanceof AgentEvent.Finished finished) {
                    terminated = true;
                    finish(finished.outcome(), transcript, request, task);
                    return;
                }
                transcript.on(agentEvent, task);
            }
        } finally {
            if (key != null) {
                running.remove(key, run);
            }
            if (!terminated) {
                run.cancel();
            }
        }
    }

    private void finish(AgentOutcome outcome, Transcript transcript, AgentRequest request, AgentTaskContext task) {
        if (outcome.status() == AgentOutcome.Status.SUSPENDED) {
            AgentOutcome.Suspension suspension = outcome.suspension();
            if (request.taskId() != null) {
                suspensions.put(request.taskId(), new SuspendedRun(suspension.resumeToken(), outcome.messages(),
                        outcome.outputs(), suspension.prompts()));
            }
            task.requireInput(question(suspension.prompts()));
            return;
        }
        List<Content> parts = new ArrayList<>();
        parts.add(new Content.Text(transcript.answer(outcome.messages())));
        Object structured = outcome.outputs().get(ArgiNeutralAgents.STRUCTURED_OUTPUT_KEY);
        if (structured != null) {
            parts.add(new Content.Json(structured));
        }
        task.artifact(ArgiNeutralAgents.ARTIFACT_NAME, parts);
        task.complete();
    }

    private io.github.agentic.ai.agent.AgentRequest freshRequest(AgentRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (replayHistory) {
            request.history().forEach(m -> toChat(m).ifPresent(messages::add));
        }
        toChat(request.message()).ifPresent(messages::add);
        return new io.github.agentic.ai.agent.AgentRequest(messages, baseInputs(request), null);
    }

    private io.github.agentic.ai.agent.AgentRequest resumeRequest(AgentRequest request, SuspendedRun suspended) {
        Map<String, Object> inputs = new LinkedHashMap<>(suspended.outputs());
        inputs.putAll(baseInputs(request));
        inputs.put(ArgiNeutralAgents.REPLY_KEY, request.text());
        request.message().parts().stream()
                .filter(Content.Json.class::isInstance)
                .map(p -> ((Content.Json) p).value())
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(value -> inputs.put(ArgiNeutralAgents.REPLY_DATA_KEY, value));
        return new io.github.agentic.ai.agent.AgentRequest(suspended.messages(), inputs, suspended.resumeToken());
    }

    private static Map<String, Object> baseInputs(AgentRequest request) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(ArgiNeutralTools.CALLER_KEY, request.caller());
        if (request.taskId() != null) {
            inputs.put(ArgiNeutralAgents.TASK_ID_KEY, request.taskId());
        }
        if (request.contextId() != null) {
            inputs.put(ArgiNeutralAgents.CONTEXT_ID_KEY, request.contextId());
        }
        return inputs;
    }

    private static java.util.Optional<ChatMessage> toChat(AgentMessage message) {
        List<ContentPart> parts = message.parts().stream().map(Results::toPart).toList();
        if (parts.isEmpty()) {
            return java.util.Optional.empty();
        }
        ChatRole role = message.role() == AgentMessage.Role.AGENT ? ChatRole.ASSISTANT : ChatRole.USER;
        return java.util.Optional.of(new ChatMessage(role, parts, Map.of()));
    }

    private static boolean isBlank(AgentMessage message) {
        return message.parts().stream().allMatch(p -> p instanceof Content.Text t && t.text().isBlank());
    }

    /** The suspension's prompts as one question: a single value as-is, else sorted {@code key: value} lines. */
    static String question(Map<String, Object> prompts) {
        if (prompts.isEmpty()) {
            return "Input required";
        }
        if (prompts.size() == 1) {
            return String.valueOf(prompts.values().iterator().next());
        }
        return new TreeMap<>(prompts).entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    private static Object poll(BlockingQueue<Object> events) {
        try {
            return events.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure(e);
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Turns intermediate events into {@code working} statuses and remembers the answer so far. */
    private static final class Transcript {
        private final StringBuilder deltas = new StringBuilder();
        private ChatMessage lastAssistant;

        void on(AgentEvent event, AgentTaskContext task) {
            if (event instanceof AgentEvent.MessageDelta delta) {
                if (!delta.text().isEmpty()) {
                    deltas.append(delta.text());
                    task.working(delta.text());
                }
            } else if (event instanceof AgentEvent.Message message) {
                ChatMessage chat = message.message();
                if (chat.role() == ChatRole.ASSISTANT) {
                    lastAssistant = chat;
                    if (chat.hasToolCalls()) {
                        task.working("calling " + chat.toolCalls().stream().map(ToolCall::name)
                                .collect(Collectors.joining(", ")));
                    }
                }
            } else if (event instanceof AgentEvent.Progress progress) {
                task.working(progress.details().isEmpty() ? progress.step()
                        : progress.step() + " " + new TreeMap<>(progress.details()));
            }
            // UsageUpdate: not reported.
        }

        /** The last assistant message of the outcome, else of the events, else the streamed text. */
        String answer(List<ChatMessage> outcomeMessages) {
            for (int i = outcomeMessages.size() - 1; i >= 0; i--) {
                if (outcomeMessages.get(i).role() == ChatRole.ASSISTANT) {
                    return outcomeMessages.get(i).text();
                }
            }
            return lastAssistant != null ? lastAssistant.text() : deltas.toString();
        }
    }

    /** Requests everything and queues events, errors and completion for the handler thread. */
    private record QueueingSubscriber(AtomicReference<Flow.Subscription> subscription, BlockingQueue<Object> events)
            implements Flow.Subscriber<AgentEvent> {
        @Override
        public void onSubscribe(Flow.Subscription next) {
            if (!subscription.compareAndSet(null, next)) {
                next.cancel();
                return;
            }
            next.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AgentEvent event) {
            events.add(event);
        }

        @Override
        public void onError(Throwable error) {
            events.add(new Failure(error));
        }

        @Override
        public void onComplete() {
            events.add(COMPLETE);
        }
    }
}
