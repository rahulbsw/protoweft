package io.github.rahulbsw.protoweft.examples.spring;

import io.github.rahulbsw.protoweft.a2a.A2aAgentConnector;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentReply;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;
import io.github.rahulbsw.protoweft.utcp.UtcpClientConnector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The running example, called through each protocol with Protoweft's own clients. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootExampleTest {

    private static final CredentialProvider TOKEN = (protocol, target) -> List.of(new Credential.BearerToken("demo-token"));

    @LocalServerPort
    int port;

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void mcpClientListsAndCallsTheAgentTools() {
        try (McpClientConnector mcp = McpClientConnector.http(base()).credentials(TOKEN).build()) {
            mcp.start();
            assertThat(mcp.listTools()).extracting(ToolSpec::name).containsExactly("forecast", "temperature", "whoami");
            ToolOutcome forecast = mcp.invoke(ToolInvocation.of("forecast", Map.of("city", "Paris", "days", 2)));
            assertThat(forecast.error()).isFalse();
            assertThat(forecast.text()).contains("sunny", "cloudy");
            assertThat(mcp.invoke(ToolInvocation.of("whoami", Map.of())).text()).isEqualTo("demo-client");
        }
    }

    @Test
    void utcpClientReadsTheManualAndCallsATool() {
        try (UtcpClientConnector utcp = UtcpClientConnector.http("weather", base() + "/utcp").credentials(TOKEN)
                .variable("weather_WEATHER_TOKEN", "Bearer demo-token").build()) {
            utcp.start();
            assertThat(utcp.listTools()).extracting(ToolSpec::name).contains("temperature");
            ToolOutcome outcome = utcp.invoke(ToolInvocation.of("temperature", Map.of("body", Map.of("city", "Paris"))));
            assertThat(outcome.error()).isFalse();
            assertThat(Double.parseDouble(outcome.text())).isBetween(12.0, 27.0);
        }
    }

    @Test
    void a2aClientTalksToTheAgentIncludingAFollowUpQuestion() {
        try (A2aAgentConnector agent = A2aAgentConnector.builder(base()).credentials(TOKEN).build()) {
            agent.start();
            assertThat(agent.descriptor().name()).isEqualTo("weather-agent");
            assertThat(agent.send(AgentMessage.user("What is the weather in Paris?")).text()).contains("°C in Paris");

            AgentReply question = agent.send(AgentMessage.user("What is the weather?"));
            assertThat(question.state()).isEqualTo(TaskState.INPUT_REQUIRED);
            assertThat(question.text()).contains("Which city?");
            AgentReply answer = agent.send(AgentMessage.userReply(question.taskId(), question.contextId(), "Oslo"));
            assertThat(answer.text()).contains("°C in Oslo");
        }
    }

    @Test
    void endpointsRejectMissingTokensButActuatorReportsHealth() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> utcp = http.send(HttpRequest.newBuilder(URI.create(base() + "/utcp")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(utcp.statusCode()).isEqualTo(401);
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"protoweft\"");
    }
}
