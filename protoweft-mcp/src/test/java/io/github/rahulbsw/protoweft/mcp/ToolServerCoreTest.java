package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outcome and error mapping of the MCP server, below the transport. */
class ToolServerCoreTest {

    @Test
    void unexpectedExceptionsBecomeInternalErrorsWithoutTheirMessage() {
        assertThatThrownBy(() -> ToolServerCore.toProtocolFailure(new IllegalStateException("db password=hunter2")).block())
                .isInstanceOfSatisfying(McpError.class, e -> {
                    assertThat(e.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
                    assertThat(e.getJsonRpcError().message()).isEqualTo("internal error");
                });
    }

    @Test
    void toolNotFoundIsInvalidParams() {
        var notFound = new ProtoweftException(ErrorCategory.TOOL_NOT_FOUND, null, "unknown tool: x");

        assertThatThrownBy(() -> ToolServerCore.toProtocolFailure(notFound).block())
                .isInstanceOfSatisfying(McpError.class,
                        e -> assertThat(e.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
    }

    @Test
    void unmappedContentIsRejected() {
        ToolOutcome outcome = new ToolOutcome(List.of(new Content.Unmapped("resource")), false, null);

        assertThatThrownBy(() -> ToolServerCore.toResult(outcome))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED));
    }

    @Test
    void unnamedLinksAreNamedByTheirUri() {
        McpSchema.CallToolResult result = ToolServerCore.toResult(
                new ToolOutcome(List.of(new Content.Link("file:///tmp/a.txt", null, null)), false, null));

        assertThat(result.content()).singleElement().isInstanceOfSatisfying(McpSchema.ResourceLink.class,
                link -> assertThat(link.name()).isEqualTo("file:///tmp/a.txt"));
    }

    @Test
    void resourcesBecomeEmbeddedResources() {
        McpSchema.CallToolResult result = ToolServerCore.toResult(new ToolOutcome(List.of(
                Content.Resource.text("test://a", "text/plain", "body"),
                Content.Resource.blob("test://b", "image/png", "AAAA")), false, null));

        assertThat(result.content()).hasSize(2).allSatisfy(c -> assertThat(c).isInstanceOf(McpSchema.EmbeddedResource.class));
        assertThat(((McpSchema.EmbeddedResource) result.content().get(0)).resource())
                .isInstanceOfSatisfying(McpSchema.TextResourceContents.class, t -> assertThat(t.text()).isEqualTo("body"));
        assertThat(((McpSchema.EmbeddedResource) result.content().get(1)).resource())
                .isInstanceOfSatisfying(McpSchema.BlobResourceContents.class, b -> assertThat(b.blob()).isEqualTo("AAAA"));
    }
}
