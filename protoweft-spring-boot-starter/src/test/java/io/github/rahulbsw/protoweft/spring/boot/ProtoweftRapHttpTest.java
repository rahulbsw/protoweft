package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.core.tool.ToolEvent;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolRegistry;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.core.tool.ToolSubscription;
import io.github.rahulbsw.protoweft.rap.RapCall;
import io.github.rahulbsw.protoweft.rap.RapCallbackListener;
import io.github.rahulbsw.protoweft.rap.RapClientConnector;
import io.github.rahulbsw.protoweft.rap.RapPromptHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real application on Tomcat serving its {@code @AgentTool}s over RAP behind a bearer
 * {@link InboundAuthenticator}, and consuming them through a RAP connector declared in properties,
 * whose callbacks arrive on the endpoint mounted on the same server. A second, non-web context
 * consumes the same server with the built-in callback listener.
 */
class ProtoweftRapHttpTest {

    private static final CredentialProvider BEARER = (protocol, target) -> List.of(new Credential.BearerToken("secret"));

    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        context = new SpringApplicationBuilder(App.class).web(WebApplicationType.SERVLET).properties(
                "server.port=" + port,
                "protoweft.server.rap.enabled=true",
                "protoweft.server.rap.name=weather",
                "protoweft.server.rap.allowed-callback-hosts=callbacks.internal",
                "protoweft.rap.servers.self.url=" + base + "/rap",
                "protoweft.rap.servers.self.timeout=20s").run();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void aConnectorFromPropertiesConsumesTheAppsOwnRapServer() {
        ToolRegistry registry = context.getBean(ToolRegistry.class);
        assertThat(registry.listTools()).extracting(ToolSpec::name)
                .contains("local.echo", "self.echo", "self.whoami", "self.watch", "self.unit");
        assertThat(registry.invoke(ToolInvocation.of("self.echo", Map.of("text", "hi"))).text()).isEqualTo("hi");
        // The connector's CredentialProvider bean authenticates it to the server's InboundAuthenticator.
        assertThat(registry.invoke(ToolInvocation.of("self.whoami", Map.of())).text()).isEqualTo("tester");
        assertThat(context.getBean(ProtoweftConnectors.class).connector("self", RapClientConnector.class)
                .compliance().negotiated()).contains("infinity@e1a3e4b");
    }

    @Test
    void subscriptionEventsArriveThroughTheRegistry() throws Exception {
        ToolOutcome out = context.getBean(ToolRegistry.class)
                .invoke(ToolInvocation.of("self.watch", Map.of("city", "Oslo")));
        assertThat(out.text()).isEqualTo("watching Oslo");
        ToolSubscription subscription = out.subscription().orElseThrow();
        EventCollector events = new EventCollector();
        subscription.events().subscribe(events);
        assertThat(events.awaitTexts()).containsExactly("Oslo 21.5", "Oslo 22.0", "Oslo done");
        ToolEvent second = events.events.get(1);
        assertThat(second.metadata()).containsEntry("associative", true);
        assertThat(events.events.get(2).last()).isTrue();
        // Over another protocol the tool gets the unavailable RapCallContext stand-in.
        assertThat(context.getBean(ToolRegistry.class).invoke(ToolInvocation.of("local.watch", Map.of("city", "Oslo")))
                .text()).isEqualTo("subscriptions need RAP");
    }

    @Test
    void thePromptHandlerBeanAnswersUserChoices() {
        assertThat(context.getBean(ToolRegistry.class).invoke(ToolInvocation.of("self.unit", Map.of())).text())
                .isEqualTo("fahrenheit");
        assertThat(context.getBean(App.class).prompts).containsExactly("Which unit?:unit");
    }

    @Test
    void healthAndInfoReportTheRapConnector() {
        Health health = context.getBean(ProtoweftHealthIndicator.class).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).extractingByKey("self").isEqualTo(Map.of("protocol", "rap", "state", "UP"));
        Info.Builder info = new Info.Builder();
        context.getBean(ProtoweftInfoContributor.class).contribute(info);
        assertThat(info.build().getDetails()).extractingByKey("protoweft").asString()
                .contains("self", "protocol=rap", "sdkName=none", "infinity@e1a3e4b", "subscription_event");
    }

    @Test
    void serverRoutesNeedTheAuthenticatorButCallbacksAreExempt() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> toolset = http.send(HttpRequest.newBuilder(URI.create(base + "/rap/.well-known/rap-toolset"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(toolset.statusCode()).isEqualTo(401);
        HttpResponse<String> withToken = http.send(HttpRequest.newBuilder(URI.create(base + "/rap/.well-known/rap-toolset"))
                .header("Authorization", "Bearer secret").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(withToken.statusCode()).isEqualTo(200);
        assertThat(withToken.body()).contains("\"name\":\"weather\"", "\"endpoint\":\"" + base + "/rap/invoke\"");
        // No credentials on callbacks: an unknown token is 404 from the endpoint, not 401 from the filter.
        HttpResponse<String> callback = http.send(HttpRequest.newBuilder(URI.create(base + "/rap/callback/unknown"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(callback.statusCode()).isEqualTo(404);
    }

    @Test
    void allowedCallbackHostsExtendTheCallbackPolicy() throws Exception {
        assertThat(invoke("http://10.1.2.3/cb").statusCode()).isEqualTo(400);
        assertThat(invoke("http://callbacks.internal/cb").statusCode()).isEqualTo(200);
    }

    @Test
    void aSecondApplicationConsumesTheServerWithTheBuiltInListener() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProtoweftAutoConfiguration.class,
                        ProtoweftServerAutoConfiguration.class))
                .withBean(CredentialProvider.class, () -> BEARER)
                .withPropertyValues("protoweft.local-tools.enabled=false",
                        "protoweft.rap.servers.weather.url=" + base + "/rap",
                        "protoweft.rap.callback.listener.enabled=true")
                .run(client -> {
                    assertThat(client).hasNotFailed().hasSingleBean(RapCallbackListener.class);
                    ToolRegistry registry = client.getBean(ToolRegistry.class);
                    assertThat(registry.invoke(ToolInvocation.of("weather.echo", Map.of("text", "via listener"))).text())
                            .isEqualTo("via listener");
                });
    }

    private static HttpResponse<String> invoke(String callbackUrl) throws Exception {
        String body = "{\"operation\":\"echo\",\"arguments\":{\"text\":\"x\"},\"id\":\"t1\",\"group_id\":\"g1\","
                + "\"callback_url\":\"" + callbackUrl + "\"}";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/rap/invoke"))
                .header("Authorization", "Bearer secret").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, ProtoweftAutoConfiguration.class,
            ProtoweftServerAutoConfiguration.class, ProtoweftActuatorAutoConfiguration.class})
    static class App {

        final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Bean
        RapTestTools rapTestTools() {
            return new RapTestTools();
        }

        @Bean
        InboundAuthenticator bearerAuthenticator() {
            return request -> request.bearerToken().filter("secret"::equals).isPresent()
                    ? AuthDecision.allow(CallerIdentity.of("tester"))
                    : AuthDecision.unauthenticated("bearer token required");
        }

        @Bean
        CredentialProvider credentials() {
            return BEARER;
        }

        @Bean
        RapPromptHandler prompts() {
            return new RapPromptHandler() {
                @Override
                public void onAuthorization(String authUrl, RapCall call) {
                }

                @Override
                public int onChoice(String prompt, List<String> choices, int defaultIndex, RapCall call) {
                    prompts.add(prompt + ":" + call.operation());
                    return 1;
                }
            };
        }
    }
}
