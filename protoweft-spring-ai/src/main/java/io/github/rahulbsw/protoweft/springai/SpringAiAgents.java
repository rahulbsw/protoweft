package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Serves a Spring AI {@link ChatClient} through any Protoweft agent server, for example the A2A
 * server, without this module depending on it.
 *
 * <pre>{@code
 * ChatClient chat = ChatClient.builder(chatModel)
 *         .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
 *         .defaultTools(ProtoweftToolCallbacks.from(registry))
 *         .build();
 * AgentHandler handler = SpringAiAgents.streamingHandler(chat);
 * AgentDescriptor card = SpringAiAgents.descriptor("Assistant", "Answers questions", "1.0.0",
 *         SpringAiAgents.skill("qa", "Q&A", "General questions", "qa"));
 * // hand card + handler to an Protoweft agent server, e.g. protoweft-a2a's A2aAgentServer
 * }</pre>
 *
 * <p>Each message becomes one {@code ChatClient} request: the message text is the user message,
 * the request's {@code contextId} (falling back to its {@code taskId}) is passed as advisor
 * parameter {@link ChatMemory#CONVERSATION_ID}, so a memory advisor follows the protocol's
 * conversation, and the caller is passed as tool-context entry
 * {@link ProtoweftToolCallbacks#CALLER_KEY}, so Protoweft tools are called on the caller's
 * behalf. The blocking handler's answer becomes an artifact named {@value #ARTIFACT_NAME}; the
 * streaming handler streams it with {@link AgentTaskContext#textDelta}. Then the task completes;
 * an exception fails the task with its message.
 */
public final class SpringAiAgents {

    /** Name of the artifact that carries the agent's answer. */
    public static final String ARTIFACT_NAME = "response";

    private static final long POLL_MILLIS = 100;
    /** Whether {@code protoweft-agui} is on the classpath; {@link AgUiPrompts} is loaded only if so. */
    private static final boolean AGUI = present("io.github.rahulbsw.protoweft.agui.AgUi");
    private static final Object COMPLETE = new Object();

    private SpringAiAgents() {
    }

    /** Serves a {@code ChatClient} with one blocking call per message. */
    public static AgentHandler handler(ChatClient chatClient) {
        return handler(chatClient, (request, spec) -> { });
    }

    /**
     * As {@link #handler(ChatClient)}, letting {@code customizer} adjust each request (system
     * text, options, extra advisor parameters or tool context) after the defaults are applied.
     */
    public static AgentHandler handler(ChatClient chatClient,
                                       BiConsumer<AgentRequest, ChatClient.ChatClientRequestSpec> customizer) {
        Objects.requireNonNull(chatClient, "chatClient");
        Objects.requireNonNull(customizer, "customizer");
        return (request, task) -> {
            Prepared prepared = prepare(chatClient, customizer, request, task);
            if (prepared == null) {
                task.fail("the message has no text");
                return;
            }
            String answer;
            try {
                answer = text(prepared.spec().call().chatResponse(), prepared.applicationTools());
            } catch (Exception e) {
                task.fail(message(e));
                return;
            }
            finish(task, answer);
        };
    }

    /**
     * Serves a {@code ChatClient} streaming: each chunk is reported with
     * {@link AgentTaskContext#textDelta}, then the task completes. All task calls are made on the
     * handler's thread; a cancelled task disposes the subscription, which stops
     * the stream.
     */
    public static AgentHandler streamingHandler(ChatClient chatClient) {
        return streamingHandler(chatClient, (request, spec) -> { });
    }

    /** As {@link #streamingHandler(ChatClient)}, with a per-request customizer (see {@link #handler(ChatClient, BiConsumer)}). */
    public static AgentHandler streamingHandler(ChatClient chatClient,
                                                BiConsumer<AgentRequest, ChatClient.ChatClientRequestSpec> customizer) {
        Objects.requireNonNull(chatClient, "chatClient");
        Objects.requireNonNull(customizer, "customizer");
        return (request, task) -> {
            Prepared prepared = prepare(chatClient, customizer, request, task);
            if (prepared == null) {
                task.fail("the message has no text");
                return;
            }
            BlockingQueue<Object> events = new LinkedBlockingQueue<>();
            Disposable subscription;
            try {
                subscription = prepared.spec().stream().chatResponse()
                        .subscribe(events::add, events::add, () -> events.add(COMPLETE));
            } catch (Exception e) {
                task.fail(message(e));
                return;
            }
            try {
                while (!task.isCancelled()) {
                    Object event = events.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (event instanceof ChatResponse chunk) {
                        task.textDelta(text(chunk, prepared.applicationTools()));
                    } else if (event == COMPLETE) {
                        task.complete();
                        return;
                    } else if (event instanceof Throwable error) {
                        task.fail(message(error));
                        return;
                    }
                }
            } finally {
                subscription.dispose();
            }
        };
    }

    /** An agent self-description for the server to publish. */
    public static AgentDescriptor descriptor(String name, String description, String version,
                                             AgentDescriptor.Skill... skills) {
        return new AgentDescriptor(name, description, version, List.of(skills));
    }

    /** One advertised skill. */
    public static AgentDescriptor.Skill skill(String id, String name, String description, String... tags) {
        return new AgentDescriptor.Skill(id, name, description, List.of(tags), List.of());
    }

    /** The conversation id for a request: its context id, else its task id. */
    static String conversationId(AgentRequest request) {
        if (request.contextId() != null && !request.contextId().isBlank()) {
            return request.contextId();
        }
        return request.taskId() != null ? request.taskId() : UUID.randomUUID().toString();
    }

    /** A prepared request and the names of the application tools it offers (AG-UI only). */
    private record Prepared(ChatClient.ChatClientRequestSpec spec, Set<String> applicationTools) {
    }

    /**
     * The request for one message: the message as the user text, or over AG-UI the run's whole
     * conversation and the application's tools. {@code null} if there is nothing to send.
     */
    private static Prepared prepare(ChatClient chatClient,
                                    BiConsumer<AgentRequest, ChatClient.ChatClientRequestSpec> customizer,
                                    AgentRequest request, AgentTaskContext task) {
        String conversationId = conversationId(request);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of(ProtoweftToolCallbacks.CALLER_KEY, request.caller(),
                        ProtoweftToolCallbacks.TASK_KEY, task));
        Set<String> applicationTools = Set.of();
        Optional<?> agUi = AGUI ? AgUiPrompts.run(task) : Optional.empty();
        if (agUi.isPresent()) {
            applicationTools = AgUiPrompts.apply(spec, (io.github.rahulbsw.protoweft.agui.AgUiRun) agUi.get());
        } else if (request.text().isBlank()) {
            return null;
        } else {
            spec.user(request.text());
        }
        customizer.accept(request, spec);
        return new Prepared(spec, applicationTools);
    }

    /** The response's text, without the placeholders Spring AI returns for the application's tools. */
    private static String text(ChatResponse response, Set<String> applicationTools) {
        if (response == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Generation g : response.getResults()) {
            if (AGUI && !applicationTools.isEmpty() && AgUiPrompts.isApplicationToolResult(g, applicationTools)) {
                continue;
            }
            if (g.getOutput() != null && g.getOutput().getText() != null) {
                text.append(g.getOutput().getText());
            }
        }
        return text.toString();
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, SpringAiAgents.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static void finish(AgentTaskContext task, String answer) {
        task.artifact(ARTIFACT_NAME, List.of(new Content.Text(answer == null ? "" : answer)));
        task.complete();
    }

    private static String message(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }
}
