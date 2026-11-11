package io.github.rahulbsw.protoweft.utcp;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Fuzzes the HTTP trust boundary ({@link UrlSecurity}) with two strings: the input's first line
 * (a URL or URL template) and the rest (a second URL, also used as a header name). Classification
 * never throws; {@code ensure*} reject only with their documented {@link ProtoweftException}
 * categories; and, as the security property, wherever {@link URI} (what {@code java.net.http}
 * connects to) sees an http(s) URL with a host, {@link UrlSecurity} must classify the same host:
 * a mismatch would let plain HTTP reach a remote host, or loopback be reached past the checks.
 *
 * <p>Regression mode (the default) runs the seeds under {@code UrlSecurityFuzzTestInputs/}; fuzz
 * with {@code JAZZER_FUZZ=1} (see CONTRIBUTING.md "Fuzzing").
 */
class UrlSecurityFuzzTest {

    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9a-fx.]+");

    @FuzzTest(maxDuration = "2m")
    void urls(byte[] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        int nl = text.indexOf('\n');
        String a = nl < 0 ? text : text.substring(0, nl);
        String b = nl < 0 ? "" : text.substring(nl + 1);

        UrlSecurity.origin(a);
        boolean secure = UrlSecurity.isSecure(a);
        UrlSecurity.templateHostIsLoopback(a);
        UrlSecurity.isAuthSensitiveHeader(b);
        check(UrlSecurity.sameOrigin(a, b) == UrlSecurity.sameOrigin(b, a), "sameOrigin is symmetric");
        if (IPV4_LITERAL.matcher(a).matches()) {
            Long v = UrlSecurity.inetAton(a);
            check(v == null || (v >= 0 && v <= 0xFFFFFFFFL), "inet_aton gives a 32-bit address");
        }

        try {
            UrlSecurity.ensureSecure(a, "fuzzing", ErrorCategory.PROTOCOL);
            check(secure, "ensureSecure accepts only secure URLs");
        } catch (ProtoweftException e) {
            check(!secure && e.category() == ErrorCategory.PROTOCOL, "ensureSecure rejects only insecure URLs");
        }
        try {
            UrlSecurity.ensureLoopbackWasDeclared(a, b);
        } catch (ProtoweftException e) {
            check(e.category() == ErrorCategory.INVALID_ARGUMENTS, "ensureLoopbackWasDeclared category");
        }

        URI uri;
        try {
            uri = new URI(a);
        } catch (URISyntaxException | NumberFormatException e) {
            return; // JDK 17's URI throws NumberFormatException for an over-long IPv4 part in an IPv6 literal
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return;
        }
        boolean loopback = UrlSecurity.isLoopbackHost(uri.getHost());
        check(UrlSecurity.isLoopbackUrl(a) == loopback,
                "loopback classification agrees with java.net.URI's host '" + uri.getHost() + "'");
        check(secure == ("https".equalsIgnoreCase(scheme) || loopback),
                "isSecure agrees with java.net.URI's scheme and host '" + uri.getHost() + "'");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("UrlSecurity violates: " + what);
        }
    }
}
