package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.auth.Credential;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientDiscoveryTest {

    private FakeToolServer fake;
    private RapCallbackListener listener;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeToolServer();
        listener = RapCallbackListener.builder().start();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        fake.close();
    }

    private RapClientConnector.Builder connector() {
        return RapClientConnector.builder(fake.baseUrl()).callbacks(listener);
    }

    @Test
    void loadsToolsetWithAnnotationsAsMetadata() {
        try (RapClientConnector c = connector().build()) {
            assertThat(c.compliance().negotiated()).isEmpty();
            c.start();
            assertThat(c.listTools()).extracting(ToolSpec::name).containsExactlyInAnyOrder("echo", "watch");
            ToolSpec echo = c.listTools().stream().filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
            assertThat(echo.description()).isEqualTo("Echoes");
            assertThat(echo.inputSchema()).isEqualTo(Map.of("type", "object"));
            assertThat(echo.metadata()).containsEntry(ToolSpec.META_READ_ONLY, true);
            assertThat(echo.metadata().get(RapClientConnector.META_ANNOTATIONS))
                    .isEqualTo(Map.of("x-protoweft-readOnly", true, "x-acme", "1"));
            assertThat(c.compliance().negotiated()).contains(RapFeatures.WIRE_REVISION);
        }
    }

    @Test
    void invalidToolsetIsRejectedWhole() {
        String[] invalid = {
            "{\"endpoint\":\"http://x/invoke\",\"tools\":[{\"name\":\"a\",\"description\":\"\",\"inputSchema\":{}}]}",
            "{\"name\":\"t\",\"endpoint\":\"not a url\",\"tools\":[{\"name\":\"a\",\"description\":\"\",\"inputSchema\":{}}]}",
            "{\"name\":\"t\",\"endpoint\":\"http://x/invoke\",\"tools\":[]}",
            "{\"name\":\"t\",\"endpoint\":\"http://x/invoke\",\"tools\":[{\"name\":\"a b\",\"description\":\"\",\"inputSchema\":{}}]}",
            "{\"name\":\"t\",\"endpoint\":\"http://x/invoke\",\"tools\":[{\"name\":\"a\",\"inputSchema\":{}}]}",
            "{\"name\":\"t\",\"endpoint\":\"http://x/invoke\",\"tools\":[{\"name\":\"a\",\"description\":\"\",\"inputSchema\":true}]}",
            "{\"name\":\"t\",\"endpoint\":\"http://x/invoke\",\"tools\":[{\"name\":\"a\",\"description\":\"\",\"inputSchema\":{}},"
                    + "{\"name\":\"a\",\"description\":\"\",\"inputSchema\":{}}]}",
        };
        for (String manifest : invalid) {
            fake.manifest = manifest;
            try (RapClientConnector c = connector().build()) {
                assertThatThrownBy(c::start).isInstanceOf(ProtoweftException.class)
                        .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.PROTOCOL));
                assertThatThrownBy(c::listTools).isInstanceOf(ProtoweftException.class);
            }
        }
    }

    @Test
    void collidingToolsAcrossToolsetsAreMadeAvailableFromNeither() throws Exception {
        try (FakeToolServer other = new FakeToolServer()) {
            other.manifest = Json.write(Map.of("name", "other", "endpoint", other.baseUrl() + "/invoke", "tools", List.of(
                    Map.of("name", "echo", "description", "Other echo", "inputSchema", Map.of("type", "object")),
                    Map.of("name", "time", "description", "Time", "inputSchema", Map.of("type", "object")))));
            try (RapClientConnector c = connector().addServer(other.baseUrl()).build()) {
                c.start();
                assertThat(c.listTools()).extracting(ToolSpec::name).containsExactlyInAnyOrder("watch", "time");
                assertThat(c.rejectedTools()).containsOnlyKeys("echo");
                assertThatThrownBy(() -> c.invoke(ToolInvocation.of("echo", Map.of())))
                        .isInstanceOf(ProtoweftException.class).hasMessageContaining("more than one toolset")
                        .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.TOOL_NOT_FOUND));
                c.closeThread("t-1").get();
                assertThat(FakeToolServer.poll(fake.closes)).isEqualTo(Map.of("thread_id", "t-1"));
                assertThat(FakeToolServer.poll(other.closes)).isEqualTo(Map.of("thread_id", "t-1"));
            }
        }
    }

    @Test
    void discoveryStatusesMapToCategoriesAndCredentialsAreSent() {
        fake.discoveryStatus = 401;
        try (RapClientConnector c = connector()
                .credentials((protocol, target) -> protocol.equals(ProtocolId.RAP) && target.equals(fake.baseUrl())
                        ? List.of(new Credential.BearerToken("secret")) : List.of())
                .build()) {
            assertThatThrownBy(c::start).isInstanceOf(ProtoweftException.class)
                    .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.AUTHENTICATION));
        }
        assertThat(fake.authorizationHeaders).contains("Bearer secret");
        fake.discoveryStatus = 404;
        try (RapClientConnector c = connector().build()) {
            assertThatThrownBy(c::start).isInstanceOf(ProtoweftException.class)
                    .satisfies(e -> assertThat(((ProtoweftException) e).category()).isEqualTo(ErrorCategory.PROTOCOL));
        }
    }

    @Test
    void complianceReportIsHonest() {
        try (RapClientConnector c = connector().build()) {
            ComplianceReport r = c.compliance();
            assertThat(r.protocol()).isEqualTo(ProtocolId.RAP);
            assertThat(r.sdkName()).isEqualTo("none");
            assertThat(r.sdkVersion()).isNotBlank().doesNotContain("$");
            assertThat(r.wireRevisions()).containsExactly("infinity@e1a3e4b");
            assertThat(r.supported()).containsExactlyInAnyOrder(RapFeatures.TOOLSET_DISCOVERY, RapFeatures.TOOL_INVOCATION,
                    RapFeatures.TOOL_RESULT_TEXT, RapFeatures.TOOL_RESULT_CONTENT, RapFeatures.SUBSCRIPTION_EVENTS,
                    RapFeatures.OAUTH, RapFeatures.USER_CHOICE, RapFeatures.TOOL_CANCELLATION, RapFeatures.THREAD_CLOSURE);
            assertThat(r.unsupported()).containsExactlyInAnyOrder(RapFeatures.VIEW_UPDATE, RapFeatures.MIGRATION,
                    RapFeatures.TOOL_RESULT_DISPLAY_AS, RapFeatures.TOOLSET_DISPLAY_SCRIPT);
        }
    }

    @Test
    void buildRequiresCallbackHostingAndUseBeforeStartFails() {
        assertThatThrownBy(() -> RapClientConnector.builder(fake.baseUrl()).build())
                .isInstanceOf(ProtoweftException.class).hasMessageContaining("callback");
        assertThatThrownBy(() -> RapClientConnector.builder("ftp://x")).isInstanceOf(IllegalArgumentException.class);
        try (RapClientConnector c = connector().build()) {
            assertThatThrownBy(() -> c.invoke(ToolInvocation.of("echo", Map.of())))
                    .isInstanceOf(ProtoweftException.class).hasMessageContaining("not started");
        }
    }
}
