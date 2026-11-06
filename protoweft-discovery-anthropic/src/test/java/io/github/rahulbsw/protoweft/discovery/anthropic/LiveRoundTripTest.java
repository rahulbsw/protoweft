package io.github.rahulbsw.protoweft.discovery.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoverySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: one real conversation against the Messages API, run only when
 * {@code ANTHROPIC_API_KEY} and {@code ANTHROPIC_MODEL} are set. It shows that the
 * {@code tool_reference} result of our search tool makes Anthropic load a deferred tool, which
 * the model then calls.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_MODEL", matches = ".+")
class LiveRoundTripTest {

    @Test
    void searchLoadsADeferredToolThatTheModelThenCalls() {
        CapabilityCatalog catalog = TestCatalogs.catalog();
        AnthropicDiscovery discovery = AnthropicDiscovery.builder(catalog)
                .session(new DiscoverySession(catalog)).build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(System.getenv("ANTHROPIC_MODEL"))
                .maxTokens(1024)
                .tools(discovery.tools())
                .addUserMessage("What is the weather forecast for Paris? Search for a suitable tool, then use it.")
                .build();

        List<String> called = new ArrayList<>();
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            for (int round = 0; round < 5; round++) {
                Message response = client.messages().create(params);
                response.content().stream().filter(ContentBlock::isToolUse)
                        .forEach(b -> called.add(b.asToolUse().name()));
                AnthropicTurn turn = discovery.process(response);
                if (!turn.hasToolResults()) {
                    break;
                }
                params = turn.appendTo(params.toBuilder()).build();
            }
        } finally {
            client.close();
        }

        assertThat(called).contains("search_capabilities", "weather__forecast");
        assertThat(called.indexOf("search_capabilities")).isLessThan(called.indexOf("weather__forecast"));
    }
}
