package io.agentweave.conformance.a2a;

import io.agentweave.core.agent.AgentHandler;
import io.agentweave.core.agent.AgentRequest;
import io.agentweave.core.agent.AgentTaskContext;
import io.agentweave.core.tool.Content;

import java.util.List;
import java.util.Map;

/**
 * The executor behavior the A2A TCK {@code 1.0.0.alpha3} expects of a system under test, from its
 * Gherkin scenarios ({@code scenarios/core_operations.feature}, {@code scenarios/streaming.feature})
 * and {@code docs/SUT_REQUIREMENTS.md}: the incoming {@code messageId} prefix selects the behavior.
 * Written with AgentWeave's {@link AgentHandler} API only.
 */
final class TckAgent implements AgentHandler {

    /** Base64 of "tck", the file body a2a-java's SUT uses. */
    private static final String FILE_BYTES = "dGNr";

    private final long resubscribeHoldMillis;

    /** @param streamingTimeoutSeconds the TCK's {@code TCK_STREAMING_TIMEOUT}; long tasks run twice that */
    TckAgent(double streamingTimeoutSeconds) {
        this.resubscribeHoldMillis = (long) (2 * streamingTimeoutSeconds * 1000);
    }

    @Override
    public void handle(AgentRequest request, AgentTaskContext task) throws InterruptedException {
        String id = request.message().messageId() == null ? "" : request.message().messageId();

        // Longer prefixes first where one prefix extends another.
        if (id.startsWith("tck-stream-artifact-chunked")) {
            // AgentTaskContext has no artifact append/lastChunk, so the two chunks cannot be sent
            // as one chunked artifact; both parts go out in one complete artifact instead.
            task.working(null);
            task.artifact(null, List.of(new Content.Text("chunk-1 "), new Content.Text("chunk-2")));
            task.complete();
        } else if (id.startsWith("test-resubscribe-message-id")) {
            task.working(null);
            long until = System.currentTimeMillis() + resubscribeHoldMillis;
            while (System.currentTimeMillis() < until && !task.isCancelled()) {
                Thread.sleep(50);
            }
            task.complete();
        } else if (id.startsWith("tck-stream-artifact-text")) {
            streamed(task, new Content.Text("Streamed text content"));
        } else if (id.startsWith("tck-stream-artifact-file")) {
            streamed(task, new Content.Data("text/plain", FILE_BYTES, "output.txt"));
        } else if (id.startsWith("tck-stream-ordering-001")) {
            streamed(task, new Content.Text("Ordered output"));
        } else if (id.startsWith("tck-stream-001")) {
            streamed(task, new Content.Text("Stream hello from TCK"));
        } else if (id.startsWith("tck-stream-002")) {
            task.complete();
        } else if (id.startsWith("tck-stream-003")) {
            streamed(task, new Content.Text("Stream task lifecycle"));
        } else if (id.startsWith("tck-artifact-file-url")) {
            withArtifact(task, new Content.Link("https://example.com/output.txt", "text/plain", "output.txt"));
        } else if (id.startsWith("tck-artifact-file")) {
            withArtifact(task, new Content.Data("text/plain", FILE_BYTES, "output.txt"));
        } else if (id.startsWith("tck-artifact-text")) {
            withArtifact(task, new Content.Text("Generated text content"));
        } else if (id.startsWith("tck-artifact-data")) {
            withArtifact(task, new Content.Json(Map.of("key", "value", "count", 42)));
        } else if (id.startsWith("tck-message-response")) {
            task.reply("Direct message response");
        } else if (id.startsWith("tck-input-required")) {
            task.requireInput("Input required");
        } else if (id.startsWith("tck-reject-task")) {
            task.reject("rejected");
        } else if (id.startsWith("tck-complete-task")) {
            task.complete("Hello from TCK");
        } else {
            task.complete("Unhandled messageId prefix: " + id);
        }
    }

    /** working → one artifact → completed, each forwarded as it happens. */
    private static void streamed(AgentTaskContext task, Content part) {
        task.working(null);
        task.artifact(null, List.of(part));
        task.complete();
    }

    private static void withArtifact(AgentTaskContext task, Content part) {
        task.artifact(null, List.of(part));
        task.complete();
    }
}
