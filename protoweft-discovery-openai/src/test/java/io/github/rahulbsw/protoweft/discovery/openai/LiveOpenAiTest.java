package io.github.rahulbsw.protoweft.discovery.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import io.github.rahulbsw.protoweft.discovery.DiscoverySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real client-executed tool-search round trip. Runs only when {@code OPENAI_API_KEY} and
 * {@code OPENAI_MODEL} (a model with tool search, gpt-5.4 or later) are set; skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OPENAI_MODEL", matches = ".+")
class LiveOpenAiTest {

    @Test
    void modelSearchesTheCatalogAndCallsTheFoundTool() {
        DiscoverySession session = new DiscoverySession(Fixtures.catalog(new Fixtures.FileTools()));
        OpenAiDiscovery discovery = OpenAiDiscovery.builder(session).build();
        OpenAIClient client = OpenAIOkHttpClient.fromEnv();
        List<String> seen = new ArrayList<>();
        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(System.getenv("OPENAI_MODEL"))
                    .input("What is the weather forecast for Paris? Find a suitable tool and use it.")
                    .tools(discovery.tools())
                    .parallelToolCalls(false)
                    .build();
            for (int turn = 0; turn < 5; turn++) {
                Response response = client.responses().create(params);
                for (ResponseOutputItem item : response.output()) {
                    if (item.isToolSearchCall()) {
                        seen.add("tool_search_call");
                    } else if (item.isFunctionCall()) {
                        seen.add(item.asFunctionCall().name());
                    }
                }
                List<ResponseInputItem> next = discovery.nextInput(response);
                if (next.isEmpty()) {
                    break;
                }
                params = ResponseCreateParams.builder()
                        .model(System.getenv("OPENAI_MODEL"))
                        .previousResponseId(response.id())
                        .inputOfResponse(next)
                        .tools(discovery.tools())
                        .parallelToolCalls(false)
                        .build();
            }
        } finally {
            client.close();
        }
        assertThat(seen).contains("tool_search_call", "weather__forecast");
        assertThat(session.loaded()).contains("weather.forecast");
    }
}
