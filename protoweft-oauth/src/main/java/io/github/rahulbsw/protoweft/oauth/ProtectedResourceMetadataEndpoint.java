package io.github.rahulbsw.protoweft.oauth;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serves a {@link ProtectedResourceMetadata} document with {@code GET} at its path-inserted
 * well-known URI ({@code /.well-known/oauth-protected-resource/<resource path>}, RFC 9728 §3.1)
 * and, optionally, at the root well-known URI. Mount it on the same host as the protected
 * endpoint; {@link BearerTokenAuthenticator} lets these requests through anonymously.
 */
public final class ProtectedResourceMetadataEndpoint implements ServerEndpointProvider {

    /** The protocol id the metadata endpoint is registered under. */
    public static final ProtocolId OAUTH = new ProtocolId("oauth");

    private final ProtectedResourceMetadata metadata;
    private final boolean root;
    private final String name;

    /** Serves at the path-inserted well-known URI only. */
    public ProtectedResourceMetadataEndpoint(ProtectedResourceMetadata metadata) {
        this(metadata, false);
    }

    /**
     * @param alsoAtRoot also serve at {@code /.well-known/oauth-protected-resource}, for clients
     *                   that only try the root (use it when the host protects a single resource)
     */
    public ProtectedResourceMetadataEndpoint(ProtectedResourceMetadata metadata, boolean alsoAtRoot) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.root = alsoAtRoot;
        String path = Uris.path(metadata.resource());
        this.name = "oauth-protected-resource" + (path.isEmpty() ? "" : path.replace('/', '-'));
    }

    @Override
    public List<ServletEndpoint> endpoints() {
        List<String> patterns = new ArrayList<>();
        patterns.add(metadata.metadataPath());
        String rootPath = Uris.PRM_WELL_KNOWN;
        if (root && !patterns.contains(rootPath)) {
            patterns.add(rootPath);
        }
        return List.of(new ServletEndpoint(name, OAUTH, patterns, new MetadataServlet(metadata.toJson()), false));
    }

    private static final class MetadataServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        private final String json;

        MetadataServlet(String json) {
            this.json = json;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            resp.setStatus(200);
            resp.setContentType("application/json");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            resp.setHeader("Cache-Control", "max-age=3600");
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setContentLength(body.length);
            resp.getOutputStream().write(body);
        }

        @Override
        protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
            resp.setStatus(204);
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "*");
            resp.setHeader("Allow", "GET, HEAD, OPTIONS");
        }
    }
}
