package io.github.rahulbsw.protoweft.agui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One AG-UI protocol event (spec 1.0, the 31 types of {@code EventType}). Fields follow the official
 * schema; an optional field with no value is {@code null}. JSON values (state, results, payloads)
 * are plain Java values: {@code Map<String, Object>}, {@code List<Object>}, {@code String},
 * {@code Number}, {@code Boolean} or {@code null}.
 *
 * <p>Every event carries an {@link Envelope}: the schema's general fields, plus the subagent
 * attribution on events that can carry it.
 */
public sealed interface AgUiEvent {

    /** The wire discriminator, for example {@code "TEXT_MESSAGE_START"}. */
    String type();

    Envelope envelope();

    /**
     * General fields shared by every event.
     *
     * @param timestamp     creation time, informational only (milliseconds since the epoch by convention)
     * @param rawEvent      the provider-native event this one was translated from, or {@code null}
     * @param metadata      open metadata, or {@code null}
     * @param subagentRunId the subagent invocation that produced the event; {@code null} for the parent
     *                      agent and on events that cannot be attributed
     */
    record Envelope(Long timestamp, Object rawEvent, Map<String, Object> metadata, String subagentRunId) {

        public Envelope {
            if (timestamp != null && Math.abs(timestamp) > Decoder.MAX_SAFE) {
                throw new IllegalArgumentException("timestamp must be within +/-(2^53 - 1)");
            }
        }

        /** No general fields. */
        public static final Envelope NONE = new Envelope(null, null, null, null);

        public static Envelope of(String subagentRunId) {
            return subagentRunId == null ? NONE : new Envelope(null, null, null, subagentRunId);
        }
    }

    // ---- Runs and steps -------------------------------------------------------------------

    record RunStarted(String threadId, String runId, String protocolVersion, String parentRunId,
                      RunAgentInput input, Envelope envelope) implements AgUiEvent {
        public RunStarted {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(runId, "runId");
        }
        public String type() { return "RUN_STARTED"; }
    }

    record RunFinished(String threadId, String runId, Object result, RunOutcome outcome, List<TokenUsage> usage,
                       Envelope envelope) implements AgUiEvent {
        public RunFinished {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(runId, "runId");
            usage = usage == null ? null : List.copyOf(usage);
        }
        public String type() { return "RUN_FINISHED"; }
    }

    record RunError(String message, String code, List<TokenUsage> usage, Envelope envelope) implements AgUiEvent {
        public RunError {
            Objects.requireNonNull(message, "message");
            usage = usage == null ? null : List.copyOf(usage);
        }
        public String type() { return "RUN_ERROR"; }
    }

    record StepStarted(String stepName, Envelope envelope) implements AgUiEvent {
        public String type() { return "STEP_STARTED"; }
    }

    record StepFinished(String stepName, Envelope envelope) implements AgUiEvent {
        public String type() { return "STEP_FINISHED"; }
    }

    // ---- Text messages --------------------------------------------------------------------

    /** @param role {@code null} means {@code assistant} */
    record TextMessageStart(String messageId, String role, String name, Envelope envelope) implements AgUiEvent {
        public String type() { return "TEXT_MESSAGE_START"; }
    }

    record TextMessageContent(String messageId, String delta, Envelope envelope) implements AgUiEvent {
        public String type() { return "TEXT_MESSAGE_CONTENT"; }
    }

    record TextMessageEnd(String messageId, Envelope envelope) implements AgUiEvent {
        public String type() { return "TEXT_MESSAGE_END"; }
    }

    record TextMessageChunk(String messageId, String role, String delta, String name, Envelope envelope)
            implements AgUiEvent {
        public String type() { return "TEXT_MESSAGE_CHUNK"; }
    }

    // ---- Tool calls -----------------------------------------------------------------------

    record ToolCallStart(String toolCallId, String toolCallName, String parentMessageId, Envelope envelope)
            implements AgUiEvent {
        public String type() { return "TOOL_CALL_START"; }
    }

    record ToolCallArgs(String toolCallId, String delta, Envelope envelope) implements AgUiEvent {
        public String type() { return "TOOL_CALL_ARGS"; }
    }

    record ToolCallEnd(String toolCallId, Envelope envelope) implements AgUiEvent {
        public String type() { return "TOOL_CALL_END"; }
    }

    record ToolCallChunk(String toolCallId, String toolCallName, String parentMessageId, String delta,
                         Envelope envelope) implements AgUiEvent {
        public String type() { return "TOOL_CALL_CHUNK"; }
    }

    /** @param content a {@code String} or a {@code List<ContentPart>} */
    record ToolCallResult(String messageId, String toolCallId, Object content, Envelope envelope)
            implements AgUiEvent {
        public String type() { return "TOOL_CALL_RESULT"; }
    }

    // ---- Reasoning ------------------------------------------------------------------------

    record ReasoningStart(String messageId, Envelope envelope) implements AgUiEvent {
        public String type() { return "REASONING_START"; }
    }

    record ReasoningEnd(String messageId, Envelope envelope) implements AgUiEvent {
        public String type() { return "REASONING_END"; }
    }

    record ReasoningMessageStart(String messageId, Envelope envelope) implements AgUiEvent {
        public String type() { return "REASONING_MESSAGE_START"; }
    }

    record ReasoningMessageContent(String messageId, String delta, Envelope envelope) implements AgUiEvent {
        public String type() { return "REASONING_MESSAGE_CONTENT"; }
    }

    record ReasoningMessageEnd(String messageId, Envelope envelope) implements AgUiEvent {
        public String type() { return "REASONING_MESSAGE_END"; }
    }

    record ReasoningMessageChunk(String messageId, String delta, Envelope envelope) implements AgUiEvent {
        public String type() { return "REASONING_MESSAGE_CHUNK"; }
    }

    /** @param subtype {@code "message"} or {@code "tool-call"}: what {@code entityId} names */
    record ReasoningEncryptedValue(String subtype, String entityId, String encryptedValue, Envelope envelope)
            implements AgUiEvent {
        public String type() { return "REASONING_ENCRYPTED_VALUE"; }
    }

    // ---- State ----------------------------------------------------------------------------

    /** @param snapshot any JSON value, including {@code null} */
    record StateSnapshot(Object snapshot, Envelope envelope) implements AgUiEvent {
        public String type() { return "STATE_SNAPSHOT"; }
    }

    /** @param delta an RFC 6902 JSON Patch: a list of operation objects */
    record StateDelta(List<Object> delta, Envelope envelope) implements AgUiEvent {
        public String type() { return "STATE_DELTA"; }
    }

    record MessagesSnapshot(List<AgUiMessage> messages, Envelope envelope) implements AgUiEvent {
        public MessagesSnapshot {
            messages = List.copyOf(messages);
        }
        public String type() { return "MESSAGES_SNAPSHOT"; }
    }

    // ---- Activity -------------------------------------------------------------------------

    /** @param replace {@code null} means {@code true} */
    record ActivitySnapshot(String messageId, String activityType, Map<String, Object> content, Boolean replace,
                            Envelope envelope) implements AgUiEvent {
        public String type() { return "ACTIVITY_SNAPSHOT"; }
    }

    record ActivityDelta(String messageId, String activityType, List<Object> patch, Envelope envelope)
            implements AgUiEvent {
        public String type() { return "ACTIVITY_DELTA"; }
    }

    // ---- Subagents ------------------------------------------------------------------------

    record SubagentStarted(String subagentRunId, String name, String description, String parentSubagentRunId,
                           String parentToolCallId, String parentMessageId, Envelope envelope) implements AgUiEvent {
        public String type() { return "SUBAGENT_STARTED"; }
    }

    /** @param outcome {@code null} means success */
    record SubagentFinished(String subagentRunId, Object result, SubagentOutcome outcome, Envelope envelope)
            implements AgUiEvent {
        public String type() { return "SUBAGENT_FINISHED"; }
    }

    record SubagentError(String subagentRunId, String message, String code, Envelope envelope) implements AgUiEvent {
        public String type() { return "SUBAGENT_ERROR"; }
    }

    // ---- Passthrough ----------------------------------------------------------------------

    record Raw(Object event, String source, Envelope envelope) implements AgUiEvent {
        public String type() { return "RAW"; }
    }

    record Custom(String name, Object value, Envelope envelope) implements AgUiEvent {
        public String type() { return "CUSTOM"; }
    }

    // ---- Outcomes -------------------------------------------------------------------------

    /** How a run that did not fail ended. */
    sealed interface RunOutcome {

        /** @param pendingToolCallIds frontend tool calls left unanswered, in call order; {@code null} if not stated */
        record Success(List<String> pendingToolCallIds) implements RunOutcome {
            public Success {
                pendingToolCallIds = pendingToolCallIds == null ? null : List.copyOf(pendingToolCallIds);
            }
        }

        record Interrupted(List<Interrupt> interrupts) implements RunOutcome {
            public Interrupted {
                interrupts = List.copyOf(interrupts);
                if (interrupts.isEmpty()) {
                    throw new IllegalArgumentException("an interrupt outcome carries at least one interrupt");
                }
            }
        }

        record Cancelled() implements RunOutcome {
        }
    }

    /** How a subagent invocation ended. */
    sealed interface SubagentOutcome {

        record Success() implements SubagentOutcome {
        }

        record Suspended(List<String> interruptIds) implements SubagentOutcome {
            public Suspended {
                interruptIds = interruptIds == null ? null : List.copyOf(interruptIds);
            }
        }
    }

    /**
     * Something a run is waiting for (spec "Interrupts and Resume").
     *
     * @param reason         an open string, for example {@code "approval"}
     * @param responseSchema JSON Schema of the expected answer, or {@code null}
     * @param expiresAt      conventionally ISO 8601, or {@code null}
     */
    record Interrupt(String id, String reason, String message, String toolCallId, Map<String, Object> responseSchema,
                     String expiresAt, Map<String, Object> metadata, String subagentRunId) {
        public Interrupt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
        }

        public static Interrupt of(String id, String reason, String message) {
            return new Interrupt(id, reason, message, null, null, null, null, null);
        }
    }

    /** Token usage for one provider and model; an absent count is {@code null}, never zero. */
    record TokenUsage(String provider, String model, Long inputTokens, Long outputTokens, Long totalTokens,
                      Long reasoningTokens, Long cachedInputTokens, Long cacheWriteInputTokens) {
        public TokenUsage {
            for (Long count : new Long[] {inputTokens, outputTokens, totalTokens, reasoningTokens, cachedInputTokens,
                    cacheWriteInputTokens}) {
                if (count != null && (count < 0 || count > Decoder.MAX_SAFE)) {
                    throw new IllegalArgumentException("token counts must be within 0 and 2^53 - 1: " + count);
                }
            }
        }
    }
}
