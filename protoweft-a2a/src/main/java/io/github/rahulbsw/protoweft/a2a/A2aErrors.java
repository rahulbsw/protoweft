package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.A2AClientJSONError;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/** Maps a2a-java client failures onto the neutral {@link ErrorCategory} model (table in SPEC.md). */
final class A2aErrors {

    private A2aErrors() {
    }

    /**
     * @param operation the A2A method (for example {@code SendMessage}) or {@code agent-card}
     * @param connecting whether the failure happened while starting the connector
     */
    static ProtoweftException translate(Throwable e, String operation, boolean connecting) {
        if (e instanceof ProtoweftException already) {
            return already;
        }
        String prefix = "A2A " + operation + " failed: ";
        A2AClientHTTPError http = findCause(e, A2AClientHTTPError.class);
        if (http != null) {
            int status = http.getCode();
            ErrorCategory category = status == 401 || status == 403 ? ErrorCategory.AUTHENTICATION
                    : connecting ? ErrorCategory.CONNECTION : ErrorCategory.PROTOCOL;
            return new ProtoweftException(category, ProtocolId.A2A, Integer.toString(status),
                    prefix + "HTTP " + status, e);
        }
        A2AError rpc = findCause(e, A2AError.class);
        if (rpc != null) {
            int code = rpc.getCode();
            return new ProtoweftException(categoryFor(code), ProtocolId.A2A, Integer.toString(code),
                    prefix + rpc.getMessage(), e);
        }
        if (findCause(e, TimeoutException.class) != null) {
            return new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.A2A, "A2A " + operation + " timed out", e);
        }
        if (findCause(e, A2AClientJSONError.class) != null) {
            return new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.A2A, prefix + rootMessage(e), e);
        }
        if (findCause(e, IOException.class) != null || connecting) {
            return new ProtoweftException(ErrorCategory.CONNECTION, ProtocolId.A2A, prefix + rootMessage(e), e);
        }
        return new ProtoweftException(ErrorCategory.PROTOCOL, ProtocolId.A2A, prefix + rootMessage(e), e);
    }

    static ErrorCategory categoryFor(int code) {
        A2AErrorCodes known = A2AErrorCodes.fromCode(code);
        if (known == null) {
            return ErrorCategory.PROTOCOL;
        }
        return switch (known) {
            case VERSION_NOT_SUPPORTED -> ErrorCategory.VERSION_MISMATCH;
            // Unknown and not-owned tasks are indistinguishable by design (the server hides
            // other callers' tasks), so both are an invalid task id from the caller's view.
            case TASK_NOT_FOUND, INVALID_PARAMS, CONTENT_TYPE_NOT_SUPPORTED -> ErrorCategory.INVALID_ARGUMENTS;
            case METHOD_NOT_FOUND, UNSUPPORTED_OPERATION, PUSH_NOTIFICATION_NOT_SUPPORTED,
                 EXTENDED_AGENT_CARD_NOT_CONFIGURED, EXTENSION_SUPPORT_REQUIRED -> ErrorCategory.UNSUPPORTED;
            default -> ErrorCategory.PROTOCOL;
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
