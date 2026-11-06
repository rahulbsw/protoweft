package io.github.rahulbsw.protoweft.oauth;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;

import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) for one protected resource, such as an MCP
 * endpoint. Servers publish it with {@link ProtectedResourceMetadataEndpoint}; clients discover it
 * through {@link OAuthClient}.
 *
 * <p>{@code resource} is kept in canonical form (lowercase scheme and host, no fragment, no
 * trailing slash), which is also the audience {@link BearerTokenAuthenticator} requires.
 */
public final class ProtectedResourceMetadata {

    private final URI resource;
    private final List<URI> authorizationServers;
    private final List<String> scopesSupported;
    private final List<String> bearerMethodsSupported;
    private final URI resourceDocumentation;
    private final String resourceName;

    private ProtectedResourceMetadata(URI resource, List<URI> authorizationServers, List<String> scopesSupported,
                                      List<String> bearerMethodsSupported, URI resourceDocumentation,
                                      String resourceName) {
        this.resource = resource;
        this.authorizationServers = List.copyOf(authorizationServers);
        this.scopesSupported = List.copyOf(scopesSupported);
        this.bearerMethodsSupported = List.copyOf(bearerMethodsSupported);
        this.resourceDocumentation = resourceDocumentation;
        this.resourceName = resourceName;
    }

    /** @param resource the protected resource's URI, for example {@code https://api.example.com/mcp} */
    public static Builder builder(URI resource) {
        return new Builder(resource);
    }

    /** The canonical resource identifier. */
    public URI resource() {
        return resource;
    }

    public List<URI> authorizationServers() {
        return authorizationServers;
    }

    /** Scopes the resource accepts; empty when not advertised. */
    public List<String> scopesSupported() {
        return scopesSupported;
    }

    public List<String> bearerMethodsSupported() {
        return bearerMethodsSupported;
    }

    public Optional<URI> resourceDocumentation() {
        return Optional.ofNullable(resourceDocumentation);
    }

    public Optional<String> resourceName() {
        return Optional.ofNullable(resourceName);
    }

    /** The path-inserted well-known URI of this document (RFC 9728 §3.1). */
    public URI metadataUri() {
        return Uris.prmWellKnown(resource);
    }

    /** The path part of {@link #metadataUri()}, for example {@code /.well-known/oauth-protected-resource/mcp}. */
    public String metadataPath() {
        return metadataUri().getRawPath();
    }

    /** The document as a JSON object. */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("resource", resource.toString());
        json.put("authorization_servers", authorizationServers.stream().map(URI::toString).toList());
        if (!scopesSupported.isEmpty()) {
            json.put("scopes_supported", scopesSupported);
        }
        json.put("bearer_methods_supported", bearerMethodsSupported);
        if (resourceDocumentation != null) {
            json.put("resource_documentation", resourceDocumentation.toString());
        }
        if (resourceName != null) {
            json.put("resource_name", resourceName);
        }
        return JSONObjectUtils.toJSONString(json);
    }

    /** Parses a fetched document; requires {@code resource} (RFC 9728 §2). */
    static ProtectedResourceMetadata fromJson(Map<String, Object> json) {
        try {
            String resource = JSONObjectUtils.getString(json, "resource");
            if (resource == null) {
                throw new ProtoweftException(ErrorCategory.PROTOCOL, null, "protected resource metadata lacks 'resource'");
            }
            Builder b = builder(URI.create(resource));
            String[] servers = JSONObjectUtils.getStringArray(json, "authorization_servers");
            if (servers != null) {
                for (String s : servers) {
                    b.authorizationServer(URI.create(s));
                }
            }
            String[] scopes = JSONObjectUtils.getStringArray(json, "scopes_supported");
            if (scopes != null) {
                b.scopesSupported(List.of(scopes));
            }
            String[] methods = JSONObjectUtils.getStringArray(json, "bearer_methods_supported");
            if (methods != null) {
                b.bearerMethods = List.of(methods);
            }
            return b.buildUnchecked();
        } catch (ParseException | IllegalArgumentException e) {
            throw new ProtoweftException(ErrorCategory.PROTOCOL, null,
                    "invalid protected resource metadata: " + e.getMessage(), e);
        }
    }

    /** Parses a JSON document; used by clients and tests. */
    static ProtectedResourceMetadata parse(String json) {
        try {
            return fromJson(JSONObjectUtils.parse(json));
        } catch (ParseException e) {
            throw new ProtoweftException(ErrorCategory.PROTOCOL, null, "invalid protected resource metadata JSON", e);
        }
    }

    @Override
    public String toString() {
        return "ProtectedResourceMetadata" + toJson();
    }

    public static final class Builder {
        private final URI resource;
        private final List<URI> authorizationServers = new ArrayList<>();
        private List<String> scopes = List.of();
        private List<String> bearerMethods = List.of("header");
        private URI documentation;
        private String name;

        private Builder(URI resource) {
            this.resource = Uris.canonical(Objects.requireNonNull(resource, "resource"));
            if (resource.getRawFragment() != null) {
                throw Uris.config("resource must not contain a fragment");
            }
        }

        /** Adds an authorization server issuer identifier; at least one is required. */
        public Builder authorizationServer(URI issuer) {
            authorizationServers.add(Objects.requireNonNull(issuer, "issuer"));
            return this;
        }

        public Builder scopesSupported(List<String> scopes) {
            this.scopes = List.copyOf(scopes);
            return this;
        }

        public Builder scopesSupported(String... scopes) {
            return scopesSupported(List.of(scopes));
        }

        public Builder resourceDocumentation(URI documentation) {
            this.documentation = documentation;
            return this;
        }

        public Builder resourceName(String name) {
            this.name = name;
            return this;
        }

        /** @throws ProtoweftException ({@code CONFIGURATION}) without an authorization server (MCP: MUST) */
        public ProtectedResourceMetadata build() {
            if (authorizationServers.isEmpty()) {
                throw Uris.config("protected resource metadata needs at least one authorization server");
            }
            return buildUnchecked();
        }

        private ProtectedResourceMetadata buildUnchecked() {
            return new ProtectedResourceMetadata(resource, authorizationServers, scopes, bearerMethods,
                    documentation, name);
        }
    }
}
