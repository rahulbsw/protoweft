package io.github.rahulbsw.protoweft.rap;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which {@code callback_url}s a {@link RapToolServer} will post to. Invocations whose callback URL
 * is refused are rejected with HTTP 400, so an authenticated caller cannot make the server send
 * requests to arbitrary internal addresses.
 *
 * <p>{@link #DEFAULT}: {@code https} to any host except literal link-local, wildcard or multicast
 * addresses (cloud metadata services live at link-local addresses), or {@code http} to
 * {@code localhost} or a literal loopback address. Host names are not resolved.
 */
@FunctionalInterface
public interface RapCallbackPolicy {

    /** {@code https} (not to link-local, wildcard or multicast literals), or {@code http} to loopback. */
    RapCallbackPolicy DEFAULT = RapCallbackPolicy::httpsOrLoopback;

    boolean allows(URI callbackUrl);

    /** This policy, plus {@code http} or {@code https} to exactly these hosts (names or literal addresses). */
    default RapCallbackPolicy allowingHosts(String... hosts) {
        Set<String> allowed = Arrays.stream(hosts).map(h -> strip(Objects.requireNonNull(h, "host")))
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        return uri -> allows(uri) || isHttp(uri) && uri.getHost() != null
                && allowed.contains(strip(uri.getHost()).toLowerCase(Locale.ROOT));
    }

    /** The {@link #DEFAULT} rule. */
    static boolean httpsOrLoopback(URI uri) {
        if (!isHttp(uri) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return false;
        }
        String host = strip(uri.getHost()).toLowerCase(Locale.ROOT);
        InetAddress literal = IpLiterals.parse(host);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return literal == null || !(literal.isLinkLocalAddress() || literal.isAnyLocalAddress()
                    || literal.isMulticastAddress());
        }
        return host.equals("localhost") || literal != null && literal.isLoopbackAddress();
    }

    private static boolean isHttp(URI uri) {
        return uri.isAbsolute() && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static String strip(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
