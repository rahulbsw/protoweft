package io.github.rahulbsw.protoweft.oauth;

/** A presented access token is not valid. The message must not contain the token. */
public class InvalidTokenException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
