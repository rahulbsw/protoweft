package io.agentweave.mcp;

import io.agentweave.core.ErrorCategory;
import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.api.Test;

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
    void transportFailureDuringInitializeIsConnection() {
        assertThat(McpErrors.translate(new IllegalStateException("pipe closed"), "initialize").category())
                .isEqualTo(ErrorCategory.CONNECTION);
    }
}
