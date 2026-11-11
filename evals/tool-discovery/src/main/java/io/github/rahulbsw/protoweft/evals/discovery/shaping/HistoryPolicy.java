package io.github.rahulbsw.protoweft.evals.discovery.shaping;

import io.github.rahulbsw.protoweft.evals.discovery.llm.ToolResult;
import io.github.rahulbsw.protoweft.evals.discovery.llm.Turn;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes the conversation that is re-sent on each turn — the history-side half of the study's
 * factorial. {@code full} resends everything (the default); {@code clear-stale} replaces every tool
 * result except the most recent with a short stub, so earlier results stop compounding in input
 * every turn. The assistant turns (and their provider state) are never altered, so provider replay
 * of reasoning items is preserved. Applied as a view when building each request; the loop's own
 * conversation is untouched.
 */
public interface HistoryPolicy {

    String name();

    /** The conversation as it should be sent this turn. */
    List<Turn> apply(List<Turn> conversation);

    /** Resend the whole conversation unchanged. */
    static HistoryPolicy full() {
        return new HistoryPolicy() {
            public String name() {
                return "full";
            }

            public List<Turn> apply(List<Turn> conversation) {
                return conversation;
            }
        };
    }

    /** Keep only the most recent tool-results turn; stub the content of all earlier ones. */
    static HistoryPolicy clearStale() {
        return new HistoryPolicy() {
            public String name() {
                return "clear-stale";
            }

            public List<Turn> apply(List<Turn> conversation) {
                int lastResults = -1;
                for (int i = 0; i < conversation.size(); i++) {
                    if (conversation.get(i) instanceof Turn.ToolResults) {
                        lastResults = i;
                    }
                }
                if (lastResults < 0) {
                    return conversation;
                }
                List<Turn> out = new ArrayList<>(conversation.size());
                for (int i = 0; i < conversation.size(); i++) {
                    Turn t = conversation.get(i);
                    if (i != lastResults && t instanceof Turn.ToolResults r) {
                        List<ToolResult> stubbed = r.results().stream()
                                .map(res -> new ToolResult(res.callId(), "[result cleared]", res.error()))
                                .toList();
                        out.add(new Turn.ToolResults(stubbed));
                    } else {
                        out.add(t);
                    }
                }
                return out;
            }
        };
    }

    /** Parses {@code full} or {@code clear-stale}. */
    static HistoryPolicy parse(String spec) {
        String s = spec == null ? "full" : spec.strip();
        return switch (s) {
            case "full" -> full();
            case "clear-stale" -> clearStale();
            default -> throw new IllegalArgumentException("unknown history policy: " + spec + " (full|clear-stale)");
        };
    }
}
