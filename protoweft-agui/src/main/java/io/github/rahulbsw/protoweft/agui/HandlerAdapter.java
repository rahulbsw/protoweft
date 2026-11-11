package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiEvent.Interrupt;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.AssistantMessage;
import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentArtifact;
import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Serves a core {@link AgentHandler} as an {@link AgUiAgent}; the mapping is documented on {@link AgUi}. */
final class HandlerAdapter implements AgUiAgent {

    /** Message metadata key carrying the run's resume entries on a resumed request. */
    static final String RESUME_METADATA = "agui/resume";

    private static final System.Logger LOG = System.getLogger(HandlerAdapter.class.getName());

    private final AgentHandler handler;

    HandlerAdapter(AgentHandler handler) {
        this.handler = handler;
    }

    @Override
    public void run(RunAgentInput input, AgUiRun run) throws Exception {
        String thread = input.threadId();
        if (run instanceof RunEmitter emitter) {
            emitter.onDisconnect(() -> handler.onCancel(thread));
        }
        handler.handle(request(input, run), new TaskContext(run, thread));
    }

    static AgentRequest request(RunAgentInput input, AgUiRun run) {
        List<AgUiMessage> messages = input.messages();
        int last = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                last = i;
                break;
            }
        }
        List<AgentMessage> history = new ArrayList<>();
        int end = !input.resume().isEmpty() || last < 0 ? messages.size() : last;
        for (int i = 0; i < end; i++) {
            neutral(messages.get(i), input.threadId()).ifPresent(history::add);
        }
        AgentMessage message;
        if (!input.resume().isEmpty()) {
            message = resumeMessage(input);
        } else if (last >= 0) {
            message = neutral(messages.get(last), input.threadId()).orElseThrow();
        } else {
            message = new AgentMessage(AgentMessage.Role.USER, List.of(), null, input.threadId(), input.threadId(), null);
        }
        return new AgentRequest(message, input.threadId(), input.threadId(), !input.resume().isEmpty(), history,
                run.caller());
    }

    private static java.util.Optional<AgentMessage> neutral(AgUiMessage m, String thread) {
        if (m instanceof UserMessage u) {
            return java.util.Optional.of(new AgentMessage(AgentMessage.Role.USER, Contents.toNeutral(u.content()),
                    u.id(), thread, thread, u.metadata()));
        }
        if (m instanceof AssistantMessage a && a.content() != null) {
            return java.util.Optional.of(new AgentMessage(AgentMessage.Role.AGENT, List.of(new Content.Text(a.content())),
                    a.id(), thread, thread, a.metadata()));
        }
        return java.util.Optional.empty();
    }

    private static AgentMessage resumeMessage(RunAgentInput input) {
        List<Content> parts = new ArrayList<>();
        List<Object> entries = new ArrayList<>();
        for (RunAgentInput.ResumeEntry e : input.resume()) {
            if (e.payload() instanceof String s) {
                parts.add(new Content.Text(s));
            } else if (e.payload() != null) {
                parts.add(new Content.Json(e.payload()));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("interruptId", e.interruptId());
            entry.put("status", e.status());
            entry.put("payload", e.payload());
            entries.add(entry);
        }
        return new AgentMessage(AgentMessage.Role.USER, parts, null, input.threadId(), input.threadId(),
                Map.of(RESUME_METADATA, entries));
    }

    /** The neutral task view of one AG-UI run. */
    static final class TaskContext implements AgentTaskContext {

        private final AgUiRun run;
        private final String thread;
        private final Map<String, String> toolCallIds = new HashMap<>();
        private AgUiEmitter.TextMessage streaming;

        TaskContext(AgUiRun run, String thread) {
            this.run = run;
            this.thread = thread;
        }

        AgUiRun run() {
            return run;
        }

        @Override
        public String taskId() {
            return thread;
        }

        @Override
        public String contextId() {
            return thread;
        }

        @Override
        public synchronized void reply(AgentMessage message) {
            complete(text(message.parts()));
        }

        @Override
        public synchronized void working(String status) {
            if (status != null && !status.isEmpty()) {
                run.activity(run.input().runId() + "/status", "status", Map.of("text", status));
            }
        }

        @Override
        public synchronized void textDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (streaming == null) {
                streaming = run.startMessage();
            }
            streaming.delta(delta);
        }

        @Override
        public synchronized void toolCall(String callId, String toolName, String arguments) {
            endText();
            toolCallIds.put(callId, run.toolCall(toolName, arguments == null ? "" : arguments));
        }

        @Override
        public synchronized void toolResult(String callId, ToolOutcome outcome) {
            String id = toolCallIds.get(callId);
            if (id == null) {
                throw new IllegalStateException("no tool call '" + callId + "' was reported in this run");
            }
            run.toolResult(id, Contents.toAgUi(outcome.content()));
        }

        @Override
        public synchronized void artifact(AgentArtifact artifact) {
            endText();
            String text = Contents.text(artifact.parts());
            if (!text.isEmpty()) {
                run.say(text);
            }
            if (!artifact.parts().stream().allMatch(c -> c instanceof Content.Text || c instanceof Content.Json)) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("name", artifact.name());
                Object parts = Contents.toAgUi(artifact.parts());
                content.put("parts", parts instanceof String s ? s : Codec.content(parts));
                String id = artifact.artifactId() != null ? artifact.artifactId() : UUID.randomUUID().toString();
                run.activity(id, "artifact", content);
            }
        }

        @Override
        public synchronized void requireInput(String question) {
            interrupt("input_required", question);
        }

        @Override
        public synchronized void requireAuth(String reason) {
            interrupt("auth_required", reason);
        }

        @Override
        public synchronized void complete(String message) {
            endText();
            if (message != null && !message.isEmpty()) {
                run.say(message);
            }
            run.finish();
        }

        @Override
        public synchronized void fail(String reason) {
            run.fail(reason == null ? "the agent failed" : reason, null);
        }

        @Override
        public synchronized void reject(String reason) {
            run.fail(reason == null ? "the agent declined the work" : reason, "rejected");
        }

        @Override
        public boolean isCancelled() {
            return run.isCancelled();
        }

        private void interrupt(String reason, String message) {
            endText();
            run.interrupt(List.of(Interrupt.of(UUID.randomUUID().toString(), reason, message)));
        }

        private void endText() {
            if (streaming != null) {
                streaming.end();
                streaming = null;
            }
        }

        private static String text(List<Content> parts) {
            String text = Contents.text(parts);
            if (parts.stream().anyMatch(c -> !(c instanceof Content.Text) && !(c instanceof Content.Json))) {
                LOG.log(System.Logger.Level.WARNING, "AG-UI text messages carry text only; non-text parts of a "
                        + "reply or completion message were not sent");
            }
            return text;
        }
    }
}
