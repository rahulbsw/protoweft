package io.agentweave.evals.discovery.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCountTokensParams;
import io.agentweave.core.tool.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * Exact Claude input-token counts via {@code POST /v1/messages/count_tokens} (the SDK's
 * {@code messages().countTokens}). Claude does not use OpenAI's tokenizer, so jtokkit is only an
 * approximation for Anthropic. Optional: the runner uses it only with {@code --count-tokens}.
 * Counting is free but rate-limited, and it is not a model call (no cost-guard charge).
 */
public final class AnthropicTokenCounter {

    private final AnthropicClient client;
    private final String model;

    public AnthropicTokenCounter(AnthropicClient client, String model) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
    }

    public static AnthropicTokenCounter fromEnv(String model) {
        return new AnthropicTokenCounter(
                AnthropicOkHttpClient.builder().apiKey(AnthropicModelClient.requireKey()).build(), model);
    }

    /** Input tokens of a full request (system, conversation and tools). */
    public long count(ModelRequest request) {
        MessageCountTokensParams.Builder b = MessageCountTokensParams.builder().model(model);
        if (request.system() != null && !request.system().isBlank()) {
            b.system(request.system());
        }
        if (request.rawTools().isEmpty()) {
            request.tools().forEach(t -> b.addTool(AnthropicModelClient.toTool(t)));
        } else {
            b.putAdditionalBodyProperty("tools",
                    com.anthropic.core.JsonValue.from(AnthropicModelClient.rawToolArray(request, false)));
        }
        AnthropicModelClient.messages(request.conversation()).forEach(b::addMessage);
        try {
            return client.messages().countTokens(b.build()).inputTokens();
        } catch (AnthropicServiceException e) {
            throw new ModelCallException("count_tokens error " + e.statusCode() + ": " + e.getMessage(), e.statusCode(), e);
        }
    }

    /**
     * Tokens contributed by the tool definitions alone: count with tools minus count without,
     * for the same one-message conversation.
     */
    public long toolDefinitionTokens(List<ToolSpec> tools) {
        List<Turn> probe = List.of(new Turn.User("hi"));
        long with = count(new ModelRequest(null, probe, tools, 1));
        long without = count(new ModelRequest(null, probe, List.of(), 1));
        return with - without;
    }
}
