package io.agentweave.oauth;

/** How the client authenticates at the token endpoint (RFC 7591 §2 {@code token_endpoint_auth_method}). */
public enum TokenEndpointAuthMethod {

    /** HTTP Basic with client id and secret. */
    CLIENT_SECRET_BASIC("client_secret_basic"),

    /** Client id and secret in the form body. */
    CLIENT_SECRET_POST("client_secret_post"),

    /** Public client: {@code client_id} in the body, no secret. */
    NONE("none");

    private final String value;

    TokenEndpointAuthMethod(String value) {
        this.value = value;
    }

    /** The registered value, for example {@code client_secret_basic}. */
    public String value() {
        return value;
    }

    static TokenEndpointAuthMethod fromValue(String value) {
        for (TokenEndpointAuthMethod m : values()) {
            if (m.value.equals(value)) {
                return m;
            }
        }
        return null;
    }
}
