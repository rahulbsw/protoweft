package io.agentweave.discovery.anthropic;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentweave.discovery.CapabilityCatalog;
import io.agentweave.discovery.DiscoverySession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static io.agentweave.discovery.anthropic.TestCatalogs.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Parses hand-written responses shaped like the tool-search documentation's examples. */
class MessageProcessingTest {

    private final CapabilityCatalog catalog = TestCatalogs.catalog();

    private static String fixture(String name) {
        try (InputStream in = MessageProcessingTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void hostedSearchBlocksAreEchoedUnchangedAndToolUseIsAnswered() {
        String raw = fixture("hosted-regex-response.json");
        Message response = TestCatalogs.parse(raw, Message.class);
        DiscoverySession session = new DiscoverySession(catalog);
        AnthropicDiscovery discovery = AnthropicDiscovery.builder(catalog).session(session)
                .mode(AnthropicDiscovery.Mode.HOSTED_REGEX).build();
        discovery.tools();

        AnthropicTurn turn = discovery.process(response);

        assertThat(response.content()).hasSize(5);
        assertThat(response.content().get(1).isServerToolUse()).isTrue();
        assertThat(response.content().get(2).isToolSearchToolResult()).isTrue();
        assertThat(turn.hasToolResults()).isTrue();
        ToolResultBlockParam result = turn.toolResults().get(0);
        assertThat(result.toolUseId()).isEqualTo("toolu_01KLMNOPQRST");
        assertThat(result.content().orElseThrow().asString()).isEqualTo("forecast: {city=Paris}");

        // The assistant message carries every block back, hosted-search blocks byte-for-byte.
        JsonNode original = json(TestCatalogs.parse(raw, Object.class)).get("content");
        JsonNode echoed = json(turn.assistantMessage());
        assertThat(echoed.get("role").asText()).isEqualTo("assistant");
        assertThat(echoed.get("content")).hasSize(5);
        assertThat(echoed.get("content").get(1)).isEqualTo(original.get(1));
        assertThat(echoed.get("content").get(2)).isEqualTo(original.get(2));
        assertThat(echoed.get("content").get(4)).isEqualTo(original.get(4));

        // Hosted-search hits are remembered in the session.
        assertThat(session.loaded()).containsExactly("weather.forecast");
    }

    @Test
    void catalogSearchToolUsesBecomeOneFollowUpUserMessage() {
        Message response = TestCatalogs.parse(fixture("catalog-search-response.json"), Message.class);
        AnthropicDiscovery discovery = AnthropicDiscovery.of(catalog);
        discovery.tools();

        AnthropicTurn turn = discovery.process(response);

        assertThat(turn.toolResults()).hasSize(2);
        ToolResultBlockParam search = turn.toolResults().get(0);
        assertThat(search.toolUseId()).isEqualTo("toolu_01SEARCH000001");
        assertThat(search.content().orElseThrow().asBlocks().get(0).asToolReference().toolName())
                .isEqualTo("crm__findCustomer");
        ToolResultBlockParam unknown = turn.toolResults().get(1);
        assertThat(unknown.isError()).contains(true);
        assertThat(unknown.content().orElseThrow().asString()).isEqualTo("unknown tool: crm__exportEverything");

        JsonNode user = json(turn.toolResultMessage().orElseThrow());
        assertThat(user.get("role").asText()).isEqualTo("user");
        assertThat(user.at("/content/0/type").asText()).isEqualTo("tool_result");
        assertThat(user.at("/content/0/content/0")).isEqualTo(json(java.util.Map.of(
                "type", "tool_reference", "tool_name", "crm__findCustomer")));

        MessageCreateParams next = turn.appendTo(MessageCreateParams.builder()
                .model("claude-sonnet-4-5").maxTokens(1024)
                .addUserMessage("Look up ada@example.com")
                .tools(discovery.tools())).build();
        assertThat(next.messages()).hasSize(3);
        assertThat(json(next.messages().get(1))).isEqualTo(json(turn.assistantMessage()));
    }

    @Test
    void responseWithoutToolUseNeedsNoFollowUp() {
        Message response = TestCatalogs.parse("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                 "content":[{"type":"text","text":"It is sunny in Paris."}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":7}}""", Message.class);

        AnthropicTurn turn = AnthropicDiscovery.of(catalog).process(response);

        assertThat(turn.hasToolResults()).isFalse();
        assertThat(turn.toolResultMessage()).isEmpty();
    }
}
