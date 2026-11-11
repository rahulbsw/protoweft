package io.github.rahulbsw.protoweft.agui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one message an application sends to an agent to start a run (spec "Run Input").
 *
 * @param protocolVersion the sender's {@code MAJOR.MINOR}, or {@code null} for a peer that predates it
 * @param state           the state the run starts from; {@code null} means the empty object
 * @param messages        the whole conversation so far
 * @param tools           the application's own (frontend) tools offered for this run
 * @param context         entries the agent should make available to the model
 * @param forwardedProps  an application-specific payload passed through untouched, or {@code null}
 * @param resume          answers to the interrupts of the run being continued
 */
public record RunAgentInput(String threadId, String runId, String protocolVersion, String parentRunId,
                            Object state, List<AgUiMessage> messages, List<Tool> tools, List<Context> context,
                            Object forwardedProps, List<ResumeEntry> resume) {

    public RunAgentInput {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(runId, "runId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = tools == null ? List.of() : List.copyOf(tools);
        context = context == null ? List.of() : List.copyOf(context);
        resume = resume == null ? List.of() : List.copyOf(resume);
    }

    /** An input with only the required fields, speaking this implementation's protocol version. */
    public static RunAgentInput of(String threadId, String runId, List<AgUiMessage> messages) {
        return new RunAgentInput(threadId, runId, AgUiProtocol.VERSION, null, null, messages, null, null, null, null);
    }

    /**
     * A tool the application offers and executes itself.
     *
     * @param parameters JSON Schema of the arguments, or {@code null} for none
     */
    public record Tool(String name, String description, Map<String, Object> parameters, Map<String, Object> metadata) {
        public Tool {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
        }
    }

    /** One piece of grounding for the model. */
    public record Context(String description, String value) {
        public Context {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * The answer to one interrupt.
     *
     * @param status  {@code "resolved"} or {@code "cancelled"} (abandoned)
     * @param payload the answer the agent asked for, any JSON value
     */
    public record ResumeEntry(String interruptId, String status, Object payload, Map<String, Object> metadata) {
        public static final String RESOLVED = "resolved";
        public static final String CANCELLED = "cancelled";

        public ResumeEntry {
            Objects.requireNonNull(interruptId, "interruptId");
            if (!RESOLVED.equals(status) && !CANCELLED.equals(status)) {
                throw new IllegalArgumentException("resume status must be resolved or cancelled: " + status);
            }
        }
    }
}
