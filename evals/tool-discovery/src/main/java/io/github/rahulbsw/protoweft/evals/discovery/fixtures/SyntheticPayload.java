package io.github.rahulbsw.protoweft.evals.discovery.fixtures;

import io.github.rahulbsw.protoweft.evals.discovery.Json;
import io.github.rahulbsw.protoweft.evals.discovery.llm.TokenEstimator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * A plausible JSON tool result sized to a target token count, used when no fixture matches a call.
 * {@code ToolSpec} carries no output schema, so this is not validated against a tool's output
 * contract; it is a well-formed JSON object padded to the target so a miss is sized like a real
 * result rather than a token-free stub. Padding is measured with the same {@link TokenEstimator} the
 * accounting uses, so the produced size matches the target within one filler word.
 */
public final class SyntheticPayload {

    private static final String[] VOCAB = {
            "lorem", "ipsum", "dolor", "value", "result", "item", "field", "record", "entry", "data"};

    private SyntheticPayload() {
    }

    /** A JSON object for {@code tool} whose estimated size is at least {@code targetTokens}. */
    public static String of(String tool, int targetTokens, TokenEstimator estimator, Random random) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("synthetic", true);
        obj.put("tool", tool);
        obj.put("data", "");
        StringBuilder data = new StringBuilder();
        // Each VOCAB word is about one token, so this converges; the guard bounds worst case.
        for (int guard = 0; guard <= targetTokens + 64; guard++) {
            obj.put("data", data.toString());
            long current = estimator.text(Json.write(obj));
            if (current >= targetTokens) {
                break;
            }
            int need = (int) Math.max(1, targetTokens - current);
            for (int i = 0; i < need; i++) {
                if (data.length() > 0) {
                    data.append(' ');
                }
                data.append(VOCAB[random.nextInt(VOCAB.length)]);
            }
        }
        return Json.write(obj);
    }
}
