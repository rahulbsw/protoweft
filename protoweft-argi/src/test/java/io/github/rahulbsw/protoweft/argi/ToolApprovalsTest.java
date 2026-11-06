package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolApprovalsTest {

    private static final ToolFeedback PAY = ToolFeedback.builder().id("a").name("bank__pay")
            .arguments("{\"amount\":1}").description("Pay?").build();
    private static final ToolFeedback MAIL = ToolFeedback.builder().id("b").name("mail__send")
            .arguments("{}").description("Send?").build();
    private static final List<ToolFeedback> BOTH = List.of(PAY, MAIL);

    private static AgentMessage text(String text) {
        return AgentMessage.user(text);
    }

    private static AgentMessage json(Object value) {
        return new AgentMessage(AgentMessage.Role.USER, List.of(new Content.Json(value)), null, null, null, null);
    }

    @Test
    void textDecidesEveryUndecidedCall() {
        ToolApprovals.Parsed approved = ToolApprovals.parse(text("Yes!"), BOTH, Map.of());
        assertThat(approved.error()).isNull();
        assertThat(approved.decided().values()).extracting(ToolFeedback::getResult)
                .containsOnly(FeedbackResult.APPROVED).hasSize(2);

        ToolApprovals.Parsed rejected = ToolApprovals.parse(text("no"), BOTH, Map.of());
        assertThat(rejected.decided().get("a").getDescription()).isEqualTo(ToolApprovals.DEFAULT_REJECTION);

        assertThat(ToolApprovals.parse(text("perhaps"), BOTH, Map.of()).error()).contains("not understood");
    }

    @Test
    void jsonSelectsByIdNameOrTheOnlyUndecidedCall() {
        ToolApprovals.Parsed byName = ToolApprovals.parse(json(Map.of("decision", "approved", "name", "mail__send")),
                BOTH, Map.of());
        assertThat(byName.decided()).containsOnlyKeys("b");

        ToolApprovals.Parsed onlyOpen = ToolApprovals.parse(json(Map.of("decision", "edited",
                "arguments", Map.of("amount", 2))), BOTH, byName.decided());
        assertThat(onlyOpen.decided().get("a").getResult()).isEqualTo(FeedbackResult.EDITED);
        assertThat(onlyOpen.decided().get("a").getArguments()).isEqualTo("{\"amount\":2}");

        ToolApprovals.Parsed wrapped = ToolApprovals.parse(json(Map.of("decisions", List.of(
                Map.of("decision", "approve", "id", "a"), Map.of("decision", "reject", "id", "b", "reason", "spam")))),
                BOTH, Map.of());
        assertThat(wrapped.decided().get("b").getDescription()).isEqualTo("spam");
    }

    @Test
    void invalidJsonDecisionsKeepEarlierDecisionsAndExplain() {
        Map<String, ToolFeedback> earlier = ToolApprovals.parse(json(Map.of("decision", "approved", "id", "a")),
                BOTH, Map.of()).decided();

        assertThat(ToolApprovals.parse(json(Map.of("decision", "approved", "id", "zzz")), BOTH, earlier).error())
                .contains("No pending tool call matches 'zzz'");
        assertThat(ToolApprovals.parse(json(Map.of("decision", "maybe", "id", "b")), BOTH, earlier).error())
                .contains("Unknown decision");
        assertThat(ToolApprovals.parse(json(Map.of("decision", "edited", "id", "b")), BOTH, earlier).error())
                .contains("needs \"arguments\"");
        assertThat(ToolApprovals.parse(json(Map.of("decision", "edited", "arguments", "{}")), BOTH, Map.of()).error())
                .contains("give the \"id\"");
        assertThat(ToolApprovals.parse(json(Map.of("decision", "maybe", "id", "b")), BOTH, earlier).decided())
                .isEqualTo(earlier);
    }

    @Test
    void questionListsOnlyUndecidedCalls() {
        String all = ToolApprovals.question(BOTH, Map.of(), null);
        assertThat(all).startsWith("The agent wants to call 2 tools that need approval:")
                .contains("1. bank__pay (id a)", "Arguments: {\"amount\":1}", "Pay?", "2. mail__send (id b)", "per tool call.");

        String rest = ToolApprovals.question(BOTH, Map.of("a", PAY), "Try again.");
        assertThat(rest).startsWith("Try again.\n\nThe agent wants to call a tool that needs approval:")
                .contains("mail__send").doesNotContain("bank__pay");
    }
}
