package io.github.rahulbsw.protoweft.oauth;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** URI rules shared by the client and the resource server: canonical form, well-known URIs, transport checks. */
final class Uris {

    static final String PRM_WELL_KNOWN = "/.well-known/oauth-protected-resource";
    private static final String OAUTH_AS_WELL_KNOWN = "/.well-known/oauth-authorization-server";
    private static final String OIDC_WELL_KNOWN = "/.well-known/openid-configuration";
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private Uris() {
    }

    /**
     * The canonical resource URI (RFC 8707 §2, MCP "Canonical Server URI"): absolute http(s) URI,
     * lowercase scheme and host, no fragment, no user info, no default port, no trailing slash.
     */
    static URI canonical(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
            throw config("resource URI must be absolute with a host: " + uri);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw config("resource URI must be http or https: " + uri);
        }
        if (uri.getRawUserInfo() != null) {
            throw config("resource URI must not contain user info");
        }
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) {
            port = -1;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder sb = new StringBuilder(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return URI.create(sb.toString());
    }

    /** {@code scheme://authority} of a URI, without path. */
    static String origin(URI uri) {
        URI c = canonical(uri);
        return c.getScheme() + "://" + c.getRawAuthority();
    }

    /** The path of a canonical URI, {@code ""} for the root. */
    static String path(URI uri) {
        String p = canonical(uri).getRawPath();
        return p == null ? "" : p;
    }

    /** RFC 9728 §3.1 path-inserted well-known URI of a resource ({@code /.well-known/oauth-protected-resource/<path>}). */
    static URI prmWellKnown(URI resource) {
        return URI.create(origin(resource) + PRM_WELL_KNOWN + path(resource));
    }

    /** The root well-known PRM URI of the resource's origin. */
    static URI prmRoot(URI resource) {
        return URI.create(origin(resource) + PRM_WELL_KNOWN);
    }

    /** AS metadata URIs to try, in the MCP 2025-11-25 priority order (RFC 8414 §3.1 then OIDC variants). */
    static List<URI> authorizationServerMetadataUris(URI issuer) {
        String origin = issuer.getScheme().toLowerCase(Locale.ROOT) + "://" + issuer.getRawAuthority();
        String path = issuer.getRawPath() == null ? "" : issuer.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        List<URI> out = new ArrayList<>();
        if (path.isEmpty()) {
            out.add(URI.create(origin + OAUTH_AS_WELL_KNOWN));
            out.add(URI.create(origin + OIDC_WELL_KNOWN));
        } else {
            out.add(URI.create(origin + OAUTH_AS_WELL_KNOWN + path));
            out.add(URI.create(origin + OIDC_WELL_KNOWN + path));
            out.add(URI.create(origin + path + OIDC_WELL_KNOWN));
        }
        return out;
    }

    /** Whether {@code prmResource} identifies {@code requested}: same origin and a path prefix on a segment boundary. */
    static boolean covers(URI prmResource, URI requested) {
        URI a = canonical(prmResource);
        URI b = canonical(requested);
        if (!origin(a).equals(origin(b))) {
            return false;
        }
        String pa = a.getRawPath() == null ? "" : a.getRawPath();
        String pb = b.getRawPath() == null ? "" : b.getRawPath();
        return pa.isEmpty() || pb.equals(pa) || pb.startsWith(pa + "/");
    }

    /** Whether the host is loopback: 127.0.0.0/8, ::1, or the exact name {@code localhost}. */
    static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return IPV4_LOOPBACK.matcher(host).matches() || host.equals("[::1]") || host.equals("::1")
                || host.equalsIgnoreCase("localhost");
    }

    /** Enforces HTTPS for authorization-server endpoints (OAuth 2.1 §1.5), with loopback allowed for local testing. */
    static URI requireSecure(URI uri, String what) {
        if (uri == null) {
            throw auth(what + " is missing");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") || (scheme.equals("http") && isLoopback(uri))) {
            if (uri.getRawFragment() != null) {
                throw auth(what + " must not contain a fragment");
            }
            return uri;
        }
        throw auth(what + " must use https: " + uri);
    }

    static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    static ProtoweftException auth(String message) {
        return new ProtoweftException(ErrorCategory.AUTHENTICATION, null, message);
    }

    static ProtoweftException auth(String message, Throwable cause) {
        return new ProtoweftException(ErrorCategory.AUTHENTICATION, null, message, cause);
    }

    static ProtoweftException config(String message) {
        return new ProtoweftException(ErrorCategory.CONFIGURATION, null, message);
    }
}
