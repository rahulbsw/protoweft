package io.agentweave.oauth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.agentweave.core.AgentWeaveException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates JWT access tokens (RFC 9068 style) against a JWKS: signature (RS256 or ES256 by
 * default), {@code exp} (required) and {@code nbf} with clock skew, {@code iss} among the allowed
 * issuers, and {@code aud} containing the canonical resource URI (MCP: MUST). Scopes come from
 * {@code scope} (space-delimited) or {@code scp}.
 */
public final class JwtTokenVerifier implements TokenVerifier {

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final Set<String> issuers;
    private final URI audience;

    private JwtTokenVerifier(DefaultJWTProcessor<SecurityContext> processor, Set<String> issuers, URI audience) {
        this.processor = processor;
        this.issuers = issuers;
        this.audience = audience;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public VerifiedToken verify(String token) throws InvalidTokenException {
        JWTClaimsSet claims;
        try {
            claims = processor.process(SignedJWT.parse(token), null);
        } catch (ParseException e) {
            throw new InvalidTokenException("malformed token");
        } catch (BadJOSEException e) {
            throw new InvalidTokenException("invalid token: " + e.getMessage());
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new InvalidTokenException("token could not be verified: " + e.getMessage());
        }
        if (claims.getIssuer() == null || !issuers.contains(claims.getIssuer())) {
            throw new InvalidTokenException("token issuer is not trusted");
        }
        List<String> aud = claims.getAudience();
        boolean forUs = aud != null && aud.stream().anyMatch(a -> sameResource(a, audience));
        if (!forUs) {
            throw new InvalidTokenException("token audience does not include " + audience);
        }
        String clientId = stringClaim(claims, "client_id");
        if (clientId == null) {
            clientId = stringClaim(claims, "azp");
        }
        return new VerifiedToken(claims.getSubject(), clientId, new HashSet<>(aud), scopes(claims), claims.getClaims());
    }

    static boolean sameResource(String value, URI canonicalResource) {
        URI uri = Uris.parse(value);
        if (uri == null) {
            return false;
        }
        try {
            return Uris.canonical(uri).equals(canonicalResource);
        } catch (AgentWeaveException e) {
            return false;
        }
    }

    private static Set<String> scopes(JWTClaimsSet claims) {
        Set<String> out = new LinkedHashSet<>();
        Object scope = claims.getClaim("scope");
        Object scp = claims.getClaim("scp");
        for (Object o : new Object[] {scope, scp}) {
            if (o instanceof String s) {
                out.addAll(StoredToken.split(s));
            } else if (o instanceof Collection<?> c) {
                c.forEach(v -> out.add(String.valueOf(v)));
            }
        }
        return out;
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        return claims.getClaim(name) instanceof String s ? s : null;
    }

    public static final class Builder {
        private URI jwksUri;
        private String jwksJson;
        private final Set<String> issuers = new LinkedHashSet<>();
        private URI audience;
        private Duration clockSkew = Duration.ofSeconds(60);
        private Set<JWSAlgorithm> algorithms = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);
        private HttpClient httpClient;

        private Builder() {
        }

        /** Fetches (and caches, refreshing on unknown key ids) the JWKS from this https URL. */
        public Builder jwksUri(URI jwksUri) {
            this.jwksUri = Uris.requireSecure(Objects.requireNonNull(jwksUri, "jwksUri"), "jwks_uri");
            return this;
        }

        /** A fixed JWK set, as JSON ({@code {"keys":[...]}}). */
        public Builder jwks(String jwkSetJson) {
            this.jwksJson = Objects.requireNonNull(jwkSetJson, "jwkSetJson");
            return this;
        }

        /** An accepted {@code iss} value (exact match); at least one is required. */
        public Builder issuer(String... issuers) {
            this.issuers.addAll(Arrays.asList(issuers));
            return this;
        }

        /** The resource this server protects; tokens must name it in {@code aud}. */
        public Builder audience(URI resource) {
            this.audience = Uris.canonical(Objects.requireNonNull(resource, "resource"));
            return this;
        }

        /** Tolerance for {@code exp} and {@code nbf}; default 60 seconds. */
        public Builder clockSkew(Duration skew) {
            this.clockSkew = Objects.requireNonNull(skew, "skew");
            return this;
        }

        /** Accepted JWS algorithms, for example {@code "RS256"}; default RS256 and ES256. */
        public Builder algorithms(String... names) {
            Set<JWSAlgorithm> algs = new HashSet<>();
            for (String n : names) {
                algs.add(JWSAlgorithm.parse(n));
            }
            if (algs.isEmpty()) {
                throw Uris.config("at least one algorithm is required");
            }
            this.algorithms = Set.copyOf(algs);
            return this;
        }

        /** The client used to fetch {@link #jwksUri}; it must not follow redirects. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        public JwtTokenVerifier build() {
            if (issuers.isEmpty()) {
                throw Uris.config("JwtTokenVerifier needs at least one issuer");
            }
            if (audience == null) {
                throw Uris.config("JwtTokenVerifier needs the resource audience");
            }
            if ((jwksUri == null) == (jwksJson == null)) {
                throw Uris.config("JwtTokenVerifier needs exactly one of jwksUri or jwks");
            }
            JWKSource<SecurityContext> source;
            if (jwksJson != null) {
                try {
                    source = new ImmutableJWKSet<>(JWKSet.parse(jwksJson));
                } catch (ParseException e) {
                    throw Uris.config("invalid JWK set: " + e.getMessage());
                }
            } else {
                HttpClient client = httpClient != null ? httpClient : Http.defaultClient();
                try {
                    source = JWKSourceBuilder.<SecurityContext>create(jwksUri.toURL(), url -> fetch(client, url))
                            .build();
                } catch (MalformedURLException e) {
                    throw Uris.config("invalid jwks_uri: " + jwksUri);
                }
            }
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                    JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, source));
            DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                    null, null, Set.of("exp", "iss"), null);
            claims.setMaxClockSkew((int) clockSkew.toSeconds());
            processor.setJWTClaimsSetVerifier(claims);
            return new JwtTokenVerifier(processor, Set.copyOf(issuers), audience);
        }

        private static Resource fetch(HttpClient client, java.net.URL url) throws IOException {
            try {
                HttpResponse<String> reply = client.send(HttpRequest.newBuilder(url.toURI())
                        .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (reply.statusCode() != 200) {
                    throw new IOException("JWKS fetch returned HTTP " + reply.statusCode());
                }
                return new Resource(reply.body(), reply.headers().firstValue("Content-Type").orElse(null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted fetching JWKS", e);
            } catch (java.net.URISyntaxException e) {
                throw new IOException(e);
            }
        }
    }
}
