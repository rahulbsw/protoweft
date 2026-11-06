package io.agentweave.evals.discovery.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelClientsBaseUrlTest {

    @Test
    void reportsTheEndpointWithoutUserInfoOrQuery() {
        assertThat(ModelClients.describeUrl(null)).isEqualTo("provider default");
        assertThat(ModelClients.describeUrl("http://localhost:4000")).isEqualTo("http://localhost:4000");
        assertThat(ModelClients.describeUrl("https://user:secret@proxy.example.com/v1?key=abc"))
                .isEqualTo("https://proxy.example.com/v1");
    }
}
