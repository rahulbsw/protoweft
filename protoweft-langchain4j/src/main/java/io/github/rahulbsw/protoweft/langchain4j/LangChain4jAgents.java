package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.TokenStream;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Serves a LangChain4j agent (typically an AI Service) through any Protoweft agent server,
 * for example the A2A server, without this module depending on it.
 *
 * <pre>{@code
 * interface Assistant {
 *     String chat(@MemoryId String conversationId, @UserMessage String message);
 * }
 * Assistant assistant = AiServices.builder(Assistant.class)
 *         .chatModel(model)
 *         .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
 *         .build();
 *
 * AgentHandler handler = LangChain4jAgents.handler(assistant::chat);
 * AgentDescriptor card = LangChain4jAgents.descriptor("Assistant", "Answers questions", "1.0.0",
 *         LangChain4jAgents.skill("qa", "Q&A", "General questions", "qa"));
 * // hand card + handler to an Protoweft agent server, e.g. protoweft-a2a's A2aAgentServer
 * }</pre>
 *
 * <p>The conversation id passed to the agent is the request's {@code contextId} (falling back to
 * its {@code taskId}), so LangChain4j chat memory follows the protocol's conversation. The blocking
 * handler's answer becomes an artifact named {@value #ARTIFACT_NAME}; the streaming handler
 * streams it with {@link AgentTaskContext#textDelta}. Then the task completes; an exception fails
 * the task with its message.
 */
public final class LangChain4jAgents {

    /** Name of the artifact that carries the agent's answer. */
    public static final String ARTIFACT_NAME = "response";

    private static final long POLL_MILLIS = 100;
    /** Whether {@code protoweft-agui} is on the classpath; {@link AgUiHistory} is loaded only if so. */
    private static final boolean AGUI = present("io.github.rahulbsw.protoweft.agui.AgUi");
    private static final System.Logger LOG = System.getLogger(LangChain4jAgents.class.getName());
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private LangChain4jAgents() {
    }

    /** A blocking agent; an AI Service method {@code String chat(@MemoryId String, @UserMessage String)} fits. */
    @FunctionalInterface
    public interface Conversation {
        String chat(String conversationId, String userMessage);
    }

    /** A streaming agent; an AI Service method {@code TokenStream chat(@MemoryId String, @UserMessage String)} fits. */
    @FunctionalInterface
    public interface StreamingConversation {
        TokenStream chat(String conversationId, String userMessage);
    }

    /** Serves a blocking agent: one call per message, the answer becomes the task's artifact. */
    public static AgentHandler handler(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        return (request, task) -> {
            String id = conversationId(request);
            FrontendHistory.Run run = begin(id, task);
            String answer;
            try {
                answer = conversation.chat(id, request.text());
            } catch (Exception e) {
                task.fail(message(e));
                return;
            } finally {
                end(id, run);
            }
            finish(task, answer);
        };
    }

    /**
     * Serves a streaming agent: each partial response is reported with
     * {@link AgentTaskContext#textDelta}, then the task completes. If the model streamed nothing,
     * the complete response's text becomes the {@value #ARTIFACT_NAME} artifact instead. All task
     * calls are made on the handler's thread; a cancelled task stops listening to the stream.
     */
    public static AgentHandler streamingHandler(StreamingConversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        return (request, task) -> {
            String id = conversationId(request);
            FrontendHistory.Run run = begin(id, task);
            try {
                stream(conversation, request, task, id);
            } finally {
                end(id, run);
            }
        };
    }

    private static void stream(StreamingConversation conversation, AgentRequest request, AgentTaskContext task,
                               String conversationId) throws InterruptedException {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        try {
            conversation.chat(conversationId, request.text())
                    .onPartialResponse(events::add)
                    .beforeToolExecution(events::add)
                    .onToolExecuted(events::add)
                    .onCompleteResponse(events::add)
                    .onError(events::add)
                    .start();
        } catch (Exception e) {
            task.fail(message(e));
            return;
        }
        boolean streamed = false;
        Map<ToolExecutionRequest, String> callIds = new java.util.HashMap<>();
        while (!task.isCancelled()) {
            Object event = events.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (event instanceof String partial) {
                streamed |= !partial.isEmpty();
                task.textDelta(partial);
            } else if (event instanceof BeforeToolExecution before) {
                ToolExecutionRequest call = before.request();
                String callId = call.id() != null ? call.id() : UUID.randomUUID().toString();
                callIds.put(call, callId);
                task.toolCall(callId, call.name(), call.arguments());
            } else if (event instanceof ToolExecution done) {
                String callId = callIds.remove(done.request());
                if (callId != null) {
                    String result = done.result() == null ? "" : done.result();
                    task.toolResult(callId, done.hasFailed() ? ToolOutcome.failure(result) : ToolOutcome.text(result));
                }
            } else if (event instanceof ChatResponse response) {
                if (streamed) {
                    task.complete();
                } else {
                    finish(task, response.aiMessage() == null ? null : response.aiMessage().text());
                }
                return;
            } else if (event instanceof Throwable error) {
                task.fail(message(error));
                return;
            }
        }
    }

    /**
     * Chat memory for an AI Service served over AG-UI as well as other protocols: while an AG-UI
     * run is in flight for a conversation, the AI Service sees that run's own conversation (AG-UI
     * clients send the whole conversation on every run); otherwise, and for every other protocol,
     * it uses {@code fallback}. Build the AI Service with it:
     *
     * <pre>{@code
     * AiServices.builder(Assistant.class)
     *         .streamingChatModel(model)
     *         .chatMemoryProvider(LangChain4jAgents.chatMemory(id -> MessageWindowChatMemory.withMaxMessages(20)))
     *         .build();
     * }</pre>
     *
     * <p>AG-UI runs leave {@code fallback}'s memory untouched.
     */
    public static ChatMemoryProvider chatMemory(ChatMemoryProvider fallback) {
        return FrontendHistory.provider(fallback);
    }

    /** Makes an AG-UI run's conversation the memory for {@code id} while the run is in flight. */
    private static FrontendHistory.Run begin(String id, AgentTaskContext task) {
        if (!AGUI) {
            return null;
        }
        java.util.Optional<List<dev.langchain4j.data.message.ChatMessage>> history = AgUiHistory.history(task);
        return history.map(h -> FrontendHistory.begin(id, h)).orElse(null);
    }

    private static void end(String id, FrontendHistory.Run run) {
        if (run == null) {
            return;
        }
        FrontendHistory.end(id, run);
        if (!run.used.get() && WARNED.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "An AG-UI run used the AI Service's own chat memory and ignored the "
                    + "conversation the client sent; build the AI Service with "
                    + "chatMemoryProvider(LangChain4jAgents.chatMemory(...)) to use it");
        }
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, LangChain4jAgents.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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

    /** The LangChain4j memory id for a request: its context id, else its task id. */
    static String conversationId(AgentRequest request) {
        if (request.contextId() != null && !request.contextId().isBlank()) {
            return request.contextId();
        }
        return request.taskId() != null ? request.taskId() : UUID.randomUUID().toString();
    }

    private static void finish(AgentTaskContext task, String answer) {
        task.artifact(ARTIFACT_NAME, List.of(new Content.Text(answer == null ? "" : answer)));
        task.complete();
    }

    private static String message(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }
}
