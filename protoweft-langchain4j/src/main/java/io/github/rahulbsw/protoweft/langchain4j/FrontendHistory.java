package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat memory for one AI Service served over a stateless protocol (AG-UI): while a run of that
 * service is in flight for a conversation, its memory serves the conversation the client sent
 * instead of the stored one; otherwise it delegates to the application's memory, which such runs
 * never write. Create it with {@link LangChain4jAgents#chatMemory(ChatMemoryProvider)}, build the AI
 * Service with it, and pass the same instance to the handler, so runs are scoped to that service.
 */
public final class FrontendHistory implements ChatMemoryProvider {

    /** This service's runs in flight, by memory id. */
    private final Map<Object, Run> runs = new ConcurrentHashMap<>();
    private final ChatMemoryProvider fallback;

    FrontendHistory(ChatMemoryProvider fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public ChatMemory get(Object memoryId) {
        return new Memory(memoryId);
    }

    /** One run's conversation; {@code used} tells whether the AI Service read it. */
    static final class Run {
        final List<ChatMessage> messages;
        final AtomicBoolean used = new AtomicBoolean();

        Run(List<ChatMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }
    }

    /**
     * Makes {@code history} the memory of {@code memoryId} for one run.
     *
     * @throws IllegalStateException if a run for that conversation is already in flight: the two
     *                               would share one memory
     */
    Run begin(Object memoryId, List<ChatMessage> history) {
        Run run = new Run(history);
        if (runs.putIfAbsent(memoryId, run) != null) {
            throw new IllegalStateException("another run for conversation " + memoryId + " is still in progress");
        }
        return run;
    }

    void end(Object memoryId, Run run) {
        runs.remove(memoryId, run);
    }

    /** Delegates to the application's memory except while a run for its id is in flight. */
    private final class Memory implements ChatMemory {
        private final Object id;
        private volatile ChatMemory stored;

        Memory(Object id) {
            this.id = id;
        }

        private ChatMemory stored() {
            ChatMemory m = stored;
            if (m == null) {
                synchronized (this) {
                    if (stored == null) {
                        stored = fallback.get(id);
                    }
                    m = stored;
                }
            }
            return m;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public void add(ChatMessage message) {
            Run run = runs.get(id);
            if (run != null) {
                synchronized (run) {
                    run.messages.add(message);
                }
            } else {
                stored().add(message);
            }
        }

        @Override
        public List<ChatMessage> messages() {
            Run run = runs.get(id);
            if (run != null) {
                run.used.set(true);
                synchronized (run) {
                    return List.copyOf(run.messages);
                }
            }
            return stored().messages();
        }

        @Override
        public void clear() {
            Run run = runs.get(id);
            if (run != null) {
                synchronized (run) {
                    run.messages.clear();
                }
            } else {
                stored().clear();
            }
        }
    }
}
