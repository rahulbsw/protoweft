package io.agentweave.spring.boot;

import io.agentweave.core.server.AuthDecision;
import io.agentweave.core.server.CallerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSecurityInboundAuthenticatorTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopeAuthoritiesBecomeTheScopesAttribute() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "ana", null, AuthorityUtils.createAuthorityList("SCOPE_tools:read", "ROLE_USER", "SCOPE_mail")));

        CallerIdentity caller = ((AuthDecision.Allow) new SpringSecurityInboundAuthenticator().authenticate(null)).caller();

        assertThat(caller.name()).isEqualTo("ana");
        assertThat(caller.attributes().get(SpringSecurityInboundAuthenticator.AUTHORITIES))
                .isEqualTo(List.of("ROLE_USER", "SCOPE_mail", "SCOPE_tools:read"));
        assertThat(caller.attributes().get(SpringSecurityInboundAuthenticator.SCOPES))
                .isEqualTo(List.of("mail", "tools:read"));
    }
}
