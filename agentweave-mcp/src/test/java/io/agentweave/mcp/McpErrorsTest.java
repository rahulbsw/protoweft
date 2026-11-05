package io.agentweave.mcp;

import io.agentweave.core.AgentWeaveException;
import io.agentweave.core.ErrorCategory;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class McpErrorsTest {

    @Test
    void unsupportedRevisionDuringInitializeIsVersionMismatch() {
        // Exactly how SDK 2.1.0's LifecycleInitializer rejects an unknown server revision.
        McpError sdkError = McpError.builder(-32602).message("Unsupported protocol version").build();

        assertThat(McpErrors.translate(sdkError, "initialize").category()).isEqualTo(ErrorCategory.VERSION_MISMATCH);
    }

    @Test
    void timeoutsAnywhereInTheCauseChainMapToTimeout() {
        RuntimeException wrapped = new RuntimeException(new TimeoutException("slow"));

        assertThat(McpErrors.translate(wrapped, "tools/call").category()).isEqualTo(ErrorCategory.TIMEOUT);
    }

    @Test
    void methodNotFoundIsUnsupported() {
        McpError sdkError = McpError.builder(-32601).message("Method not found").build();

        assertThat(McpErrors.translate(sdkError, "tools/list").category()).isEqualTo(ErrorCategory.UNSUPPORTED);
    }

    @Test
    void httpAuthorizationRejectionIsAuthenticationWithTheStatus() {
        var denied = new McpHttpClientTransportAuthorizationException("Authorization error", null, new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 403;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        });

        AgentWeaveException e = McpErrors.translate(new RuntimeException(denied), "tools/call");

        assertThat(e.category()).isEqualTo(ErrorCategory.AUTHENTICATION);
        assertThat(e.protocolCode()).contains("403");
    }

    @Test
    void transportFailureDuringInitializeIsConnection() {
        assertThat(McpErrors.translate(new IllegalStateException("pipe closed"), "initialize").category())
                .isEqualTo(ErrorCategory.CONNECTION);
    }
}
