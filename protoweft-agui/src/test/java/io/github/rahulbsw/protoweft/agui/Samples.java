package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.*;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One instance of every event type and message role, with optional fields filled in. */
final class Samples {

    private Samples() {
    }

    static final Envelope FULL = new Envelope(1_700_000_000_000L, Map.of("provider", "x"), Map.of("k", "v"), null);
    static final Envelope SUB = new Envelope(null, null, null, "sub-1");

    static Map<String, Object> nullableMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kept", null);
        m.put("n", 1);
        return m;
    }

    static final List<ContentPart> PARTS = List.of(
            ContentPart.Text.of("look"),
            new ContentPart.Media("image", new ContentPart.Source.Data("aGk=", "image/png"), "p1", Map.of("a", 1)),
            new ContentPart.Media("document", new ContentPart.Source.Url("https://x.test/a.pdf", null), null, null),
            new ContentPart.Media("audio", new ContentPart.Source.File("file-1", "openai", "audio/wav"), null, null));

    static final List<AgUiMessage> MESSAGES = List.of(
            new DeveloperMessage("m0", "be brief", "dev", null, null, null),
            new SystemMessage("m1", "you are helpful", null, null, Map.of("x", 1), null),
            new UserMessage("m2", PARTS, "alice", null, null, null),
            new AssistantMessage("m3", null, List.of(ToolCall.of("c1", "lookup", "{\"q\":1}")), null, "enc", null, "sub-1"),
            new ToolMessage("m4", "42", "c1", "boom", null, null, null),
            new ActivityMessage("m5", "search", Map.of("progress", new java.math.BigDecimal("0.5")), null, null),
            new ReasoningMessage("m6", "thinking", "enc", null, null));

    static final RunAgentInput INPUT = new RunAgentInput("t1", "r1", "1.0", "r0", nullableMap(), MESSAGES,
            List.of(new RunAgentInput.Tool("confirm", "Ask the user", Map.of("type", "object"), null)),
            List.of(new RunAgentInput.Context("page", "/home")), Map.of("p", true),
            List.of(new RunAgentInput.ResumeEntry("i1", "resolved", Map.of("ok", true), null)));

    static List<AgUiEvent> events() {
        Interrupt interrupt = new Interrupt("i1", "approval", "OK?", "c1", Map.of("type", "object"), "2030-01-01T00:00:00Z",
                Map.of("m", 1), "sub-1");
        TokenUsage usage = new TokenUsage("openai", "gpt", 10L, 5L, 15L, 2L, 3L, null);
        return List.of(
                new RunStarted("t1", "r1", "1.0", "r0", INPUT, FULL),
                new RunFinished("t1", "r1", Map.of("answer", 42), new RunOutcome.Success(List.of("c1")), List.of(usage),
                        Envelope.NONE),
                new RunFinished("t1", "r1", null, new RunOutcome.Interrupted(List.of(interrupt)), null, Envelope.NONE),
                new RunFinished("t1", "r1", null, new RunOutcome.Cancelled(), null, Envelope.NONE),
                new RunError("boom", "E1", List.of(usage), Envelope.NONE),
                new StepStarted("plan", SUB),
                new StepFinished("plan", Envelope.NONE),
                new TextMessageStart("m1", "assistant", "bot", FULL),
                new TextMessageContent("m1", "Hel", SUB),
                new TextMessageEnd("m1", Envelope.NONE),
                new TextMessageChunk("m2", "user", "hi", null, Envelope.NONE),
                new TextMessageChunk(null, null, "more", null, Envelope.NONE),
                new ToolCallStart("c1", "lookup", "m1", Envelope.NONE),
                new ToolCallArgs("c1", "{\"q\"", Envelope.NONE),
                new ToolCallEnd("c1", Envelope.NONE),
                new ToolCallChunk("c2", "lookup", null, "{}", Envelope.NONE),
                new ToolCallResult("m9", "c1", "42", Envelope.NONE),
                new ToolCallResult("m10", "c1", PARTS, Envelope.NONE),
                new ReasoningStart("r1", Envelope.NONE),
                new ReasoningMessageStart("rm1", Envelope.NONE),
                new ReasoningMessageContent("rm1", "hmm", Envelope.NONE),
                new ReasoningMessageEnd("rm1", Envelope.NONE),
                new ReasoningMessageChunk("rm2", "x", Envelope.NONE),
                new ReasoningEnd("r1", Envelope.NONE),
                new ReasoningEncryptedValue("tool-call", "c1", "opaque", Envelope.NONE),
                new StateSnapshot(nullableMap(), Envelope.NONE),
                new StateSnapshot(null, Envelope.NONE),
                new StateDelta(List.of(JsonPatch.op("add", "/a", 1), JsonPatch.op("remove", "/b", null)), Envelope.NONE),
                new MessagesSnapshot(MESSAGES, Envelope.NONE),
                new ActivitySnapshot("a1", "search", Map.of("q", "x"), false, Envelope.NONE),
                new ActivityDelta("a1", "search", List.of(JsonPatch.op("replace", "/q", "y")), Envelope.NONE),
                new SubagentStarted("sub-1", "researcher", "finds things", null, "c1", "m1", Envelope.NONE),
                new SubagentFinished("sub-1", "done", new SubagentOutcome.Suspended(List.of("i1")), Envelope.NONE),
                new SubagentFinished("sub-1", null, null, Envelope.NONE),
                new SubagentError("sub-1", "failed", null, Envelope.NONE),
                new Raw(Map.of("vendor", List.of(1, 2)), "openai", Envelope.NONE),
                new Custom("acme.ping", null, Envelope.NONE));
    }
}
