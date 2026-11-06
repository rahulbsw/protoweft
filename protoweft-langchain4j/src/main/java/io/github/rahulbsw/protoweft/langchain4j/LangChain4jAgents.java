package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;

import java.util.List;
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
 * its {@code taskId}), so LangChain4j chat memory follows the protocol's conversation. Every task
 * ends with the answer as an artifact named {@value #ARTIFACT_NAME} and completes; an exception
 * fails the task with its message.
 */
public final class LangChain4jAgents {

    /** Name of the artifact that carries the agent's answer. */
    public static final String ARTIFACT_NAME = "response";

    private static final long POLL_MILLIS = 100;

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
            String answer;
            try {
                answer = conversation.chat(conversationId(request), request.text());
            } catch (Exception e) {
                task.fail(message(e));
                return;
            }
            finish(task, answer);
        };
    }

    /**
     * Serves a streaming agent: each partial response is reported as a {@code working} status
     * carrying that fragment, then the full answer becomes the task's artifact. All task calls are
     * made on the handler's thread; a cancelled task stops listening to the stream.
     */
    public static AgentHandler streamingHandler(StreamingConversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        return (request, task) -> {
            BlockingQueue<Object> events = new LinkedBlockingQueue<>();
            try {
                conversation.chat(conversationId(request), request.text())
                        .onPartialResponse(events::add)
                        .onCompleteResponse(events::add)
                        .onError(events::add)
                        .start();
            } catch (Exception e) {
                task.fail(message(e));
                return;
            }
            while (!task.isCancelled()) {
                Object event = events.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (event instanceof String partial) {
                    task.working(partial);
                } else if (event instanceof ChatResponse response) {
                    finish(task, response.aiMessage() == null ? null : response.aiMessage().text());
                    return;
                } else if (event instanceof Throwable error) {
                    task.fail(message(error));
                    return;
                }
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
