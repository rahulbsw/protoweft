package io.agentweave.examples.spring;

import io.agentweave.a2a.A2aAgentConnector;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentReply;
import io.agentweave.core.agent.TaskState;
import io.agentweave.core.auth.Credential;
import io.agentweave.core.auth.CredentialProvider;
import io.agentweave.core.tool.ToolInvocation;
import io.agentweave.core.tool.ToolOutcome;
import io.agentweave.core.tool.ToolSpec;
import io.agentweave.mcp.McpClientConnector;
import io.agentweave.utcp.UtcpClientConnector;
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

/** The running example, called through each protocol with AgentWeave's own clients. */
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
        assertThat(health.body()).contains("\"agentweave\"");
    }
}
