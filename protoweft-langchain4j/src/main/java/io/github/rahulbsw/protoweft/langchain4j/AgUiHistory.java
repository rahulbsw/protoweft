package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.rahulbsw.protoweft.agui.AgUi;
import io.github.rahulbsw.protoweft.agui.AgUiMessage;
import io.github.rahulbsw.protoweft.agui.RunAgentInput;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The conversation of an AG-UI run as LangChain4j messages. The only class of this module that
 * touches {@code protoweft-agui}, which is optional: {@link LangChain4jAgents} loads it only after
 * checking that module is present.
 */
final class AgUiHistory {

    private AgUiHistory() {
    }

    /**
     * The messages before the run's last user message (the AI Service adds that one itself), with
     * the run's context entries as a leading system message; empty if the task is not an AG-UI run.
     */
    static Optional<List<ChatMessage>> history(AgentTaskContext task) {
        return AgUi.run(task).map(run -> convert(run.input()));
    }

    static List<ChatMessage> convert(RunAgentInput input) {
        List<AgUiMessage> messages = input.messages();
        int last = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AgUiMessage.UserMessage) {
                last = i;
                break;
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        if (!input.context().isEmpty()) {
            out.add(SystemMessage.from("Context from the application:\n" + input.context().stream()
                    .map(c -> "- " + c.description() + ": " + c.value()).collect(Collectors.joining("\n"))));
        }
        Map<String, String> callNames = new HashMap<>();
        for (AgUiMessage m : messages.subList(0, last)) {
            if (m instanceof AgUiMessage.SystemMessage s) {
                out.add(SystemMessage.from(s.content()));
            } else if (m instanceof AgUiMessage.DeveloperMessage d) {
                out.add(SystemMessage.from(d.content()));
            } else if (m instanceof AgUiMessage.UserMessage u) {
                out.add(UserMessage.from(text(u.content())));
            } else if (m instanceof AgUiMessage.AssistantMessage a) {
                List<ToolExecutionRequest> calls = new ArrayList<>();
                if (a.toolCalls() != null) {
                    for (AgUiMessage.ToolCall c : a.toolCalls()) {
                        callNames.put(c.id(), c.name());
                        calls.add(ToolExecutionRequest.builder().id(c.id()).name(c.name()).arguments(c.arguments()).build());
                    }
                }
                String text = a.content() == null || a.content().isEmpty() ? null : a.content();
                out.add(calls.isEmpty() ? AiMessage.from(text == null ? "" : text)
                        : text == null ? AiMessage.from(calls) : AiMessage.from(text, calls));
            } else if (m instanceof AgUiMessage.ToolMessage t) {
                String result = text(t.content());
                out.add(ToolExecutionResultMessage.from(t.toolCallId(), callNames.getOrDefault(t.toolCallId(), ""),
                        result.isEmpty() && t.error() != null ? t.error() : result));
            }
            // Activity and reasoning messages are rendering material, not model input.
        }
        return out;
    }

    private static String text(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            return parts.stream().filter(AgUiMessage.ContentPart.Text.class::isInstance)
                    .map(p -> ((AgUiMessage.ContentPart.Text) p).text()).collect(Collectors.joining("\n"));
        }
        return "";
    }
}
