package io.github.rahulbsw.protoweft.springai;

import io.github.rahulbsw.protoweft.agui.AgUi;
import io.github.rahulbsw.protoweft.agui.AgUiMessage;
import io.github.rahulbsw.protoweft.agui.AgUiRun;
import io.github.rahulbsw.protoweft.agui.RunAgentInput;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How a {@code ChatClient} agent runs when served over AG-UI. The only class of this module that
 * touches {@code protoweft-agui}, which is optional: {@link SpringAiAgents} loads this class only
 * after checking that module is present.
 *
 * <ul>
 *   <li>The prompt is the run's own conversation (AG-UI servers are stateless): system and
 *       developer messages as system messages, user text, assistant text with its tool calls, tool
 *       results as tool responses; the run's context entries as one more system message. Activity
 *       and reasoning messages are not model input.</li>
 *   <li>The application's tools are offered to the model as {@code returnDirect} callbacks: a call
 *       is sent to the application as an AG-UI tool call and left unanswered, which ends the model's
 *       turn; the application answers it on the next run.</li>
 * </ul>
 */
final class AgUiPrompts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AgUiPrompts() {
    }

    /** The AG-UI run behind {@code task}, if it is served over AG-UI. */
    static Optional<AgUiRun> run(AgentTaskContext task) {
        return AgUi.run(task);
    }

    /** Fills {@code spec} with the run's conversation and the application's tools; returns those tools' names. */
    static Set<String> apply(ChatClient.ChatClientRequestSpec spec, AgUiRun run) {
        RunAgentInput input = run.input();
        spec.messages(messages(input));
        List<ToolCallback> tools = input.tools().stream().map(t -> (ToolCallback) new ApplicationTool(t, run)).toList();
        if (!tools.isEmpty()) {
            spec.tools(tools.toArray());
        }
        return input.tools().stream().map(RunAgentInput.Tool::name).collect(Collectors.toUnmodifiableSet());
    }

    /** Whether a generation is the placeholder Spring AI returns for one of the application's tools. */
    static boolean isApplicationToolResult(Generation generation, Set<String> applicationTools) {
        return generation.getMetadata() != null && "returnDirect".equals(generation.getMetadata().getFinishReason())
                && applicationTools.contains(String.valueOf(generation.getMetadata().<Object>get("toolName")));
    }

    static List<Message> messages(RunAgentInput input) {
        List<Message> out = new ArrayList<>();
        Map<String, String> callNames = new HashMap<>();
        List<ToolResponseMessage.ToolResponse> pendingResponses = new ArrayList<>();
        for (AgUiMessage m : input.messages()) {
            if (!(m instanceof AgUiMessage.ToolMessage) && !pendingResponses.isEmpty()) {
                out.add(ToolResponseMessage.builder().responses(List.copyOf(pendingResponses)).build());
                pendingResponses.clear();
            }
            if (m instanceof AgUiMessage.SystemMessage s) {
                out.add(new SystemMessage(s.content()));
            } else if (m instanceof AgUiMessage.DeveloperMessage d) {
                out.add(new SystemMessage(d.content()));
            } else if (m instanceof AgUiMessage.UserMessage u) {
                out.add(new UserMessage(text(u.content())));
            } else if (m instanceof AgUiMessage.AssistantMessage a) {
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                if (a.toolCalls() != null) {
                    for (AgUiMessage.ToolCall c : a.toolCalls()) {
                        callNames.put(c.id(), c.name());
                        calls.add(new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()));
                    }
                }
                out.add(AssistantMessage.builder().content(a.content() == null ? "" : a.content()).toolCalls(calls).build());
            } else if (m instanceof AgUiMessage.ToolMessage t) {
                String result = text(t.content());
                if (result.isEmpty() && t.error() != null) {
                    result = t.error();
                }
                pendingResponses.add(new ToolResponseMessage.ToolResponse(t.toolCallId(),
                        callNames.getOrDefault(t.toolCallId(), ""), result));
            }
            // Activity and reasoning messages are rendering material, not model input.
        }
        if (!pendingResponses.isEmpty()) {
            out.add(ToolResponseMessage.builder().responses(List.copyOf(pendingResponses)).build());
        }
        if (!input.context().isEmpty()) {
            out.add(new SystemMessage("Context from the application:\n" + input.context().stream()
                    .map(c -> "- " + c.description() + ": " + c.value()).collect(Collectors.joining("\n"))));
        }
        return out;
    }

    /** A string as-is; the text parts of a part list, in order (media parts are not sent). */
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

    /** One of the application's tools: calling it hands the call to the application. */
    private static final class ApplicationTool implements ToolCallback {
        private final RunAgentInput.Tool tool;
        private final AgUiRun run;
        private final ToolDefinition definition;

        ApplicationTool(RunAgentInput.Tool tool, AgUiRun run) {
            this.tool = tool;
            this.run = run;
            Object schema = tool.parameters() != null ? tool.parameters()
                    : Map.of("type", "object", "properties", Map.of());
            this.definition = ToolDefinition.builder().name(tool.name()).description(tool.description())
                    .inputSchema(JSON.writeValueAsString(schema)).build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(true).build();
        }

        @Override
        public String call(String toolInput) {
            run.callClientTool(tool.name(), toolInput == null ? "" : toolInput);
            return "";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }
}
