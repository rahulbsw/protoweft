package io.github.rahulbsw.protoweft.evals.discovery.metrics;

import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.data.EvalTask;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelCallException;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelClient;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelRequest;
import io.github.rahulbsw.protoweft.evals.discovery.llm.ModelResponse;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A model-based grader of an agent's free-text final answer, for tasks where exact-argument and
 * substring checks are too brittle. Given the task and the final answer, the judge model returns a
 * strict JSON verdict {@code {"pass": bool, "reason": str}}. Deterministic scorers
 * ({@link CallScorer}) stay the primary metrics; the judge is an optional, separately-priced extra
 * so free-text success can be read without the exact-match floor near 50%.
 *
 * <p>The judge is a plain {@link ModelClient} call with no tools; wrap it in the cost guard like any
 * other. A parse failure or model error grades as not passed, with the reason recorded.
 */
public final class LlmJudge {

    static final String SYSTEM = "You grade whether an AI agent completed a multi-step task, judging only "
            + "from its final answer. Be strict and literal: pass only if the answer actually reports the "
            + "requested outcome. Reply with one line of JSON and nothing else.";

    private final ModelClient client;
    private final int maxOutputTokens;

    public LlmJudge(ModelClient client) {
        this(client, 256);
    }

    public LlmJudge(ModelClient client, int maxOutputTokens) {
        this.client = Objects.requireNonNull(client, "client");
        this.maxOutputTokens = maxOutputTokens;
    }

    /** The judge's verdict on an agent's final answer to a task. */
    public record Verdict(boolean pass, String reason) {
    }

    public Verdict judge(EvalTask task, String finalAnswer) {
        String facts = task.requiredFacts().isEmpty() ? "(none specified)" : String.join("; ", task.requiredFacts());
        String answer = finalAnswer == null || finalAnswer.isBlank() ? "(the agent produced no final answer)"
                : finalAnswer;
        String prompt = "Task given to the agent:\n" + task.query()
                + "\n\nFacts the answer should contain, if any: " + facts
                + "\n\nThe agent's final answer:\n" + answer
                + "\n\nDid the agent accomplish the task? Reply ONLY with JSON: "
                + "{\"pass\": true or false, \"reason\": \"<one short sentence>\"}.";
        ModelRequest request = new ModelRequest(SYSTEM, List.of(new Turn.User(prompt)), List.of(), maxOutputTokens);
        try {
            ModelResponse response = client.send(request);
            return parse(response.turn().text());
        } catch (ModelCallException e) {
            return new Verdict(false, "judge error: " + e.getMessage());
        }
    }

    private static final java.util.regex.Pattern PASS =
            java.util.regex.Pattern.compile("\"?pass\"?\\s*[:=]\\s*(true|false)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Extracts {@code {"pass",...}} from the model's reply; a missing/unparseable verdict is a fail. */
    static Verdict parse(String text) {
        if (text == null) {
            return new Verdict(false, "no judge output");
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            try {
                Map<String, Object> v = Json.parseObject(text.substring(open, close + 1));
                boolean pass = Boolean.TRUE.equals(v.get("pass"))
                        || "true".equalsIgnoreCase(String.valueOf(v.get("pass")));
                String reason = v.get("reason") == null ? "" : String.valueOf(v.get("reason"));
                return new Verdict(pass, reason);
            } catch (RuntimeException ignored) {
                // fall through to the regex fallback below
            }
        }
        // Fallback: the model emitted a pass/fail without clean JSON (code fences, prose, reasoning).
        java.util.regex.Matcher m = PASS.matcher(text);
        if (m.find()) {
            return new Verdict("true".equalsIgnoreCase(m.group(1)), "parsed from non-JSON output");
        }
        return new Verdict(false, "unparseable judge output");
    }
}
