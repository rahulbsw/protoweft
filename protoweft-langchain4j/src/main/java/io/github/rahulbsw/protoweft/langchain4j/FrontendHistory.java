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
 * Chat memory that, while an agent run served over a stateless protocol (AG-UI) is in flight for a
 * memory id, serves that run's own conversation instead of the stored one. The application's
 * memory is left untouched by such runs. See {@link LangChain4jAgents#chatMemory(ChatMemoryProvider)}.
 */
final class FrontendHistory {

    /** Conversations of the runs in flight, by memory id. */
    private static final Map<Object, Run> RUNS = new ConcurrentHashMap<>();

    private FrontendHistory() {
    }

    /** One run's conversation; {@code used} tells whether the AI Service read it. */
    static final class Run {
        final List<ChatMessage> messages;
        final AtomicBoolean used = new AtomicBoolean();

        Run(List<ChatMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }
    }

    static Run begin(Object memoryId, List<ChatMessage> history) {
        Run run = new Run(history);
        RUNS.put(memoryId, run);
        return run;
    }

    static void end(Object memoryId, Run run) {
        RUNS.remove(memoryId, run);
    }

    static ChatMemoryProvider provider(ChatMemoryProvider fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return id -> new Memory(id, fallback);
    }

    /** Delegates to the application's memory except while a run for its id is in flight. */
    private static final class Memory implements ChatMemory {
        private final Object id;
        private final ChatMemoryProvider fallbackProvider;
        private volatile ChatMemory fallback;

        Memory(Object id, ChatMemoryProvider fallbackProvider) {
            this.id = id;
            this.fallbackProvider = fallbackProvider;
        }

        private ChatMemory fallback() {
            ChatMemory m = fallback;
            if (m == null) {
                synchronized (this) {
                    if (fallback == null) {
                        fallback = fallbackProvider.get(id);
                    }
                    m = fallback;
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
            Run run = RUNS.get(id);
            if (run != null) {
                synchronized (run) {
                    run.messages.add(message);
                }
            } else {
                fallback().add(message);
            }
        }

        @Override
        public List<ChatMessage> messages() {
            Run run = RUNS.get(id);
            if (run != null) {
                run.used.set(true);
                synchronized (run) {
                    return List.copyOf(run.messages);
                }
            }
            return fallback().messages();
        }

        @Override
        public void clear() {
            Run run = RUNS.get(id);
            if (run != null) {
                synchronized (run) {
                    run.messages.clear();
                }
            } else {
                fallback().clear();
            }
        }
    }
}
