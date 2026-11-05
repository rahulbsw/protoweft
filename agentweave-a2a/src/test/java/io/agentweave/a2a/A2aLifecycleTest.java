package io.agentweave.a2a;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.agentweave.core.agent.AgentDescriptor;
import io.agentweave.core.agent.AgentEvent;
import io.agentweave.core.agent.AgentMessage;
import io.agentweave.core.agent.AgentReply;
import io.agentweave.core.agent.TaskState;
import io.agentweave.core.tool.Content;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Our A2A client against our A2A server, over real HTTP, for each lifecycle feature. */
class A2aLifecycleTest {

    static TestServer server;
    static A2aAgentConnector client;

    @BeforeAll
    static void start() {
        server = new TestServer();
        client = server.client().build();
        client.start();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    @Test
    void readsTheAgentCardIntoADescriptor() {
        AgentDescriptor d = client.descriptor();
        assertThat(d.name()).isEqualTo("Test Agent");
        assertThat(d.version()).isEqualTo("1.2.3");
        assertThat(d.skills()).extracting(AgentDescriptor.Skill::id).containsExactly("echo", "booking");
        assertThat(d.skills().get(0).examples()).containsExactly("hello");
        assertThat(client.streams()).isTrue();
    }

    @Test
    void directMessageReply() {
        AgentReply reply = client.send(AgentMessage.user("hello"));
        assertThat(reply.task()).isFalse();
        assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(reply.text()).isEqualTo("hi there");
    }

    @Test
    void callerIdentityReachesTheHandler() {
        try (A2aAgentConnector alice = server.client("alice-token").build()) {
            alice.start();
            assertThat(alice.send(AgentMessage.user("whoami")).text()).isEqualTo("alice");
        }
        assertThat(client.send(AgentMessage.user("whoami")).text()).isEqualTo("anonymous");
    }

    @Test
    void taskWithStatusAndArtifacts() {
        AgentReply reply = client.send(AgentMessage.user("report"));
        assertThat(reply.task()).isTrue();
        assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(reply.message().text()).isEqualTo("done");
        assertThat(reply.artifacts()).extracting(a -> a.name()).containsExactly("report", "data");
        assertThat(reply.artifacts().get(0).parts()).containsExactly(new Content.Text("line1"));
        assertThat(reply.artifacts().get(1).parts().get(0)).isInstanceOf(Content.Json.class);
        Object data = ((Content.Json) reply.artifacts().get(1).parts().get(0)).value();
        assertThat(data).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) data).keySet()).extracting(Object::toString).containsExactly("rows");
        assertThat(client.getTask(reply.taskId()).state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void streamsEventsInOrder() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentReply reply = client.send(AgentMessage.user("report"), events::add);

        assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(reply.artifacts()).hasSize(2);
        List<String> kinds = events.stream().map(A2aLifecycleTest::kind).toList();
        assertThat(kinds).containsExactly("SUBMITTED", "WORKING", "artifact:report", "artifact:data", "COMPLETED");
        assertThat(events).allMatch(e -> reply.taskId().equals(e.taskId()));
        AgentEvent.StatusUpdate working = (AgentEvent.StatusUpdate) events.get(1);
        assertThat(working.message().text()).isEqualTo("crunching");
    }

    @Test
    void streamingDirectMessageArrivesAsMessageEvent() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentReply reply = client.send(AgentMessage.user("hello"), events::add);
        assertThat(reply.text()).isEqualTo("hi there");
        assertThat(events).singleElement().isInstanceOf(AgentEvent.MessageReceived.class);
    }

    @Test
    void inputRequiredThenResumedOnTheSameTask() {
        AgentReply first = client.send(AgentMessage.user("book a table"));
        assertThat(first.state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(first.message().text()).isEqualTo("For how many people?");

        AgentReply second = client.send(AgentMessage.userReply(first.taskId(), first.contextId(), "4"));
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(second.message().text()).isEqualTo("Booked for 4");
        assertThat(server.agent.historyByTask.get(first.taskId())).contains("book a table", "For how many people?");
    }

    @Test
    void inputRequiredThenResumedWhileStreaming() {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentReply first = client.send(AgentMessage.user("book"), events::add);
        assertThat(first.state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(kind(events.get(events.size() - 1))).isEqualTo("INPUT_REQUIRED");

        AgentReply second = client.send(AgentMessage.userReply(first.taskId(), first.contextId(), "2"), e -> { });
        assertThat(second.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(second.text()).isEqualTo("Booked for 2");
    }

    @Test
    void authRequiredIsAnInterruptedState() {
        AgentReply reply = client.send(AgentMessage.user("login"));
        assertThat(reply.state()).isEqualTo(TaskState.AUTH_REQUIRED);
        assertThat(reply.message().text()).isEqualTo("Please sign in");
    }

    @Test
    void cancelsARunningTask() throws Exception {
        CompletableFuture<String> taskId = new CompletableFuture<>();
        CompletableFuture<AgentReply> running = CompletableFuture.supplyAsync(() -> client.send(
                AgentMessage.user("slow"), e -> {
                    if (e instanceof AgentEvent.StatusUpdate s && s.state() == TaskState.WORKING) {
                        taskId.complete(s.taskId());
                    }
                }));
        String id = taskId.get(10, TimeUnit.SECONDS);

        AgentReply canceled = client.cancel(id);

        assertThat(canceled.state()).isEqualTo(TaskState.CANCELED);
        assertThat(running.get(10, TimeUnit.SECONDS).state()).isEqualTo(TaskState.CANCELED);
        assertThat(server.agent.cancelledTasks).contains(id);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!server.agent.sawCancelFlag.contains(id) && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(server.agent.sawCancelFlag).contains(id);
        assertThat(client.getTask(id).state()).isEqualTo(TaskState.CANCELED);
    }

    @Test
    void resubscribesToARunningTask() throws Exception {
        server.agent.release = new CountDownLatch(1);
        CompletableFuture<String> taskId = new CompletableFuture<>();
        CompletableFuture<AgentReply> original = CompletableFuture.supplyAsync(() -> client.send(
                AgentMessage.user("wait"), e -> {
                    if (e instanceof AgentEvent.StatusUpdate s && s.state() == TaskState.WORKING) {
                        taskId.complete(s.taskId());
                    }
                }));
        String id = taskId.get(10, TimeUnit.SECONDS);

        List<AgentEvent> resubscribed = new CopyOnWriteArrayList<>();
        CompletableFuture<AgentReply> again = CompletableFuture.supplyAsync(() -> client.resubscribe(id, resubscribed::add));
        Thread.sleep(300);
        server.agent.release.countDown();

        AgentReply reply = again.get(10, TimeUnit.SECONDS);
        assertThat(reply.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(resubscribed).anyMatch(e -> e instanceof AgentEvent.ArtifactUpdate a && a.artifact().name().equals("late"));
        assertThat(kind(resubscribed.get(resubscribed.size() - 1))).isEqualTo("COMPLETED");
        assertThat(original.get(10, TimeUnit.SECONDS).state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void handlerExceptionFailsTheTaskAndRejectIsTerminal() {
        AgentReply failed = client.send(AgentMessage.user("boom"));
        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.message().text()).isEqualTo("kaboom");

        assertThat(client.send(AgentMessage.user("reject")).state()).isEqualTo(TaskState.REJECTED);
        assertThat(client.send(AgentMessage.user("quiet")).state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void tasksAreVisibleOnlyToTheirCreator() {
        try (A2aAgentConnector alice = server.client("alice-token").build();
             A2aAgentConnector bob = server.client("bob-token").build()) {
            alice.start();
            bob.start();
            AgentReply paused = alice.send(AgentMessage.user("book"));

            assertThatThrownBy(() -> bob.getTask(paused.taskId()))
                    .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                        assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS);
                        assertThat(e.protocolCode()).contains("-32001");
                    });
            assertThatThrownBy(() -> bob.send(AgentMessage.userReply(paused.taskId(), paused.contextId(), "1")))
                    .isInstanceOf(AgentWeaveException.class);
            assertThat(alice.getTask(paused.taskId()).state()).isEqualTo(TaskState.INPUT_REQUIRED);
        }
    }

    @Test
    void extendedCardOnlyForAuthenticatedCallers() {
        assertThatThrownBy(client::extendedDescriptor)
                .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                    assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION);
                    assertThat(e.protocolCode()).contains("401");
                });
        try (A2aAgentConnector alice = server.client("alice-token").build()) {
            alice.start();
            AgentDescriptor extended = alice.extendedDescriptor();
            assertThat(extended.name()).isEqualTo("Test Agent (extended)");
            assertThat(extended.skills()).extracting(AgentDescriptor.Skill::id).containsExactly("admin");
        }
    }

    @Test
    void rejectedCredentialsFailWithAuthentication() {
        try (A2aAgentConnector intruder = server.client("wrong").build()) {
            assertThatThrownBy(intruder::start)
                    .isInstanceOfSatisfying(AgentWeaveException.class, e -> {
                        assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION);
                        assertThat(e.protocolCode()).contains("401");
                    });
        }
    }

    @Test
    void unknownTaskIsInvalidArguments() {
        assertThatThrownBy(() -> client.getTask("no-such-task"))
                .isInstanceOfSatisfying(AgentWeaveException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    static String kind(AgentEvent e) {
        if (e instanceof AgentEvent.StatusUpdate s) {
            return s.state().name();
        }
        if (e instanceof AgentEvent.ArtifactUpdate a) {
            return "artifact:" + a.artifact().name();
        }
        return "message";
    }
}
