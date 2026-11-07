package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.util.Objects;

/**
 * A RAP tool asked for user authorization ({@code oauth} message) and no {@link RapPromptHandler}
 * is configured to show the user its URL. Category {@link ErrorCategory#AUTHENTICATION}. If the
 * user visits {@link #authUrl()} anyway, the tool's eventual result goes to
 * {@link RapClientConnector#onRecoveredResult}.
 */
public final class RapAuthorizationRequiredException extends ProtoweftException {

    private final transient RapCall call;
    private final String authUrl;

    public RapAuthorizationRequiredException(RapCall call, String authUrl) {
        super(ErrorCategory.AUTHENTICATION, ProtocolId.RAP,
                "tool '" + call.operation() + "' requires user authorization at " + authUrl);
        this.call = Objects.requireNonNull(call, "call");
        this.authUrl = Objects.requireNonNull(authUrl, "authUrl");
    }

    /** The URL the user must visit to grant access. Show it to the user; don't store it. */
    public String authUrl() {
        return authUrl;
    }

    public RapCall call() {
        return call;
    }
}
