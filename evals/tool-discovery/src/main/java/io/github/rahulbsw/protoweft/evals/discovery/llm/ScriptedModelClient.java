package io.github.rahulbsw.protoweft.evals.discovery.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An offline {@link ModelClient} driven by a function, for tests and harness smoke runs. Records
 * every request it receives. No network, no keys.
 */
public final class ScriptedModelClient implements ModelClient {

    private final String provider;
    private final String model;
    private final Function<ModelRequest, ModelResponse> script;
    private final List<ModelRequest> requests = new ArrayList<>();

    public ScriptedModelClient(String provider, String model, Function<ModelRequest, ModelResponse> script) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = Objects.requireNonNull(model, "model");
        this.script = Objects.requireNonNull(script, "script");
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public synchronized ModelResponse send(ModelRequest request) {
        requests.add(request);
        return script.apply(request);
    }

    /** Requests received so far, in order. */
    public synchronized List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    /** A reply that calls one tool. */
    public static ModelResponse callTool(String name, java.util.Map<String, Object> args, Usage usage) {
        ToolCall call = ToolCall.of("call_" + Integer.toHexString(Objects.hash(name, args)), name, args);
        return new ModelResponse(new Turn.Assistant("", List.of(call), null), usage, 1, "tool_use");
    }

    /** A text-only reply. */
    public static ModelResponse text(String text, Usage usage) {
        return new ModelResponse(new Turn.Assistant(text, List.of(), null), usage, 1, "end_turn");
    }
}
