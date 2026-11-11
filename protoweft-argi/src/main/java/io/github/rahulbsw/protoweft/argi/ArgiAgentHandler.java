package io.github.rahulbsw.protoweft.argi;

import io.github.rahulbsw.protoweft.core.agent.AgentHandler;
import io.github.rahulbsw.protoweft.core.agent.AgentRequest;
import io.github.rahulbsw.protoweft.core.agent.AgentTaskContext;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.agentic.ai.graph.NodeOutput;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.action.InterruptionMetadata;
import io.github.agentic.ai.graph.action.InterruptionMetadata.ToolFeedback;
import io.github.agentic.ai.graph.agent.Agent;
import io.github.agentic.ai.graph.agent.BaseAgent;
import io.github.agentic.ai.graph.streaming.OutputType;
import io.github.agentic.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** The {@link AgentHandler} built by {@link ArgiAgents}. */
final class ArgiAgentHandler implements AgentHandler {

    private static final long POLL_MILLIS = 100;
    /** Whether {@code protoweft-agui} is on the classpath; {@link ArgiAgUi} is loaded only if so. */
    private static final boolean AGUI = present("io.github.rahulbsw.protoweft.agui.AgUi");
    private static final Object COMPLETE = new Object();

    /** A paused run: ARGI's interruption and the decisions received so far, by tool call id. */
    private record Pending(String threadId, InterruptionMetadata interruption, Map<String, ToolFeedback> decided) {
    }

    private final Agent agent;
    private final boolean streaming;
    private final Function<AgentRequest, String> threadId;
    private final BiConsumer<AgentRequest, RunnableConfig.Builder> customizer;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    ArgiAgentHandler(ArgiAgents.Builder b) {
        this.agent = b.agent;
        this.streaming = b.streaming;
        this.threadId = b.threadId;
        this.customizer = b.customizer;
        if (agent.getAndCompileGraph().compileConfig.checkpointSaver().isEmpty()) {
            throw new IllegalArgumentException("ARGI agent '" + agent.name() + "' has no checkpoint saver; build it "
                    + "with .saver(new MemorySaver()) (or another saver) so interrupted runs can resume");
        }
    }

    @Override
    public void handle(AgentRequest request, AgentTaskContext task) {
        Pending paused = request.taskId() == null ? null : pending.remove(request.taskId());
        if (paused != null) {
            resume(request, task, paused);
            return;
        }
        if (request.resumed()) {
            task.fail("no paused ARGI run for task " + request.taskId()
                    + " (the handler may have been restarted); start a new task");
            return;
        }
        if (request.text().isBlank()) {
            task.fail("the message has no text");
            return;
        }
        String thread = thread(request);
        run(request, task, thread, request.text(), config(request, thread));
    }

    @Override
    public void onCancel(String taskId) {
        if (taskId != null) {
            pending.remove(taskId);
        }
    }

    private void resume(AgentRequest request, AgentTaskContext task, Pending paused) {
        List<ToolFeedback> calls = paused.interruption().toolFeedbacks();
        ToolApprovals.Parsed parsed = ToolApprovals.parse(ToolApprovals.fromResumeEntries(request.message()), calls,
                paused.decided());
        Pending next = new Pending(paused.threadId(), paused.interruption(), parsed.decided());
        if (parsed.error() != null || parsed.decided().size() < calls.size()) {
            pending.put(request.taskId(), next);
            askApproval(task, calls, parsed.decided(), parsed.error());
            return;
        }
        InterruptionMetadata.Builder feedback = InterruptionMetadata.builder()
                .nodeId(paused.interruption().node())
                .state(paused.interruption().state());
        for (ToolFeedback call : calls) {
            feedback.addToolFeedback(parsed.decided().get(call.getId()));
        }
        RunnableConfig.Builder config = config(request, paused.threadId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback.build());
        // As in ARGI's HumanInTheLoopTest: an empty input resumes from the checkpoint.
        run(request, task, paused.threadId(), "", config);
    }

    private RunnableConfig.Builder config(AgentRequest request, String thread) {
        return ArgiTools.withCaller(RunnableConfig.builder().threadId(thread), request.caller());
    }

    private String thread(AgentRequest request) {
        String id = threadId.apply(request);
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    private void run(AgentRequest request, AgentTaskContext task, String thread, String input,
                     RunnableConfig.Builder config) {
        customizer.accept(request, config);
        Optional<NodeOutput> output;
        StringBuilder streamed = new StringBuilder();
        try {
            output = streaming ? stream(task, input, config.build(), streamed)
                    : agent.invokeAndGetOutput(input, config.build());
        } catch (Exception e) {
            task.fail(message(e));
            return;
        }
        if (task.isCancelled()) {
            return;
        }
        if (output.isEmpty()) {
            task.fail("the ARGI agent produced no output");
            return;
        }
        if (output.get() instanceof InterruptionMetadata interruption) {
            if (interruption.toolFeedbacks().isEmpty()) {
                task.fail("the ARGI agent was interrupted at '" + interruption.node()
                        + "' without pending tool calls; only tool-approval interrupts can be resumed over A2A");
                return;
            }
            pending.put(request.taskId(), new Pending(thread, interruption, Map.of()));
            askApproval(task, interruption.toolFeedbacks(), Map.of(), null);
            return;
        }
        String answer = answer(output.get().state());
        if (!answer.strip().equals(streamed.toString().strip())) {
            // Not already streamed: the agent's output key, or a non-streaming run.
            task.artifact(ArgiAgents.ARTIFACT_NAME, List.of(new Content.Text(answer)));
        }
        task.complete();
    }

    /** Over AG-UI one interrupt per undecided call; elsewhere an input-required question. */
    private static void askApproval(AgentTaskContext task, List<ToolFeedback> calls, Map<String, ToolFeedback> decided,
                                    String problem) {
        if (!(AGUI && ArgiAgUi.interrupt(task, calls, decided, problem))) {
            task.requireInput(ToolApprovals.question(calls, decided, problem));
        }
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, ArgiAgentHandler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Runs {@code Agent.stream}, streaming model text chunks with {@code textDelta} on this thread, and
     * returns the interruption if one was emitted, else the last output.
     */
    private Optional<NodeOutput> stream(AgentTaskContext task, String input, RunnableConfig config,
                                        StringBuilder streamed) throws Exception {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        Disposable subscription = agent.stream(input, config)
                .subscribe(events::add, events::add, () -> events.add(COMPLETE));
        NodeOutput last = null;
        InterruptionMetadata interruption = null;
        try {
            while (!task.isCancelled()) {
                Object event = events.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (event instanceof InterruptionMetadata i) {
                    interruption = i;
                } else if (event instanceof NodeOutput out) {
                    last = out;
                    String chunk = chunk(out);
                    if (chunk != null) {
                        streamed.append(chunk);
                        task.textDelta(chunk);
                    } else if (callsTools(out)) {
                        // Text before a tool call is not the answer: only the final turn's text is.
                        streamed.setLength(0);
                    }
                } else if (event == COMPLETE) {
                    return Optional.ofNullable(interruption != null ? interruption : last);
                } else if (event instanceof Throwable error) {
                    throw error instanceof Exception e ? e : new ExecutionException(error);
                }
            }
            return Optional.empty();
        } finally {
            subscription.dispose();
        }
    }

    private static boolean callsTools(NodeOutput out) {
        return out instanceof StreamingOutput<?> s && s.message() instanceof AssistantMessage m && m.hasToolCalls();
    }

    private static String chunk(NodeOutput out) {
        if (out instanceof StreamingOutput<?> s && s.getOutputType() == OutputType.AGENT_MODEL_STREAMING
                && s.message() instanceof AssistantMessage m && !m.hasToolCalls()) {
            String text = m.getText();
            return text == null || text.isEmpty() ? null : text;
        }
        return null;
    }

    /** The agent's {@code outputKey} message if it has one, else the last assistant message's text. */
    private String answer(OverAllState state) {
        if (state == null) {
            return "";
        }
        if (agent instanceof BaseAgent base && base.getOutputKey() != null && !base.getOutputKey().isEmpty()) {
            Optional<Object> value = state.value(base.getOutputKey());
            if (value.isPresent()) {
                return value.get() instanceof AssistantMessage m ? text(m) : value.get().toString();
            }
        }
        Object messages = state.value("messages").orElse(List.of());
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) instanceof AssistantMessage m) {
                    return text(m);
                }
            }
        }
        return "";
    }

    private static String text(AssistantMessage message) {
        return message.getText() == null ? "" : message.getText();
    }

    private static String message(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : t.getClass().getSimpleName();
    }
}
