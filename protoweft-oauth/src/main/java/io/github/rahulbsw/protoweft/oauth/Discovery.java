package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Protected-resource and authorization-server metadata discovery (RFC 9728, RFC 8414, OIDC Discovery). */
final class Discovery {

    private static final System.Logger LOG = System.getLogger(OAuthClient.class.getName());

    /** A discovered PRM document and the URL it came from. */
    record Prm(URI url, ProtectedResourceMetadata metadata) {
    }

    private final Http http;

    Discovery(Http http) {
        this.http = http;
    }

    /** The PRM URIs to try: the challenge's {@code resource_metadata}, then path-inserted, then root. */
    static List<URI> prmCandidates(URI resource, URI fromHeader) {
        List<URI> out = new ArrayList<>();
        if (fromHeader != null) {
            out.add(fromHeader);
        }
        URI pathInserted = Uris.prmWellKnown(resource);
        URI root = Uris.prmRoot(resource);
        if (!out.contains(pathInserted)) {
            out.add(pathInserted);
        }
        if (!out.contains(root)) {
            out.add(root);
        }
        return out;
    }

    Prm protectedResource(URI resource, URI fromHeader) {
        if (fromHeader != null && !fromHeader.getScheme().equalsIgnoreCase("https") && !Uris.isLoopback(fromHeader)
                && !Uris.origin(fromHeader).equals(Uris.origin(resource))) {
            throw Uris.auth("resource_metadata URL must use https: " + fromHeader);
        }
        List<String> tried = new ArrayList<>();
        for (URI candidate : prmCandidates(resource, fromHeader)) {
            Optional<Map<String, Object>> json = http.getJson(candidate);
            if (json.isEmpty()) {
                tried.add(candidate.toString());
                continue;
            }
            ProtectedResourceMetadata prm;
            try {
                prm = ProtectedResourceMetadata.fromJson(json.get());
            } catch (ProtoweftException e) {
                throw Uris.auth("invalid protected resource metadata at " + candidate + ": " + e.getMessage(), e);
            }
            // RFC 9728 §3.3 / §7.3: the document must be about the resource being accessed.
            if (!Uris.covers(prm.resource(), resource)) {
                throw Uris.auth("protected resource metadata at " + candidate + " is for " + prm.resource()
                        + ", not " + resource);
            }
            if (prm.authorizationServers().isEmpty()) {
                throw Uris.auth("protected resource metadata at " + candidate + " lists no authorization_servers");
            }
            return new Prm(candidate, prm);
        }
        throw Uris.auth("no protected resource metadata found for " + resource + " (tried " + tried + ")");
    }

    /** Discovers the first usable authorization server of a PRM document (RFC 9728 §7.6: in listed order). */
    AuthorizationServerMetadata authorizationServer(ProtectedResourceMetadata prm) {
        List<String> failures = new ArrayList<>();
        for (URI issuer : prm.authorizationServers()) {
            try {
                return authorizationServer(issuer);
            } catch (ProtoweftException e) {
                failures.add(e.getMessage());
                LOG.log(System.Logger.Level.DEBUG, "authorization server " + issuer + " unusable: " + e.getMessage());
            }
        }
        throw Uris.auth(failures.size() == 1 ? failures.get(0) : "no usable authorization server: " + failures);
    }

    /** RFC 8414 and OIDC discovery in the MCP order; the returned issuer must match (RFC 8414 §3.3). */
    AuthorizationServerMetadata authorizationServer(URI issuer) {
        Uris.requireSecure(issuer, "authorization server issuer");
        for (URI candidate : Uris.authorizationServerMetadataUris(issuer)) {
            Optional<String> body = http.getText(candidate);
            if (body.isEmpty()) {
                continue;
            }
            AuthorizationServerMetadata metadata;
            try {
                metadata = AuthorizationServerMetadata.parse(body.get());
            } catch (ParseException e) {
                throw Uris.auth("invalid authorization server metadata at " + candidate + ": " + e.getMessage(), e);
            }
            if (!stripSlash(metadata.getIssuer().getValue()).equals(stripSlash(issuer.toString()))) {
                throw Uris.auth("authorization server metadata at " + candidate + " has issuer "
                        + metadata.getIssuer() + ", expected " + issuer);
            }
            Uris.requireSecure(metadata.getTokenEndpointURI(), "token_endpoint");
            return metadata;
        }
        throw Uris.auth("no authorization server metadata found for " + issuer);
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
