package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks an event stream against the spec's sequence rules (run lifecycle, the streaming triads,
 * reasoning spans, steps, subagent lifecycle and attribution). Chunk events must already be
 * expanded. A violation throws {@link AgUiFormatException}; the stream is then untrustworthy.
 *
 * <p>Not thread-safe; one instance per stream.
 */
final class StreamVerifier {

    private enum Phase { BEFORE, ACTIVE, FINISHED, ERRORED }

    private Phase phase = Phase.BEFORE;
    private String runId;
    private String threadId;
    /** Open items: id → owner ({@code ""} for the parent agent). */
    private final Map<String, String> messages = new HashMap<>();
    private final Map<String, String> toolCalls = new HashMap<>();
    private final Map<String, String> reasoningSpans = new HashMap<>();
    private final Map<String, String> reasoningMessages = new HashMap<>();
    /** Open steps, keyed by owner + name: steps are per owner. */
    private final Set<String> steps = new HashSet<>();
    private final Set<String> activeSubagents = new HashSet<>();
    private final Set<String> finishedSubagents = new HashSet<>();
    /** Tool calls started this run, in order, mapped to whether a result arrived. */
    private final Map<String, Boolean> calls = new LinkedHashMap<>();

    /** @throws AgUiFormatException if {@code event} may not come next */
    void accept(AgUiEvent event) {
        if (event instanceof RunStarted e) {
            if (phase == Phase.ACTIVE) {
                fail("RUN_STARTED while run " + runId + " is still active");
            }
            reset();
            phase = Phase.ACTIVE;
            runId = e.runId();
            threadId = e.threadId();
            return;
        } else if (event instanceof RunError e) {
            if (phase == Phase.ERRORED) {
                fail("RUN_ERROR after RUN_ERROR");
            }
            phase = Phase.ERRORED;
            reset();
            return;
        }
        if (phase == Phase.BEFORE) {
            fail("the stream must begin with RUN_STARTED or RUN_ERROR, not " + event.type());
        }
        if (phase != Phase.ACTIVE) {
            fail(event.type() + " after the run closed");
        }
        String owner = owner(event);
        if (event instanceof RunFinished e) {
            finish(e);
        } else if (event instanceof StepStarted e) {
            if (!steps.add(owner + "\u0000" + e.stepName())) {
                fail("step '" + e.stepName() + "' is already open");
            }
        } else if (event instanceof StepFinished e) {
            if (!steps.remove(owner + "\u0000" + e.stepName())) {
                fail("step '" + e.stepName() + "' is not open for this owner");
            }
        } else if (event instanceof TextMessageStart e) {
            open(messages, e.messageId(), owner, "text message");
        } else if (event instanceof TextMessageContent e) {
            continueItem(messages, e.messageId(), event, "text message");
        } else if (event instanceof TextMessageEnd e) {
            close(messages, e.messageId(), event, "text message");
        } else if (event instanceof ToolCallStart e) {
            open(toolCalls, e.toolCallId(), owner, "tool call");
            calls.putIfAbsent(e.toolCallId(), false);
        } else if (event instanceof ToolCallArgs e) {
            continueItem(toolCalls, e.toolCallId(), event, "tool call");
        } else if (event instanceof ToolCallEnd e) {
            close(toolCalls, e.toolCallId(), event, "tool call");
        } else if (event instanceof ToolCallResult e) {
            calls.replace(e.toolCallId(), true);
        } else if (event instanceof ReasoningStart e) {
            open(reasoningSpans, e.messageId(), owner, "reasoning span");
        } else if (event instanceof ReasoningEnd e) {
            close(reasoningSpans, e.messageId(), event, "reasoning span");
        } else if (event instanceof ReasoningMessageStart e) {
            open(reasoningMessages, e.messageId(), owner, "reasoning message");
        } else if (event instanceof ReasoningMessageContent e) {
            continueItem(reasoningMessages, e.messageId(), event, "reasoning message");
        } else if (event instanceof ReasoningMessageEnd e) {
            close(reasoningMessages, e.messageId(), event, "reasoning message");
        } else if (event instanceof SubagentStarted e) {
            if (activeSubagents.contains(e.subagentRunId()) || finishedSubagents.contains(e.subagentRunId())) {
                fail("subagent invocation " + e.subagentRunId() + " was already announced in this run");
            }
            if (e.parentSubagentRunId() != null && !activeSubagents.contains(e.parentSubagentRunId())
                    && !finishedSubagents.contains(e.parentSubagentRunId())) {
                fail("parent subagent invocation " + e.parentSubagentRunId() + " was not announced");
            }
            activeSubagents.add(e.subagentRunId());
        } else if (event instanceof SubagentFinished e) {
            endSubagent(e.subagentRunId());
        } else if (event instanceof SubagentError e) {
            endSubagent(e.subagentRunId());
        } else if (event instanceof TextMessageChunk e) {
            fail("unexpanded TEXT_MESSAGE_CHUNK");
        } else if (event instanceof ToolCallChunk e) {
            fail("unexpanded TOOL_CALL_CHUNK");
        } else if (event instanceof ReasoningMessageChunk e) {
            fail("unexpanded REASONING_MESSAGE_CHUNK");
        }
        // Standalone events (state, messages snapshot, activity, encrypted value, raw, custom) open
        // and close nothing.
    }

    /** Whether a run is open. */
    boolean active() {
        return phase == Phase.ACTIVE;
    }

    /** Tool calls the current run started and has not answered with a result, in call order. */
    List<String> unansweredToolCalls() {
        List<String> out = new ArrayList<>();
        calls.forEach((id, answered) -> {
            if (!answered) {
                out.add(id);
            }
        });
        return out;
    }

    /** What the current run still has open, for a producer closing it on the handler's behalf. */
    Open open() {
        return new Open(List.copyOf(messages.keySet()), List.copyOf(toolCalls.keySet()),
                List.copyOf(reasoningMessages.keySet()), List.copyOf(reasoningSpans.keySet()),
                steps.stream().map(s -> {
                    int split = s.indexOf('\u0000');
                    String owner = s.substring(0, split);
                    return new Step(owner.isEmpty() ? null : owner, s.substring(split + 1));
                }).toList(),
                List.copyOf(activeSubagents));
    }

    /** @param owner the subagent invocation, or {@code null} for the parent agent */
    record Step(String owner, String name) {
    }

    record Open(List<String> messages, List<String> toolCalls, List<String> reasoningMessages,
                List<String> reasoningSpans, List<Step> steps, List<String> subagents) {
    }

    private void finish(RunFinished e) {
        if (!Objects.equals(e.runId(), runId) || !Objects.equals(e.threadId(), threadId)) {
            fail("RUN_FINISHED names run " + e.runId() + " but run " + runId + " is active");
        }
        List<String> left = new ArrayList<>();
        messages.keySet().forEach(id -> left.add("text message " + id));
        toolCalls.keySet().forEach(id -> left.add("tool call " + id));
        reasoningSpans.keySet().forEach(id -> left.add("reasoning span " + id));
        reasoningMessages.keySet().forEach(id -> left.add("reasoning message " + id));
        steps.forEach(s -> left.add("step " + s.substring(s.indexOf('\u0000') + 1)));
        activeSubagents.forEach(id -> left.add("subagent " + id));
        if (!left.isEmpty()) {
            fail("RUN_FINISHED with items still open: " + String.join(", ", left));
        }
        if (e.outcome() instanceof RunOutcome.Interrupted i) {
            Set<String> ids = new HashSet<>();
            for (Interrupt interrupt : i.interrupts()) {
                if (!ids.add(interrupt.id())) {
                    fail("interrupt id " + interrupt.id() + " is not unique in the run");
                }
            }
        }
        if (e.outcome() instanceof RunOutcome.Success s && s.pendingToolCallIds() != null
                && !s.pendingToolCallIds().isEmpty() && !s.pendingToolCallIds().equals(unansweredToolCalls())) {
            fail("pendingToolCallIds " + s.pendingToolCallIds() + " differ from the unanswered calls "
                    + unansweredToolCalls());
        }
        phase = Phase.FINISHED;
    }

    private void endSubagent(String id) {
        if (finishedSubagents.contains(id)) {
            fail("subagent invocation " + id + " already ended");
        }
        activeSubagents.remove(id);
        finishedSubagents.add(id);
    }

    private void open(Map<String, String> items, String id, String owner, String what) {
        if (items.containsKey(id)) {
            fail(what + " " + id + " is already open");
        }
        items.put(id, owner);
    }

    private void continueItem(Map<String, String> items, String id, AgUiEvent event, String what) {
        String opener = items.get(id);
        if (opener == null) {
            fail(event.type() + " for " + what + " " + id + ", which is not open");
        }
        checkOwner(opener, event, what, id);
    }

    private void close(Map<String, String> items, String id, AgUiEvent event, String what) {
        continueItem(items, id, event, what);
        items.remove(id);
    }

    /** A continuation may omit its tag; one it carries must agree with the opener's. */
    private void checkOwner(String opener, AgUiEvent event, String what, String id) {
        String tag = event.envelope() == null ? null : event.envelope().subagentRunId();
        if (tag != null && !tag.equals(opener)) {
            fail(event.type() + " for " + what + " " + id + " is attributed to " + tag
                    + " but the item belongs to " + (opener.isEmpty() ? "the parent agent" : opener));
        }
    }

    private static String owner(AgUiEvent event) {
        String tag = event.envelope() == null ? null : event.envelope().subagentRunId();
        return tag == null ? "" : tag;
    }

    private void reset() {
        messages.clear();
        toolCalls.clear();
        reasoningSpans.clear();
        reasoningMessages.clear();
        steps.clear();
        activeSubagents.clear();
        finishedSubagents.clear();
        calls.clear();
    }

    private static void fail(String message) {
        throw new AgUiFormatException(message);
    }
}
