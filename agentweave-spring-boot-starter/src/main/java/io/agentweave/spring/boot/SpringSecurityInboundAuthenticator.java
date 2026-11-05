package io.agentweave.spring.boot;

import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import io.agentweave.core.server.InboundAuthenticator;
import io.agentweave.core.server.InboundRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * The default {@link InboundAuthenticator} when Spring Security is present: it never rejects
 * (Spring Security's own rules protect the routes) and names the caller after the
 * {@link Authentication} Spring Security established, with its authorities as the
 * {@code authorities} attribute. Unauthenticated requests are {@link CallerIdentity#ANONYMOUS}.
 */
public final class SpringSecurityInboundAuthenticator implements InboundAuthenticator {

    /** Caller attribute holding the sorted authority names. */
    public static final String AUTHORITIES = "authorities";

    @Override
    public AuthDecision authenticate(InboundRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return AuthDecision.allow(CallerIdentity.ANONYMOUS);
        }
        return AuthDecision.allow(new CallerIdentity(auth.getName(), Map.of(AUTHORITIES,
                auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList())));
    }
}
