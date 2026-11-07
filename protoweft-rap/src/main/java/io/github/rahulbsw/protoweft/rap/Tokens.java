package io.github.rahulbsw.protoweft.rap;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** Random identifiers. */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** 256 random bits, URL-safe: the secret in a callback URL. */
    static String newCallbackToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String newToolCallId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
