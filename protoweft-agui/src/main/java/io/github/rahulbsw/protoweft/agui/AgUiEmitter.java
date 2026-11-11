package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiMessage.ContentPart;

import java.util.List;
import java.util.Map;

/**
 * Emits the content of a run: what the parent agent ({@link AgUiRun}) or one subagent invocation
 * ({@link AgUiRun.Subagent}) produces. Every call is checked against the spec's sequence rules
 * before anything is sent; a call that would break them throws {@link IllegalStateException}.
 * Once the client disconnects, calls are silently dropped (see {@link AgUiRun#isCancelled()}).
 */
public interface AgUiEmitter {

    // ---- text -----------------------------------------------------------------------

    /** Opens an assistant text message, to be streamed with {@link TextMessage#delta}. */
    TextMessage startMessage();

    /** Sends a whole assistant text message. */
    default void say(String text) {
        TextMessage message = startMessage();
        message.delta(text);
        message.end();
    }

    // ---- tool calls -----------------------------------------------------------------

    /**
     * Opens a tool call the agent will run itself, to be followed by {@link #toolResult}. Use
     * {@link ToolCall#args} to stream its arguments.
     */
    ToolCall startToolCall(String toolName);

    /** Announces a whole agent-side tool call and returns its id. */
    default String toolCall(String toolName, String arguments) {
        ToolCall call = startToolCall(toolName);
        call.args(arguments);
        call.end();
        return call.id();
    }

    /** The result of an agent-side tool call: a {@code String} or a {@code List<ContentPart>}. */
    void toolResult(String toolCallId, Object content);

    default void toolResult(String toolCallId, List<ContentPart> parts) {
        toolResult(toolCallId, (Object) parts);
    }

    /**
     * Calls one of the application's own tools ({@link RunAgentInput#tools()}): announced and left
     * unanswered, the application runs it and answers on the next run. Finish the run once nothing
     * else depends on the result; {@link AgUiRun#finish()} lists it as pending. Returns the call id.
     */
    String callClientTool(String toolName, String arguments);

    // ---- reasoning ------------------------------------------------------------------

    /** Opens a reasoning span, which may hold several reasoning messages. */
    ReasoningSpan startReasoning();

    /**
     * A provider's encrypted reasoning artefact for a message or tool call; the client stores it and
     * returns it unread.
     *
     * @param subtype {@code "message"} or {@code "tool-call"}
     */
    void reasoningEncryptedValue(String subtype, String entityId, String encryptedValue);

    // ---- steps, state, activity ---------------------------------------------------------

    void startStep(String stepName);

    void finishStep(String stepName);

    /** Replaces the run's shared state; any JSON value. */
    void stateSnapshot(Object state);

    /** Amends the shared state with an RFC 6902 patch (see {@link JsonPatch#op}). */
    void stateDelta(List<Map<String, Object>> patch);

    /** Restates the whole conversation; the client reconciles messages by id. */
    void messagesSnapshot(List<AgUiMessage> messages);

    /** Creates or replaces an activity message (structured progress rendered by the UI). */
    void activity(String messageId, String activityType, Map<String, Object> content);

    /** Amends an activity created with {@link #activity} in this run. */
    void activityDelta(String messageId, String activityType, List<Map<String, Object>> patch);

    // ---- passthrough ----------------------------------------------------------------

    /** An application event; name it with a vendor prefix. */
    void custom(String name, Object value);

    /** A provider-native event, alongside (never instead of) the standard events. */
    void raw(Object event, String source);

    // ---- handles --------------------------------------------------------------------

    /** An open text or reasoning message. */
    interface TextMessage {
        String id();

        /** Appends text; empty text is ignored. */
        void delta(String text);

        void end();
    }

    /** An open agent-side tool call. */
    interface ToolCall {
        String id();

        /** Appends argument text (conventionally JSON). */
        void args(String delta);

        void end();
    }

    /** An open reasoning span. */
    interface ReasoningSpan {
        String id();

        /** Opens a reasoning message inside the span. */
        TextMessage startMessage();

        void end();
    }
}
