package io.agentweave.evals.discovery.arms;

import io.agentweave.core.tool.ToolSpec;
import io.agentweave.evals.discovery.data.EvalCatalog;
import io.agentweave.evals.discovery.llm.ModelCallException;
import io.agentweave.evals.discovery.llm.ModelClient;
import io.agentweave.evals.discovery.llm.ModelRequest;
import io.agentweave.evals.discovery.llm.ModelResponse;
import io.agentweave.evals.discovery.llm.TokenEstimator;
import io.agentweave.evals.discovery.llm.ToolCall;
import io.agentweave.evals.discovery.llm.ToolResult;
import io.agentweave.evals.discovery.llm.Turn;
import io.agentweave.evals.discovery.llm.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The model ↔ tools loop shared by all arms. Each turn sends the conversation and the current
 * tool set; for every tool call in the reply, the arm's {@link CallHandler} decides:
 * <ul>
 *   <li>{@link Decision#select()} — the call is the model's tool choice; it is recorded as a real
 *       call and the loop ends after this turn (tools are never executed);</li>
 *   <li>{@link Decision#respond} — answer the call (for example with search results), optionally
 *       add tool definitions for the next turn (schemas loaded on demand), and continue. Mark it
 *       {@code discovery} to count it as a discovery call.</li>
 * </ul>
 * The loop also ends when a reply has no tool calls or after {@code maxTurns} model calls.
 * Single-turn arms use {@code maxTurns = 1} and a handler that always selects.
 * {@link ModelCallException}s end the loop with an error and keep the usage so far;
 * {@link io.agentweave.evals.discovery.cost.BudgetExceededException} propagates.
 */
public final class ToolLoop {

    /** The arm's answer to one tool call. */
    public record Decision(boolean selected, ToolResult result, List<ToolSpec> addTools, boolean discovery) {

        public static Decision select() {
            return new Decision(true, null, List.of(), false);
        }

        public static Decision respond(ToolResult result, List<ToolSpec> addTools, boolean discovery) {
            return new Decision(false, Objects.requireNonNull(result, "result"),
                    addTools == null ? List.of() : List.copyOf(addTools), discovery);
        }
    }

    /** Decides how to answer each tool call. {@code turn} is 1-based. */
    @FunctionalInterface
    public interface CallHandler {
        Decision handle(ToolCall call, int turn);
    }

    /**
     * The raw loop outcome.
     *
     * @param realCalls       non-discovery calls in chronological order: selected ones and any
     *                        the handler answered without marking them discovery
     * @param selected        the calls the handler selected
     * @param discoveryCalls  number of calls answered as discovery
     * @param responses       every model response, in order
     */
    public record Outcome(List<ToolCall> realCalls, List<ToolCall> selected, int discoveryCalls,
                          List<ModelResponse> responses, Usage usage, long latencyMillis, long toolPayloadTokens,
                          int toolsOffered, String finalText, boolean hitMaxTurns, String error) {
    }

    private ToolLoop() {
    }

    public static Outcome run(ModelClient client, String system, String userText, List<ToolSpec> initialTools,
                              int maxOutputTokens, int maxTurns, CallHandler handler, TokenEstimator estimator) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1");
        }
        List<Turn> conversation = new ArrayList<>(List.of(new Turn.User(userText)));
        Map<String, ToolSpec> tools = new LinkedHashMap<>();
        initialTools.forEach(t -> tools.put(t.name(), t));
        List<ToolCall> selected = new ArrayList<>();
        List<ToolCall> real = new ArrayList<>();
        List<ModelResponse> responses = new ArrayList<>();
        Usage usage = Usage.ZERO;
        long latency = 0;
        long payload = 0;
        int discovery = 0;
        String finalText = "";
        String error = null;
        boolean hitMax = false;
        for (int turn = 1; turn <= maxTurns; turn++) {
            ModelRequest request = new ModelRequest(system, conversation, List.copyOf(tools.values()), maxOutputTokens);
            if (estimator != null) {
                payload += estimator.tools(request.tools());
            }
            ModelResponse response;
            try {
                response = client.send(request);
            } catch (ModelCallException e) {
                error = e.getMessage();
                break;
            }
            responses.add(response);
            usage = usage.plus(response.usage());
            latency += response.latencyMillis();
            conversation.add(response.turn());
            finalText = response.turn().text();
            if (response.turn().toolCalls().isEmpty()) {
                break;
            }
            boolean stop = false;
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : response.turn().toolCalls()) {
                Decision d = handler.handle(call, turn);
                if (d.selected()) {
                    selected.add(call);
                    real.add(call);
                    stop = true;
                } else {
                    results.add(d.result());
                    d.addTools().forEach(t -> tools.putIfAbsent(t.name(), t));
                    if (d.discovery()) {
                        discovery++;
                    } else {
                        real.add(call);
                    }
                }
            }
            if (stop) {
                break;
            }
            if (turn == maxTurns) {
                hitMax = true;
                break;
            }
            conversation.add(new Turn.ToolResults(results));
        }
        return new Outcome(real, selected, discovery, responses, usage, latency, payload, initialTools.size(),
                finalText, hitMax, error);
    }

    /** Maps the real calls back to catalog tool ids. */
    public static List<SelectedCall> realCalls(Outcome outcome, EvalCatalog catalog) {
        List<SelectedCall> out = new ArrayList<>();
        for (ToolCall c : outcome.realCalls()) {
            out.add(new SelectedCall(catalog.toolIdForName(c.name()), c.name(), c.arguments(), c.argumentError()));
        }
        return out;
    }

    /** Builds an {@link ArmResult} from an outcome. */
    public static ArmResult toResult(String arm, String taskId, Outcome o, EvalCatalog catalog, Map<String, Object> extras) {
        return new ArmResult(arm, taskId, realCalls(o, catalog), o.discoveryCalls(), o.responses().size(), o.usage(),
                o.latencyMillis(), o.toolsOffered(), o.toolPayloadTokens(), o.finalText(), o.hitMaxTurns(), o.error(),
                extras);
    }
}
