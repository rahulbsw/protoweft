package io.agentweave.argi.neutral;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.Usage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * A neutral ARGI {@link ChatClient} with no model: each call answers with the next scripted turn and
 * records the request. Streaming emits the answer's words as text deltas, then the response.
 */
final class ScriptedChatClient implements ChatClient {

    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<Function<ChatRequest, ChatResponse>> turns = new ArrayDeque<>();

    @SafeVarargs
    ScriptedChatClient(Function<ChatRequest, ChatResponse>... turns) {
        this.turns.addAll(List.of(turns));
    }

    static Function<ChatRequest, ChatResponse> answer(String text) {
        return request -> response(ChatMessage.assistant(text), List.of());
    }

    static Function<ChatRequest, ChatResponse> callTool(String name, String arguments) {
        return request -> {
            ToolCall call = new ToolCall("call-" + request.messages().size(), name, arguments);
            return response(ChatMessage.assistant(null, List.of(call)), List.of(call));
        };
    }

    private static ChatResponse response(ChatMessage message, List<ToolCall> calls) {
        return new ChatResponse("r", "scripted", List.of(message), calls,
                calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS, new Usage(1L, 1L, 2L), Map.of());
    }

    private synchronized ChatResponse next(ChatRequest request) {
        requests.add(request);
        Function<ChatRequest, ChatResponse> turn = turns.poll();
        if (turn == null) {
            throw new IllegalStateException("no scripted turn left");
        }
        return turn.apply(request);
    }

    @Override
    public CompletionStage<ChatResponse> call(ChatRequest request) {
        try {
            return CompletableFuture.completedFuture(next(request));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            ChatResponse response;
            try {
                response = next(request);
            } catch (RuntimeException e) {
                subscriber.onError(e);
                return;
            }
            for (String word : response.text().split("(?<= )")) {
                if (!word.isEmpty()) {
                    subscriber.onNext(ChatEvent.textDelta(word));
                }
            }
            subscriber.onNext(ChatEvent.response(response));
            subscriber.onComplete();
        };
    }

    @Override
    public ChatClientCapabilities capabilities() {
        return new ChatClientCapabilities(true, true, false, false, true, false);
    }
}
