package io.github.rahulbsw.protoweft.a2a;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.agent.AgentEvent;
import io.github.rahulbsw.protoweft.core.agent.AgentMessage;
import io.github.rahulbsw.protoweft.core.agent.TaskState;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.VersionNotSupportedError;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aComplianceAndErrorsTest {

    @Test
    void clientReportSeparatesSdkVersionFromWireRevision() {
        try (TestServer server = new TestServer(); A2aAgentConnector client = server.client().build()) {
            ComplianceReport before = client.compliance();
            assertThat(before.protocol()).isEqualTo(ProtocolId.A2A);
            assertThat(before.sdkName()).isEqualTo("org.a2aproject.sdk:a2a-java-sdk-client");
            assertThat(before.sdkVersion()).isEqualTo("1.5.0.Final");
            assertThat(before.wireRevisions()).containsExactly("1.0");
            assertThat(before.negotiated()).isEmpty();

            client.start();
            assertThat(client.compliance().negotiated()).contains("1.0");
        }
    }

    @Test
    void clientDeclaresOnlyWhatItImplements() {
        ComplianceReport report = A2aAgentConnector.builder("http://localhost:1").build().compliance();
        assertThat(report.supported()).contains(A2aFeatures.BINDING_JSONRPC, A2aFeatures.SEND_MESSAGE,
                A2aFeatures.SEND_STREAMING_MESSAGE, A2aFeatures.GET_TASK, A2aFeatures.CANCEL_TASK,
                A2aFeatures.SUBSCRIBE_TO_TASK, A2aFeatures.DISCOVERY_EXTENDED_AGENT_CARD, A2aFeatures.MULTI_TURN);
        assertThat(report.unsupported()).contains(A2aFeatures.BINDING_GRPC, A2aFeatures.BINDING_HTTP_JSON,
                A2aFeatures.PUSH_NOTIFICATIONS, A2aFeatures.LIST_TASKS, A2aFeatures.AUTH_SECURITY_SCHEMES,
                A2aFeatures.EXTENSIONS, A2aFeatures.DISCOVERY_CARD_SIGNATURES);
        assertThatThrownBy(() -> report.require(A2aFeatures.BINDING_GRPC))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED));
    }

    @Test
    void serverReportFollowsItsConfiguration() {
        try (TestServer full = new TestServer()) {
            ComplianceReport report = full.a2a.compliance();
            assertThat(report.sdkName()).isEqualTo("org.a2aproject.sdk:a2a-java-sdk-server-common");
            assertThat(report.sdkVersion()).isEqualTo("1.5.0.Final");
            assertThat(report.wireRevisions()).containsExactly("1.0");
            assertThat(report.supported()).contains(A2aFeatures.PUSH_NOTIFICATIONS, A2aFeatures.SEND_STREAMING_MESSAGE,
                    A2aFeatures.LIST_TASKS, A2aFeatures.DISCOVERY_EXTENDED_AGENT_CARD);
            assertThat(report.unsupported()).contains(A2aFeatures.BINDING_GRPC, A2aFeatures.BINDING_HTTP_JSON);
        }
        try (TestServer minimal = new TestServer(b -> b.streaming(false).pushNotifications(false))) {
            ComplianceReport report = minimal.a2a.compliance();
            assertThat(report.unsupported()).contains(A2aFeatures.PUSH_NOTIFICATIONS,
                    A2aFeatures.SEND_STREAMING_MESSAGE, A2aFeatures.SUBSCRIBE_TO_TASK);
            try (A2aAgentConnector client = minimal.client().build()) {
                client.start();
                assertThat(client.streams()).isFalse();
                List<AgentEvent> events = new CopyOnWriteArrayList<>();
                assertThat(client.send(AgentMessage.user("report"), events::add).state()).isEqualTo(TaskState.COMPLETED);
                assertThat(events).isEmpty();
                assertThatThrownBy(() -> client.resubscribe("t", e -> { }))
                        .isInstanceOfSatisfying(ProtoweftException.class,
                                e -> assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED));
            }
        }
    }

    @Test
    void lateBoundPublicUrlAndObservations() {
        List<String> seen = new CopyOnWriteArrayList<>();
        ObservationHandler recorder = new ObservationHandler() {
            @Override
            public void onSuccess(Observation o, Duration elapsed) {
                seen.add(o.operation());
            }
        };
        A2aAgentServer[] holder = new A2aAgentServer[1];
        io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer[] jetty = new io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer[1];
        holder[0] = A2aAgentServer.builder(new TestAgent(), TestAgent.DESCRIPTOR)
                .publicUrl(() -> jetty[0].baseUrl()).path("/agents/test").observations(recorder).build();
        jetty[0] = io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer.builder().port(0).endpoints(holder[0]).start();
        try (A2aAgentConnector client = A2aAgentConnector.builder(jetty[0].baseUrl()).observations(recorder).build()) {
            client.start();
            assertThat(holder[0].interfaceUrl()).isEqualTo(jetty[0].baseUrl() + "/agents/test");
            assertThat(client.send(AgentMessage.user("hello")).text()).isEqualTo("hi there");
            assertThat(seen).contains("agent-card", "SendMessage", "agent/execute");
        } finally {
            jetty[0].close();
            holder[0].close();
        }
    }

    @Test
    void closeStopsTheSdkEventBusThread() throws InterruptedException {
        long before = busThreads();
        A2aAgentServer server = A2aAgentServer.builder(new TestAgent(), TestAgent.DESCRIPTOR)
                .publicUrl("http://localhost:1").build();
        assertThat(busThreads()).isEqualTo(before + 1);
        server.close();
        server.close();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (busThreads() > before && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(busThreads()).isEqualTo(before);
    }

    private static long busThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && "MainEventBusProcessor".equals(t.getName())).count();
    }

    @Test
    void unreachableAgentIsAConnectionError() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        try (A2aAgentConnector client = A2aAgentConnector.builder("http://localhost:" + port).build()) {
            assertThatThrownBy(client::start).isInstanceOfSatisfying(ProtoweftException.class,
                    e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONNECTION));
        }
    }

    @Test
    void notStartedIsAConfigurationError() {
        A2aAgentConnector client = A2aAgentConnector.builder("http://localhost:1").build();
        assertThatThrownBy(() -> client.send(AgentMessage.user("x"))).isInstanceOfSatisfying(ProtoweftException.class,
                e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONFIGURATION));
        assertThatThrownBy(() -> A2aAgentServer.builder(new TestAgent(), TestAgent.DESCRIPTOR).build())
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.CONFIGURATION));
    }

    @Test
    void mapsSdkFailuresToNeutralCategories() {
        assertCategory(new A2AClientException("x", new A2AClientHTTPError(401, "no", null, Map.of())),
                ErrorCategory.AUTHENTICATION, "401");
        assertCategory(new A2AClientHTTPError(403, "no", null, Map.of()), ErrorCategory.AUTHENTICATION, "403");
        assertCategory(new A2AClientException("x", new TaskNotFoundError()), ErrorCategory.INVALID_ARGUMENTS, "-32001");
        assertCategory(new A2AClientException("x", new VersionNotSupportedError(null, null, null)),
                ErrorCategory.VERSION_MISMATCH, "-32009");
        assertCategory(new A2AClientException("x", new MethodNotFoundError()), ErrorCategory.UNSUPPORTED, "-32601");
        assertCategory(new A2AClientException("x", new ConnectException("refused")), ErrorCategory.CONNECTION, null);
        assertCategory(new A2AClientException("x", new IllegalStateException("odd")), ErrorCategory.PROTOCOL, null);
        assertThat(A2aErrors.translate(new A2AClientHTTPError(404, "no", null, Map.of()), "agent-card", true).category())
                .isEqualTo(ErrorCategory.CONNECTION);
        assertThat(A2aErrors.translate(new A2AClientHTTPError(500, "no", null, Map.of()), "GetTask", false).category())
                .isEqualTo(ErrorCategory.PROTOCOL);
    }

    private static void assertCategory(Throwable sdkError, ErrorCategory category, String code) {
        ProtoweftException e = A2aErrors.translate(sdkError, "SendMessage", false);
        assertThat(e.category()).isEqualTo(category);
        assertThat(e.protocol()).contains(ProtocolId.A2A);
        if (code == null) {
            assertThat(e.protocolCode()).isEmpty();
        } else {
            assertThat(e.protocolCode()).contains(code);
        }
    }
}
