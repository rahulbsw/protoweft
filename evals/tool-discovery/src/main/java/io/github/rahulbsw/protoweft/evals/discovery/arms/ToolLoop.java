package io.github.rahulbsw.protoweft.evals.discovery.arms;

import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalCatalog;
import io.github.rahulbsw.protoweft.evals.discovery.fixtures.MockResultProvider;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.HistoryPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.shaping.ResultPolicy;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelCallException;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelRequest;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelResponse;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenBreakdown;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolCall;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Usage;

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
 * {@link io.github.rahulbsw.protoweft.evals.discovery.cost.BudgetExceededException} propagates.
 */
public final class ToolLoop {

    /**
     * One request's reconciliation point: the provider's real input tokens for a single model call
     * and the jtokkit estimate of that same request, split into segments. Summing these over a loop
     * gives the cumulative figures; keeping them per request lets the report reconcile per call (the
     * residual then isolates provider framing/tokenizer overhead from cumulative re-send effects).
     *
     * @param realInput provider {@code usage.inputTokens} for this one call (0 if the call errored)
     * @param estimate  the jtokkit estimate of this one request
     */
    public record RequestTokens(long realInput, TokenBreakdown estimate) {
        public long residual() {
            return realInput - estimate.total();
        }
    }

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
     * Opt-in multi-turn execution. When present, a {@link Decision#select() selected} call is not
     * the end of the loop: it is recorded as a real call, answered with a mocked
     * {@link io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult} from {@code provider}, and
     * the loop continues so later results and the history they create are sent and counted. The
     * loop stops once {@code maxRealCalls} selected calls have been made (the reference trajectory's
     * gold-call count), or the model stops on its own, or {@code maxTurns} is reached.
     *
     * @param provider      supplies the mocked result for each selected call
     * @param maxRealCalls  the trajectory bound; at least 1
     * @param resultPolicy  shapes each mocked result before it is fed back
     * @param historyPolicy shapes the conversation re-sent on each turn
     * @param fixtureCall   maps the transport-level call to the logical (tool, arguments) used for
     *                      fixture lookup — identity for direct arms, an unwrapper for discovery
     *                      arms whose selection is {@code call_capability(name, arguments)}; it
     *                      preserves the call id so the mocked result answers the right call
     */
    public record MockMode(MockResultProvider provider, int maxRealCalls, ResultPolicy resultPolicy,
                           HistoryPolicy historyPolicy, java.util.function.UnaryOperator<ToolCall> fixtureCall) {
        public MockMode {
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(resultPolicy, "resultPolicy");
            java.util.Objects.requireNonNull(historyPolicy, "historyPolicy");
            java.util.Objects.requireNonNull(fixtureCall, "fixtureCall");
            if (maxRealCalls < 1) {
                throw new IllegalArgumentException("maxRealCalls must be >= 1");
            }
        }

        public MockMode(MockResultProvider provider, int maxRealCalls, ResultPolicy resultPolicy,
                        HistoryPolicy historyPolicy) {
            this(provider, maxRealCalls, resultPolicy, historyPolicy, java.util.function.UnaryOperator.identity());
        }

        /** Raw results and full history (the default shaping). */
        public MockMode(MockResultProvider provider, int maxRealCalls) {
            this(provider, maxRealCalls, ResultPolicy.raw(), HistoryPolicy.full());
        }
    }

    /**
     * The raw loop outcome.
     *
     * @param realCalls       non-discovery calls in chronological order: selected ones and any
     *                        the handler answered without marking them discovery
     * @param selected        the calls the handler selected
     * @param discoveryCalls  number of calls answered as discovery
     * @param responses       every model response, in order
     * @param estimated       the jtokkit input estimate accumulated <b>cumulatively</b> over every
     *                        request the loop sent, split system/definitions/results/history;
     *                        {@code definitions} equals {@code toolPayloadTokens}. Reconcile against
     *                        {@code usage.inputTokens} (residual = provider minus estimate)
     * @param perRequest      one {@link RequestTokens} per model call, for per-request reconciliation
     */
    public record Outcome(List<ToolCall> realCalls, List<ToolCall> selected, int discoveryCalls,
                          List<ModelResponse> responses, Usage usage, long latencyMillis, long toolPayloadTokens,
                          int toolsOffered, String finalText, boolean hitMaxTurns, String error,
                          TokenBreakdown estimated, List<RequestTokens> perRequest) {
    }

    private ToolLoop() {
    }

    public static Outcome run(ModelClient client, String system, String userText, List<ToolSpec> initialTools,
                              int maxOutputTokens, int maxTurns, CallHandler handler, TokenEstimator estimator) {
        return run(client, system, userText, initialTools, List.of(), maxOutputTokens, maxTurns, handler, estimator);
    }

    /**
     * As {@link #run(ModelClient, String, String, List, int, int, CallHandler, TokenEstimator)},
     * plus provider-specific raw tool definitions sent unchanged on every turn (native arms).
     * The payload estimate counts them with {@link TokenEstimator#rawTools}.
     */
    public static Outcome run(ModelClient client, String system, String userText, List<ToolSpec> initialTools,
                              List<Map<String, Object>> rawTools, int maxOutputTokens, int maxTurns,
                              CallHandler handler, TokenEstimator estimator) {
        return run(client, system, userText, initialTools, rawTools, maxOutputTokens, maxTurns, handler, estimator, null);
    }

    /**
     * As the raw-tools overload, plus an optional {@link MockMode}. When {@code mock} is
     * {@code null} the loop is single-shot per selection (existing behaviour); when present, each
     * selected call is answered with a mocked result and the loop continues (see {@link MockMode}).
     */
    public static Outcome run(ModelClient client, String system, String userText, List<ToolSpec> initialTools,
                              List<Map<String, Object>> rawTools, int maxOutputTokens, int maxTurns,
                              CallHandler handler, TokenEstimator estimator, MockMode mock) {
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
        TokenBreakdown estimated = TokenBreakdown.ZERO;
        List<RequestTokens> perRequest = new ArrayList<>();
        int discovery = 0;
        String finalText = "";
        String error = null;
        boolean hitMax = false;
        List<ToolResult> pendingResults = null;  // the last turn's results when the trajectory bound is hit
        for (int turn = 1; turn <= maxTurns; turn++) {
            List<Turn> sent = mock == null ? conversation : mock.historyPolicy().apply(conversation);
            ModelRequest request = new ModelRequest(system, sent, List.copyOf(tools.values()), maxOutputTokens,
                    rawTools);
            TokenBreakdown requestEstimate = estimator == null ? TokenBreakdown.ZERO : estimator.breakdown(request);
            estimated = estimated.plus(requestEstimate);
            ModelResponse response;
            try {
                response = client.send(request);
            } catch (ModelCallException e) {
                error = e.getMessage();
                break;
            }
            responses.add(response);
            perRequest.add(new RequestTokens(response.usage().inputTokens(), requestEstimate));
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
                    if (mock == null) {
                        stop = true;
                    } else {
                        ToolResult mocked = mock.provider().result(mock.fixtureCall().apply(call));
                        results.add(estimator == null ? mocked : mock.resultPolicy().shape(mocked, estimator));
                        if (selected.size() >= mock.maxRealCalls()) {
                            stop = true;
                        }
                    }
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
                if (mock != null) {
                    pendingResults = results;  // trajectory complete: feed these to a final synthesis turn
                }
                break;
            }
            if (turn == maxTurns) {
                hitMax = true;
                break;
            }
            conversation.add(new Turn.ToolResults(results));
        }
        // Multi-turn trajectory complete: one more call so the model produces a final answer
        // (state-based success scores against it). Tools stay in the request: providers (e.g.
        // Bedrock via a proxy) reject an empty tool set when the conversation carries tool_use /
        // tool_result blocks. The model usually answers in text now that it holds every result.
        if (mock != null && pendingResults != null && error == null) {
            // Force a text answer with tool_choice=none. Tools stay in the payload (providers reject
            // an empty tool set after tool_use/tool_result history), but the model may not call them.
            conversation.add(new Turn.ToolResults(pendingResults));
            List<Turn> sent = mock.historyPolicy().apply(conversation);
            ModelRequest finalRequest = new ModelRequest(system, sent, List.copyOf(tools.values()), maxOutputTokens,
                    rawTools, true);
            TokenBreakdown finalEstimate = estimator == null ? TokenBreakdown.ZERO : estimator.breakdown(finalRequest);
            estimated = estimated.plus(finalEstimate);
            try {
                ModelResponse finalResponse = client.send(finalRequest);
                responses.add(finalResponse);
                perRequest.add(new RequestTokens(finalResponse.usage().inputTokens(), finalEstimate));
                usage = usage.plus(finalResponse.usage());
                latency += finalResponse.latencyMillis();
                finalText = finalResponse.turn().text();
            } catch (ModelCallException e) {
                error = e.getMessage();
            }
        }
        return new Outcome(real, selected, discovery, responses, usage, latency, estimated.definitions(),
                initialTools.size() + rawTools.size(), finalText, hitMax, error, estimated, List.copyOf(perRequest));
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
                o.latencyMillis(), o.toolsOffered(), o.toolPayloadTokens(), o.estimated(), o.perRequest(), o.finalText(),
                o.hitMaxTurns(), o.error(), extras);
    }
}
