package io.github.rahulbsw.protoweft.oauth;

import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.server.InboundRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An OAuth 2.1 resource server in front of any Protoweft endpoint (MCP authorization 2025-11-25):
 * requires {@code Authorization: Bearer}, validates the token with a {@link TokenVerifier}, insists
 * that its audience is this resource, and enforces per-request scopes.
 *
 * <ul>
 *   <li>missing token: 401 {@code Bearer resource_metadata="…", scope="…"};</li>
 *   <li>invalid, expired or wrong-audience token: 401 {@code Bearer error="invalid_token", resource_metadata="…", scope="…"};</li>
 *   <li>insufficient scope: 403 {@code Bearer error="insufficient_scope", scope="…", resource_metadata="…"};</li>
 *   <li>valid: allowed as {@code CallerIdentity(sub or client_id, {"scopes": [...], "claims": {...}})}.</li>
 * </ul>
 * The {@code scope} of a 401 is the scopes required for that request, else the metadata's
 * {@code scopes_supported}, else omitted. The 403 {@code scope} lists the required scopes plus those
 * already granted, so a step-up does not lose permissions. {@code GET}/{@code HEAD}/{@code OPTIONS}
 * under {@code /.well-known/oauth-protected-resource} are always allowed anonymously so the
 * metadata endpoint stays reachable behind the same filter. The token itself is never logged,
 * returned, or exposed to handlers (MCP: MUST NOT pass tokens through).
 */
public final class BearerTokenAuthenticator implements InboundAuthenticator {

    /** Caller attribute: the granted scopes, a {@code List<String>}. */
    public static final String SCOPES = "scopes";
    /** Caller attribute: the verified claims, a {@code Map<String, Object>}. */
    public static final String CLAIMS = "claims";

    private record Rule(String method, String pathPrefix, List<String> scopes) {
        boolean matches(InboundRequest r) {
            return (method == null || method.equalsIgnoreCase(r.method()))
                    && (r.path().equals(pathPrefix) || r.path().startsWith(pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/"));
        }
    }

    private final ProtectedResourceMetadata metadata;
    private final TokenVerifier verifier;
    private final List<String> globalScopes;
    private final List<Rule> rules;
    private final String metadataUrl;

    private BearerTokenAuthenticator(Builder b) {
        this.metadata = b.metadata;
        this.verifier = b.verifier;
        this.globalScopes = List.copyOf(b.globalScopes);
        this.rules = List.copyOf(b.rules);
        this.metadataUrl = b.metadata.metadataUri().toString();
    }

    public static Builder builder(ProtectedResourceMetadata metadata, TokenVerifier verifier) {
        return new Builder(metadata, verifier);
    }

    @Override
    public AuthDecision authenticate(InboundRequest request) {
        if (request.path().startsWith(Uris.PRM_WELL_KNOWN) && Set.of("GET", "HEAD", "OPTIONS")
                .contains(request.method().toUpperCase(Locale.ROOT))) {
            return AuthDecision.allow(CallerIdentity.ANONYMOUS);
        }
        Set<String> required = requiredScopes(request);
        String token = request.bearerToken().orElse(null);
        if (token == null || token.isEmpty()) {
            return AuthDecision.unauthenticated("authorization required", challenge(null, challengeScope(required)));
        }
        VerifiedToken verified;
        try {
            verified = verifier.verify(token);
        } catch (InvalidTokenException e) {
            return AuthDecision.unauthenticated(e.getMessage() == null ? "invalid token" : e.getMessage(),
                    challenge("invalid_token", challengeScope(required)));
        } catch (RuntimeException e) {
            return AuthDecision.unauthenticated("token could not be verified",
                    challenge("invalid_token", challengeScope(required)));
        }
        if (verified.audiences().stream().noneMatch(a -> JwtTokenVerifier.sameResource(a, metadata.resource()))) {
            return AuthDecision.unauthenticated("token audience does not include " + metadata.resource(),
                    challenge("invalid_token", challengeScope(required)));
        }
        if (!verified.scopes().containsAll(required)) {
            Set<String> wanted = new LinkedHashSet<>(required);
            wanted.addAll(new TreeSet<>(verified.scopes()));
            String scope = String.join(" ", wanted);
            return AuthDecision.forbidden("insufficient scope",
                    "Bearer error=\"insufficient_scope\", scope=\"" + scope + "\", resource_metadata=\"" + metadataUrl + "\"");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(SCOPES, List.copyOf(new TreeSet<>(verified.scopes())));
        attributes.put(CLAIMS, verified.claims());
        return AuthDecision.allow(new CallerIdentity(verified.principal(), attributes));
    }

    private Set<String> requiredScopes(InboundRequest request) {
        Set<String> out = new LinkedHashSet<>(globalScopes);
        for (Rule rule : rules) {
            if (rule.matches(request)) {
                out.addAll(rule.scopes());
            }
        }
        return out;
    }

    private String challengeScope(Set<String> required) {
        if (!required.isEmpty()) {
            return String.join(" ", required);
        }
        return metadata.scopesSupported().isEmpty() ? null : String.join(" ", metadata.scopesSupported());
    }

    private String challenge(String error, String scope) {
        List<String> params = new ArrayList<>();
        if (error != null) {
            params.add("error=\"" + error + "\"");
        }
        params.add("resource_metadata=\"" + metadataUrl + "\"");
        if (scope != null) {
            params.add("scope=\"" + scope + "\"");
        }
        return "Bearer " + String.join(", ", params);
    }

    public static final class Builder {
        private final ProtectedResourceMetadata metadata;
        private final TokenVerifier verifier;
        private final List<String> globalScopes = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();

        private Builder(ProtectedResourceMetadata metadata, TokenVerifier verifier) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.verifier = Objects.requireNonNull(verifier, "verifier");
        }

        /** Scopes every request must carry. */
        public Builder requiredScopes(String... scopes) {
            globalScopes.addAll(validScopes(scopes));
            return this;
        }

        /**
         * Extra scopes for requests whose path is {@code pathPrefix} or below it and, unless
         * {@code method} is {@code null}, whose HTTP method matches. All matching rules apply.
         */
        public Builder requireScopes(String method, String pathPrefix, String... scopes) {
            Objects.requireNonNull(pathPrefix, "pathPrefix");
            rules.add(new Rule(method, pathPrefix, validScopes(scopes)));
            return this;
        }

        private static List<String> validScopes(String... scopes) {
            for (String s : scopes) {
                if (s == null || s.isEmpty() || s.chars().anyMatch(c -> c <= 0x20 || c == '"' || c == '\\' || c > 0x7e)) {
                    throw Uris.config("invalid scope token: " + s);
                }
            }
            return List.of(scopes);
        }

        public BearerTokenAuthenticator build() {
            return new BearerTokenAuthenticator(this);
        }
    }
}
