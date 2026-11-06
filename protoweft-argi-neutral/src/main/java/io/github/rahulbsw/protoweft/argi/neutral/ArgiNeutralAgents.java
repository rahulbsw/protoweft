package io.github.rahulbsw.protoweft.argi.neutral;

import io.github.rahulbsw.protoweft.core.agent.AgentDescriptor;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.agentic.ai.agent.Agent;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Serves an ARGI neutral {@link Agent} (for example a {@code ReActAgent}) through any Protoweft
 * agent server, for example the A2A server, with the full task lifecycle.
 *
 * <pre>{@code
 * Agent agent = ReActAgent.builder(chatClient).tools(ArgiNeutralTools.registry(tools))
 *         .humanInTheLoop(approvals).authorizationPolicy(policy).build();
 * AgentHandler handler = ArgiNeutralAgents.handler(agent);
 * // hand a descriptor + handler to an Protoweft agent server, e.g. protoweft-a2a's A2aAgentServer
 * }</pre>
 *
 * <p>Each message runs {@link Agent#stream}: text deltas and progress become {@code working}
 * statuses, a {@code COMPLETED} outcome's last assistant message becomes the artifact
 * {@value #ARTIFACT_NAME} and the task completes. A {@code SUSPENDED} outcome becomes
 * {@code requireInput} with the suspension's prompts, and the run's resume token, messages and
 * outputs are kept under the task id. When the caller answers on the same task
 * ({@link AgentRequest#resumed()}), the agent is resumed with that token, the kept messages, the
 * kept outputs as inputs and the reply under {@value #REPLY_KEY} (plus a JSON part's value under
 * {@value #REPLY_DATA_KEY}). Errors fail the task; cancelling the task cancels the subscription.
 */
public final class ArgiNeutralAgents {

    /** Name of the artifact that carries the agent's answer. */
    public static final String ARTIFACT_NAME = "response";
    /** Agent input carrying the caller's reply text when a suspended run resumes. */
    public static final String REPLY_KEY = "protoweft.reply";
    /** Agent input carrying the value of the reply's JSON part, if any, when a suspended run resumes. */
    public static final String REPLY_DATA_KEY = "protoweft.replyData";
    /** Agent input carrying the Protoweft task id. */
    public static final String TASK_ID_KEY = "protoweft.taskId";
    /** Agent input carrying the Protoweft context id. */
    public static final String CONTEXT_ID_KEY = "protoweft.contextId";
    /** Output key a {@code ReActAgent} uses for validated structured output; added to the artifact as JSON. */
    public static final String STRUCTURED_OUTPUT_KEY = "structuredOutput";
    /** Prefix of every agent input this module adds (see {@link #withoutProtoweftAttributes}). */
    public static final String ATTRIBUTE_PREFIX = "protoweft.";
    /** Authority of the default trusted context's tenant and principal. */
    public static final String AUTHORITY = "protoweft";

    private ArgiNeutralAgents() {
    }

    /** Serves {@code agent} with the defaults. */
    public static AgentHandler handler(Agent agent) {
        return builder(agent).build();
    }

    public static Builder builder(Agent agent) {
        return new Builder(agent);
    }

    /**
     * Wraps a neutral {@link ChatClient} so the {@code protoweft.*} agent inputs this handler adds
     * (caller, task and context id, reply) are removed from each {@link ChatRequest#attributes()}.
     * ARGI's {@code ReActAgent} passes its inputs both as tool context (where Protoweft tools read
     * the caller) and as chat attributes; some ARGI adapters (Google ADK, LangChain4j streaming)
     * reject any attribute, so wrap their clients with this.
     */
    public static ChatClient withoutProtoweftAttributes(ChatClient delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new ChatClient() {
            @Override
            public CompletionStage<ChatResponse> call(ChatRequest request) {
                return delegate.call(strip(request));
            }

            @Override
            public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
                return delegate.stream(strip(request));
            }

            @Override
            public ChatClientCapabilities capabilities() {
                return delegate.capabilities();
            }
        };
    }

    private static ChatRequest strip(ChatRequest request) {
        Map<String, Object> kept = request.attributes().entrySet().stream()
                .filter(e -> !e.getKey().startsWith(ATTRIBUTE_PREFIX))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return kept.size() == request.attributes().size() ? request
                : new ChatRequest(request.messages(), request.tools(), request.options(), kept);
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

    /**
     * A run suspended for input, kept until the caller answers on the same task.
     *
     * @param resumeToken the agent's opaque continuation
     * @param messages    the suspended outcome's messages, sent again on resume
     * @param outputs     the suspended outcome's outputs, sent again as inputs on resume
     * @param prompts     what the agent asked for
     */
    public record SuspendedRun(String resumeToken, List<ChatMessage> messages, Map<String, Object> outputs,
                               Map<String, Object> prompts) {
        public SuspendedRun {
            Objects.requireNonNull(resumeToken, "resumeToken");
            messages = List.copyOf(messages);
            outputs = Map.copyOf(outputs);
            prompts = Map.copyOf(prompts);
        }
    }

    /**
     * The default execution context: tenant {@code protoweft/default}, principal
     * {@code protoweft/<caller name>}, run id = task id, correlation id = context id (else the run
     * id), no deadline or budget, the system clock.
     */
    static AgentExecutionContext defaultContext(AgentRequest request) {
        String runId = present(request.taskId()) ? request.taskId() : UUID.randomUUID().toString();
        TrustedExecutionContext trusted = TrustedExecutionContext.builder()
                .tenant(new TenantReference(AUTHORITY, "default"))
                .principal(new PrincipalReference(AUTHORITY, request.caller().name()))
                .runId(runId)
                .correlationId(present(request.contextId()) ? request.contextId() : runId)
                .build();
        return new AgentExecutionContext(trusted, Clock.systemUTC());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** Configures the {@link AgentHandler} for an ARGI agent. */
    public static final class Builder {

        final Agent agent;
        Function<AgentRequest, AgentExecutionContext> executionContext = ArgiNeutralAgents::defaultContext;
        BiFunction<AgentRequest, io.github.agentic.ai.agent.AgentRequest, io.github.agentic.ai.agent.AgentRequest>
                requestCustomizer = (request, argi) -> argi;
        Map<String, SuspendedRun> suspensions = new ConcurrentHashMap<>();
        boolean replayHistory = true;

        private Builder(Agent agent) {
            this.agent = Objects.requireNonNull(agent, "agent");
        }

        /**
         * Creates the execution context per message (trusted tenant/principal, deadline, budget);
         * the handler closes it when the run ends. Replaces {@link ArgiNeutralAgents#defaultContext}.
         */
        public Builder executionContext(Function<AgentRequest, AgentExecutionContext> factory) {
            this.executionContext = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /** Adjusts every ARGI request (fresh or resumed) after the defaults are applied. */
        public Builder requestCustomizer(BiFunction<AgentRequest, io.github.agentic.ai.agent.AgentRequest,
                io.github.agentic.ai.agent.AgentRequest> customizer) {
            this.requestCustomizer = Objects.requireNonNull(customizer, "customizer");
            return this;
        }

        /**
         * Where suspended runs are kept, keyed by task id (default: an in-memory concurrent map).
         * Supply a shared or persistent map when tasks may resume on another node or after a restart.
         */
        public Builder suspensions(Map<String, SuspendedRun> store) {
            this.suspensions = Objects.requireNonNull(store, "store");
            return this;
        }

        /**
         * {@code true} (the default): a fresh run gets the task's earlier messages before the new
         * one (user messages as USER, agent messages as ASSISTANT). {@code false}: only the new one.
         */
        public Builder replayHistory(boolean replay) {
            this.replayHistory = replay;
            return this;
        }

        public AgentHandler build() {
            return new NeutralAgentHandler(this);
        }
    }
}
