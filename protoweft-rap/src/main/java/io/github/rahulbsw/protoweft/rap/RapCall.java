package io.github.rahulbsw.protoweft.rap;

import java.util.Objects;

/**
 * A client-side RAP tool call, as shown to {@link RapPromptHandler}s and recovery listeners.
 *
 * @param operation  the tool name
 * @param toolCallId the call's {@code id}
 * @param threadId   the call's {@code group_id}
 * @param serverUrl  the tool server's base URL
 */
public record RapCall(String operation, String toolCallId, String threadId, String serverUrl) {

    public RapCall {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(serverUrl, "serverUrl");
    }
}
