package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.ContentPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Emits content for one owner: the parent agent ({@code subagentRunId == null}) or a subagent
 * invocation, whose events carry its id.
 */
abstract class EmitterScope implements AgUiEmitter {

    final String subagentRunId;

    EmitterScope(String subagentRunId) {
        this.subagentRunId = subagentRunId;
    }

    abstract RunEmitter core();

    Envelope env() {
        return Envelope.of(subagentRunId);
    }

    @Override
    public TextMessage startMessage() {
        String id = RunEmitter.newId();
        core().emit(new TextMessageStart(id, null, null, env()));
        return new Handle(id, false);
    }

    @Override
    public ToolCall startToolCall(String toolName) {
        Objects.requireNonNull(toolName, "toolName");
        String id = RunEmitter.newId();
        core().emit(new ToolCallStart(id, toolName, null, env()));
        return new ToolCall() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void args(String delta) {
                if (delta != null && !delta.isEmpty()) {
                    core().emit(new ToolCallArgs(id, delta, env()));
                }
            }

            @Override
            public void end() {
                core().emit(new ToolCallEnd(id, env()));
            }
        };
    }

    @Override
    public void toolResult(String toolCallId, Object content) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        if (!(content instanceof String) && !(content instanceof List<?> parts
                && parts.stream().allMatch(ContentPart.class::isInstance))) {
            throw new IllegalArgumentException("a tool result is a String or a List<ContentPart>");
        }
        core().emit(new ToolCallResult(RunEmitter.newId(), toolCallId, content, env()));
    }

    @Override
    public String callClientTool(String toolName, String arguments) {
        if (!core().clientTools.contains(toolName)) {
            throw new IllegalArgumentException("'" + toolName + "' is not one of the tools the application "
                    + "offered for this run: " + core().clientTools);
        }
        return toolCall(toolName, arguments == null ? "" : arguments);
    }

    @Override
    public ReasoningSpan startReasoning() {
        String id = RunEmitter.newId();
        core().emit(new ReasoningStart(id, env()));
        return new ReasoningSpan() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public TextMessage startMessage() {
                String messageId = RunEmitter.newId();
                core().emit(new ReasoningMessageStart(messageId, env()));
                return new Handle(messageId, true);
            }

            @Override
            public void end() {
                core().emit(new ReasoningEnd(id, env()));
            }
        };
    }

    @Override
    public void reasoningEncryptedValue(String subtype, String entityId, String encryptedValue) {
        if (!"message".equals(subtype) && !"tool-call".equals(subtype)) {
            throw new IllegalArgumentException("subtype must be \"message\" or \"tool-call\"");
        }
        core().emit(new ReasoningEncryptedValue(subtype, Objects.requireNonNull(entityId, "entityId"),
                Objects.requireNonNull(encryptedValue, "encryptedValue"), env()));
    }

    @Override
    public void startStep(String stepName) {
        core().emit(new StepStarted(Objects.requireNonNull(stepName, "stepName"), env()));
    }

    @Override
    public void finishStep(String stepName) {
        core().emit(new StepFinished(Objects.requireNonNull(stepName, "stepName"), env()));
    }

    @Override
    public void stateSnapshot(Object state) {
        core().emit(new StateSnapshot(Json.copy(state), env()));
    }

    @Override
    public void stateDelta(List<Map<String, Object>> patch) {
        core().emit(new StateDelta(checkedPatch(patch), env()));
    }

    @Override
    public void messagesSnapshot(List<AgUiMessage> messages) {
        core().emit(new MessagesSnapshot(messages, Envelope.NONE));
    }

    @Override
    public void activity(String messageId, String activityType, Map<String, Object> content) {
        Objects.requireNonNull(content, "content");
        synchronized (core()) {
            core().emit(new ActivitySnapshot(Objects.requireNonNull(messageId, "messageId"),
                    Objects.requireNonNull(activityType, "activityType"), copyObject(content), null, env()));
            core().activities.add(messageId);
        }
    }

    @Override
    public void activityDelta(String messageId, String activityType, List<Map<String, Object>> patch) {
        synchronized (core()) {
            if (!core().activities.contains(messageId)) {
                throw new IllegalStateException("activity " + messageId + " has no snapshot in this run");
            }
            core().emit(new ActivityDelta(messageId, Objects.requireNonNull(activityType, "activityType"),
                    checkedPatch(patch), env()));
        }
    }

    @Override
    public void custom(String name, Object value) {
        core().emit(new Custom(Objects.requireNonNull(name, "name"), Json.copy(value), env()));
    }

    @Override
    public void raw(Object event, String source) {
        core().emit(new Raw(Json.copy(event), source, env()));
    }

    public AgUiRun.Subagent startSubagent(String name, String description) {
        String id = RunEmitter.newId();
        core().emit(new SubagentStarted(id, Objects.requireNonNull(name, "name"), description, subagentRunId,
                null, null, Envelope.NONE));
        return new SubagentScope(core(), id);
    }

    private static List<Object> checkedPatch(List<Map<String, Object>> patch) {
        List<Object> ops = new ArrayList<>();
        Objects.requireNonNull(patch, "patch").forEach(op -> ops.add(Json.copy(op)));
        Decoder d = new Decoder();
        List<Object> read = JsonPatch.read(d, ops, "patch");
        if (!d.warnings().isEmpty()) {
            throw new IllegalArgumentException("not a JSON Patch: " + d.warnings());
        }
        return read;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyObject(Map<String, Object> content) {
        return (Map<String, Object>) Json.copy(content);
    }

    /** An open text or reasoning message. */
    private final class Handle implements TextMessage {
        private final String id;
        private final boolean reasoning;

        Handle(String id, boolean reasoning) {
            this.id = id;
            this.reasoning = reasoning;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void delta(String text) {
            if (text != null && !text.isEmpty()) {
                core().emit(reasoning ? new ReasoningMessageContent(id, text, env())
                        : new TextMessageContent(id, text, env()));
            }
        }

        @Override
        public void end() {
            core().emit(reasoning ? new ReasoningMessageEnd(id, env()) : new TextMessageEnd(id, env()));
        }
    }

    /** A subagent invocation's view: its events carry its id. */
    private static final class SubagentScope extends EmitterScope implements AgUiRun.Subagent {
        private final RunEmitter core;

        SubagentScope(RunEmitter core, String id) {
            super(id);
            this.core = core;
        }

        @Override
        RunEmitter core() {
            return core;
        }

        @Override
        public String id() {
            return subagentRunId;
        }

        @Override
        public void finish(Object result) {
            core.emit(new SubagentFinished(subagentRunId, Json.copy(result), null, Envelope.NONE));
        }

        @Override
        public void suspend(List<String> interruptIds) {
            core.emit(new SubagentFinished(subagentRunId, null, new SubagentOutcome.Suspended(interruptIds),
                    Envelope.NONE));
        }

        @Override
        public void fail(String message, String code) {
            core.emit(new SubagentError(subagentRunId, Objects.requireNonNull(message, "message"), code,
                    Envelope.NONE));
        }
    }
}
