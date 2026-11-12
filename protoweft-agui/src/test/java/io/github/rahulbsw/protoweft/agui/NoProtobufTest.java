package io.github.rahulbsw.protoweft.agui;

import io.github.rahulbsw.protoweft.agui.AgUiMessage.UserMessage;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Without protobuf-java (run by the {@code testWithoutProtobuf} task, which removes it from the
 * classpath): the module loads, serves and reads SSE, reports the binary binding unsupported, and a
 * client that asks for Protobuf fails fast naming the dependency.
 */
class NoProtobufTest {

    @Test
    void everythingWorksOverSseAndTheBinaryBindingSaysWhyItIsMissing() throws Exception {
        assertThat(ProtoBinding.available()).isFalse();
        assertThatThrownBy(() -> AgUiClient.builder("http://localhost/agui").preferProtobuf(true).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("protobuf-java");
        assertThat(AgUiClient.builder("http://localhost/agui").build().compliance().unsupported())
                .containsExactly(AgUiFeatures.BINDING_PROTOBUF);

        AgUiAgentServer agui = AgUiAgentServer.builder((in, run) -> run.say("hi")).build();
        try (ProtoweftServer host = ProtoweftServer.builder().port(0).endpoints(agui).start()) {
            String url = host.baseUrl() + "/agui";
            RunAgentInput input = TestClient.input("t", "r", UserMessage.text("u", "hi"));
            TestClient.Response wildcard = TestClient.post(url, input, "Accept", "*/*");
            assertThat(wildcard.headers().get("Content-Type").get(0)).startsWith(AgUiProtocol.EVENT_STREAM);
            assertThat(wildcard.events()).isNotEmpty();
            assertThat(TestClient.post(url, input, "Accept", AgUiProtocol.EVENT_PROTOBUF).status()).isEqualTo(406);
            assertThat(Json.parse(TestClient.request("GET", url + "/capabilities").body()))
                    .isEqualTo(Map.of("transport", Map.of("streaming", true, "httpBinary", false)));
            assertThat(agui.compliance().unsupported()).containsExactly(AgUiFeatures.BINDING_PROTOBUF);

            List<AgUiEvent> events = new ArrayList<>();
            assertThat(AgUiClient.builder(url).build().run(input, events::add).finished()).isNotNull();
        } finally {
            agui.close();
        }
    }
}
