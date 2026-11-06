package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.agent.Agent;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Serves an ARGI {@link Agent} (typically a {@code ReactAgent}) through any Protoweft agent
 * server, for example the A2A server, with ARGI's human-in-the-loop interrupts as
 * {@code input-required}.
 *
 * <pre>{@code
 * ReactAgent agent = ReactAgent.builder()
 *         .name("payments").model(chatModel)
 *         .toolCallbackProviders(ArgiTools.callbackProvider(registry))
 *         .hooks(HumanInTheLoopHook.builder().approvalOn("bank__transfer", "Moves money").build())
 *         .saver(new MemorySaver())                 // required: interrupted runs resume from it
 *         .build();
 * AgentHandler handler = ArgiAgents.handler(agent);
 * // hand it to an Protoweft agent server, e.g. A2aAgentServer.builder(handler, descriptor)
 * }</pre>
 *
 * <ul>
 *   <li><b>Runs.</b> Each message is one ARGI run on thread id = the A2A task id (configurable),
 *       with the message text as the user message and the caller in the run's metadata under
 *       {@link ArgiTools#CALLER_KEY}, from where ARGI copies it into every tool's context.</li>
 *   <li><b>Answers.</b> The last assistant message (or the agent's {@code outputKey} value)
 *       becomes an artifact named {@value #ARTIFACT_NAME} and the task completes; an exception
 *       fails the task.</li>
 *   <li><b>Approvals.</b> A run that ends in an {@code InterruptionMetadata} with pending tool
 *       feedbacks pauses the task with {@code requireInput}, listing each pending call (name, id,
 *       arguments, description). The caller's reply on the same task ({@code "approve"},
 *       {@code "reject <reason>"}, or a JSON data part
 *       {@code {"decision":"approved|rejected|edited","id":"...","arguments":{...}}} per call)
 *       becomes ARGI feedback ({@code APPROVED}/{@code REJECTED}/{@code EDITED}), passed under
 *       {@code RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY} to resume the same thread. Until every
 *       pending call is decided the task asks again for the rest.</li>
 * </ul>
 *
 * <p>The agent must have a checkpoint saver ({@code .saver(new MemorySaver())}), because ARGI
 * resumes from it; building a handler for an agent without one fails.
 */
public final class ArgiAgents {

    /** Name of the artifact that carries the agent's answer. */
    public static final String ARTIFACT_NAME = "response";

    private ArgiAgents() {
    }

    /** Serves an ARGI agent with one blocking run per message. */
    public static AgentHandler handler(Agent agent) {
        return builder(agent).build();
    }

    /** Serves an ARGI agent streaming: model text chunks become {@code working} statuses. */
    public static AgentHandler streamingHandler(Agent agent) {
        return builder(agent).streaming(true).build();
    }

    public static Builder builder(Agent agent) {
        return new Builder(agent);
    }

    /** Configures the {@link AgentHandler} for an ARGI agent. */
    public static final class Builder {

        final Agent agent;
        boolean streaming;
        Function<AgentRequest, String> threadId = AgentRequest::taskId;
        BiConsumer<AgentRequest, RunnableConfig.Builder> customizer = (request, config) -> { };

        private Builder(Agent agent) {
            this.agent = Objects.requireNonNull(agent, "agent");
        }

        /** {@code true}: run with {@code Agent.stream} and report model text chunks as {@code working} statuses. */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /**
         * The ARGI thread id for a request (default: the task id). Using the context id instead
         * keeps one ARGI conversation across the tasks of an A2A context.
         */
        public Builder threadId(Function<AgentRequest, String> threadId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId");
            return this;
        }

        /** Adjusts every run's {@code RunnableConfig} (metadata, store, executors) after the defaults. */
        public Builder config(BiConsumer<AgentRequest, RunnableConfig.Builder> customizer) {
            this.customizer = Objects.requireNonNull(customizer, "customizer");
            return this;
        }

        /**
         * @throws IllegalArgumentException if the agent has no checkpoint saver
         */
        public AgentHandler build() {
            return new ArgiAgentHandler(this);
        }
    }
}
