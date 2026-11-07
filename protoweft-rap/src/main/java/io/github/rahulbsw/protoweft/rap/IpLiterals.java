package io.github.rahulbsw.protoweft.rap;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Parses IP address literals without DNS lookups. */
final class IpLiterals {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private IpLiterals() {
    }

    /** The address for a dotted IPv4 or an IPv6 literal (brackets stripped), else {@code null}. */
    static InetAddress parse(String host) {
        boolean v4 = IPV4.matcher(host).matches();
        if (!v4 && !host.contains(":")) {
            return null;
        }
        if (v4) {
            for (String part : host.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
