package io.agentweave.utcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.ProtocolId;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP trust boundary of {@code utcp_http/_security.py}: HTTPS or literal loopback only,
 * no remote manual pointing at loopback, no argument-chosen loopback host, credential scrubbing
 * on cross-origin redirects. Host classification never consults DNS.
 */
final class UrlSecurity {

    private static final Pattern ORIGIN = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9+.\\-]*)://([^/?#]*)");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^[0-9a-fx.]+$");

    private static final Set<String> AUTH_SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "www-authenticate",
            "x-api-key", "api-key", "x-auth-token", "x-access-token", "x-csrf-token", "x-xsrf-token",
            "x-amz-security-token", "x-goog-api-key",
            "x_api_key", "api_key", "x_auth_token", "x_access_token", "x_csrf_token", "x_xsrf_token",
            "apikey", "xapikey", "authtoken", "xauthtoken", "accesstoken", "xaccesstoken", "bearertoken",
            "sessionid", "csrftoken", "xsrftoken");
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?:(?:^|[-_])(?:auth|authn|authz|token|key|secret|bearer|session|sid|api[-_]?key|jwt|csrf|xsrf)(?:[-_]|$))"
                    + "|(?:apikey|authtoken|accesstoken|bearertoken|sessionid|csrftoken|xsrftoken|xapikey|xauthtoken"
                    + "|xaccesstoken|xapitoken)", Pattern.CASE_INSENSITIVE);

    private UrlSecurity() {
    }

    /** {@code [scheme, host, port-or-null]} of an absolute URL, host lower-cased without brackets; null if none. */
    static String[] origin(String url) {
        Matcher m = url == null ? null : ORIGIN.matcher(url);
        if (m == null || !m.find()) {
            return null;
        }
        String hostPort = m.group(2).substring(m.group(2).lastIndexOf('@') + 1);
        String host;
        String port = null;
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            host = end < 0 ? hostPort.substring(1) : hostPort.substring(1, end);
            if (end >= 0 && hostPort.length() > end + 1 && hostPort.charAt(end + 1) == ':') {
                port = hostPort.substring(end + 2);
            }
        } else {
            int colon = hostPort.indexOf(':');
            host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            port = colon < 0 ? null : hostPort.substring(colon + 1);
        }
        return new String[] {m.group(1).toLowerCase(Locale.ROOT), host.toLowerCase(Locale.ROOT), port};
    }

    static boolean isSecure(String url) {
        String[] o = origin(url);
        if (o == null || o[1].isEmpty() || !(o[0].equals("http") || o[0].equals("https"))) {
            return false;
        }
        return o[0].equals("https") || isLoopbackHost(o[1]);
    }

    static boolean isLoopbackUrl(String url) {
        String[] o = origin(url);
        return o != null && !o[1].isEmpty() && isLoopbackHost(o[1]);
    }

    /** The template's literal host is loopback (a {@code {placeholder}} host is not). */
    static boolean templateHostIsLoopback(String template) {
        String[] o = origin(template);
        return o != null && !o[1].isEmpty() && !o[1].contains("{") && isLoopbackHost(o[1]);
    }

    /** localhost, 127/8, ::1, 0.0.0.0, ::, IPv4-mapped forms and inet_aton spellings; never DNS. */
    static boolean isLoopbackHost(String rawHost) {
        String h = rawHost.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0") || h.equals("::")) {
            return true;
        }
        if (h.indexOf(':') >= 0) {
            if (!h.matches("[0-9a-f:.]+")) {
                return false;
            }
            try {
                InetAddress a = InetAddress.getByName(h); // an IPv6 literal: parsed, not resolved
                return a.isLoopbackAddress() || a.isAnyLocalAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        if (!IPV4_LITERAL.matcher(h).matches()) {
            return false;
        }
        Long v = inetAton(h);
        return v != null && ((v >>> 24) == 127 || v == 0);
    }

    /** The IPv4 address the C resolver's {@code inet_aton} reads from {@code s}, or null. */
    static Long inetAton(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length > 4) {
            return null;
        }
        long result = 0;
        for (int i = 0; i < parts.length; i++) {
            Long v = atonPart(parts[i]);
            boolean last = i == parts.length - 1;
            long max = last ? (0xFFFFFFFFL >>> (8 * i)) : 0xFF;
            if (v == null || v > max) {
                return null;
            }
            result |= last ? v : v << (24 - 8 * i);
        }
        return result;
    }

    private static Long atonPart(String p) {
        try {
            if (p.startsWith("0x") || p.startsWith("0X")) {
                return p.length() > 2 && p.length() <= 10 ? Long.parseLong(p.substring(2), 16) : null;
            }
            if (p.length() > 1 && p.startsWith("0")) {
                return p.length() <= 12 ? Long.parseLong(p.substring(1), 8) : null;
            }
            return !p.isEmpty() && p.length() <= 10 ? Long.parseLong(p) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void ensureSecure(String url, String context, ErrorCategory category) {
        if (!isSecure(url)) {
            throw new AgentWeaveException(category, ProtocolId.UTCP, "Security error during " + context
                    + ": URL must use HTTPS or be a literal loopback address (localhost / 127.0.0.1 / ::1). Got: '"
                    + url + "'. Plain HTTP to any other host is rejected to prevent MITM attacks and SSRF into"
                    + " internal services.");
        }
    }

    /** A request may target loopback only when the call template names a loopback host literally. */
    static void ensureLoopbackWasDeclared(String template, String resolved) {
        if (isLoopbackUrl(resolved) && !templateHostIsLoopback(template)) {
            throw new AgentWeaveException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.UTCP,
                    "Security error during tool invocation: the resolved URL '" + resolved + "' targets the agent's"
                            + " own loopback interface, but the call template's URL ('" + template + "') does not"
                            + " name a loopback host literally.");
        }
    }

    /** A manual fetched from a non-loopback origin may not declare loopback tool or OAuth2 token URLs. */
    static void rejectRemoteLoopbackToolUrls(String discoveryUrl, List<Manual.Tool> tools) {
        if (isLoopbackUrl(discoveryUrl)) {
            return;
        }
        for (Manual.Tool tool : tools) {
            Object url = tool.callTemplate().get("url");
            if (url instanceof String u && (isLoopbackUrl(u) || templateHostIsLoopback(u))) {
                throw remoteLoopback(discoveryUrl, "tool URL", u, tool.name());
            }
            Map<String, Object> auth = Json.object(tool.callTemplate().get("auth"));
            if (auth != null && auth.get("token_url") instanceof String t && (isLoopbackUrl(t) || templateHostIsLoopback(t))) {
                throw remoteLoopback(discoveryUrl, "OAuth2 token URL", t, tool.name());
            }
        }
    }

    private static AgentWeaveException remoteLoopback(String discoveryUrl, String what, String url, String tool) {
        return new AgentWeaveException(ErrorCategory.PROTOCOL, ProtocolId.UTCP,
                "Security error during manual discovery: a manual fetched from '" + discoveryUrl
                        + "' declares a loopback " + what + " ('" + url + "') for tool '" + tool
                        + "'. A remote manual is not allowed to redirect tool calls at the agent's own loopback interface.");
    }

    static boolean sameOrigin(String a, String b) {
        String[] x = origin(a);
        String[] y = origin(b);
        if (x == null || y == null || !x[0].equals(y[0]) || !x[1].equals(y[1])) {
            return false;
        }
        int px = effectivePort(x[0], x[2]);
        return px >= -1 && px == effectivePort(y[0], y[2]);
    }

    private static int effectivePort(String scheme, String port) {
        if (port == null || port.isEmpty()) {
            return scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : -1;
        }
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    static boolean isAuthSensitiveHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return AUTH_SENSITIVE_HEADERS.contains(lower) || AUTH_HEADER.matcher(lower).find();
    }
}
