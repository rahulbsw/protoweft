package io.agentweave.spring.boot;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.mcp.McpClientConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring Security protects the MCP route; its authenticated user reaches the tools as the caller. */
class SpringSecurityIntegrationTest {

    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        context = new SpringApplicationBuilder(App.class).web(WebApplicationType.SERVLET)
                .properties("server.port=" + port, "agentweave.server.mcp.enabled=true").run();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void springSecurityRejectsAnonymousCallers() {
        try (McpClientConnector mcp = McpClientConnector.http(base).build()) {
            assertThatThrownBy(mcp::start).isInstanceOfSatisfying(AgentWeaveException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
    }

    @Test
    void theSpringSecurityUserIsTheToolCaller() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("alice:pw".getBytes(StandardCharsets.UTF_8));
        try (McpClientConnector mcp = McpClientConnector.http(base)
                .credentials((protocol, target) -> List.of(new Credential.NamedSecret("Authorization", basic)))
                .build()) {
            mcp.start();
            assertThat(mcp.invoke(ToolInvocation.of("whoami", Map.of())).text()).isEqualTo("alice");
        }
        assertThat(context.getBean(io.agentweave.core.server.InboundAuthenticator.class))
                .isInstanceOf(SpringSecurityInboundAuthenticator.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            AgentWeaveAutoConfiguration.class, AgentWeaveServerAutoConfiguration.class})
    static class App {

        @Bean
        TestTools testTools() {
            return new TestTools();
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.requestMatchers("/mcp").authenticated().anyRequest().permitAll())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp"))
                    .build();
        }

        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername("alice").password("{noop}pw").roles("AGENT").build());
        }
    }
}
