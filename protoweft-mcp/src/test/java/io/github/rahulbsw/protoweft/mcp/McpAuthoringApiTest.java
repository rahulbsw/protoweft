package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The authoring API and its mapping, without a transport. */
class McpAuthoringApiTest {

    @Test
    void builderRejectsInconsistentDefinitions() {
        McpResourceHandler h = req -> "x";
        assertThatThrownBy(() -> McpResources.builder().resource("a://b", "b", null, h).resource("a://b", "c", null, h))
                .hasMessageContaining("duplicate resource URI");
        assertThatThrownBy(() -> McpResources.builder().resource("a://{b}", "b", null, h))
                .hasMessageContaining("use template");
        assertThatThrownBy(() -> McpResources.builder().template("a://b", "b", null, h))
                .hasMessageContaining("no {variable}");
        assertThatThrownBy(() -> McpResources.builder().completion("a://{b}", "b", McpCompleter.of(List.of())).build())
                .hasMessageContaining("unknown URI template");
        assertThatThrownBy(() -> McpResources.builder().template("a://{b}", "b", null, h)
                .completion("a://{b}", "c", McpCompleter.of(List.of())).build())
                .hasMessageContaining("has no variable c");
        assertThatThrownBy(() -> McpPrompts.builder().prompt("p", null, req -> "x")
                .completion("p", "a", McpCompleter.of(List.of())).build())
                .hasMessageContaining("prompt p has no argument a");
        assertThatThrownBy(() -> McpPrompts.builder().prompt("p", null, req -> "x").prompt("p", null, req -> "y"))
                .hasMessageContaining("duplicate prompt name");
    }

    @Test
    void scanningListsWhatTheAnnotationsDescribe() {
        McpResources resources = McpResources.scan(new TestAuthoring.AnnotatedResources());
        assertThat(resources.resources()).extracting(McpResourceInfo::uri).containsExactly("notes://raw", "notes://today");
        assertThat(resources.templates()).extracting(McpTemplateInfo::uriTemplate)
                .containsExactly("users://{user}/profile/{age}");
        assertThat(resources.completers()).containsOnlyKeys("users://{user}/profile/{age}");

        McpPrompts prompts = McpPrompts.scan(new TestAuthoring.AnnotatedPrompts());
        assertThat(prompts.prompts()).extracting(McpPromptInfo::name).containsExactly("dialogue", "greet");
        assertThat(prompts.prompts().get(0).arguments())
                .containsExactly(new McpPromptArgument("turns", null, null, true));
    }

    public static final class BadTemplate {
        @McpResourceTemplate(uriTemplate = "a://{id}")
        public String read(String other) {
            return other;
        }
    }

    public static final class BadResource {
        @McpResource(uri = "a://b")
        public String read(String id) {
            return id;
        }
    }

    public static final class BadCompleter {
        @McpPrompt
        public String p(String a) {
            return a;
        }

        @McpComplete(prompt = "p", argument = "a")
        public String complete(String typed) {
            return typed;
        }
    }

    public static final class BadArgumentType {
        @McpPrompt
        public String p(List<String> a) {
            return "x";
        }
    }

    @Test
    void scanningRejectsUnsupportedSignatures() {
        assertThatThrownBy(() -> McpResources.scan(new BadTemplate()))
                .hasMessageContaining("parameter 'other' is not one of the template variables [id]");
        assertThatThrownBy(() -> McpResources.scan(new BadResource()))
                .hasMessageContaining("parameter 'id' is not one of the injectable types");
        assertThatThrownBy(() -> McpPrompts.scan(new BadCompleter()))
                .hasMessageContaining("must return a List<String>");
        assertThatThrownBy(() -> McpPrompts.scan(new BadArgumentType()))
                .hasMessageContaining("unsupported type");
    }

    @Test
    void scannedHandlersConvertArgumentsAndRejectBadValues() throws Exception {
        McpPrompts prompts = McpPrompts.scan(new TestAuthoring.AnnotatedPrompts());
        McpPromptHandler dialogue = prompts.entries().get(0).handler();

        assertThat(dialogue.render(new McpPromptRequest("dialogue", Map.of("turns", "1"), CallerIdentity.of("x"))))
                .isEqualTo(List.of(McpPromptMessage.user("x says 0")));
        assertThatThrownBy(() -> dialogue.render(new McpPromptRequest("dialogue", Map.of("turns", "many"), null)))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
    }

    @Test
    void resourceResultsConvertByType() {
        assertThat(ServerAuthoring.contents("hi", "a://b", null)).singleElement()
                .isInstanceOfSatisfying(McpSchema.TextResourceContents.class, t -> {
                    assertThat(t.mimeType()).isEqualTo("text/plain");
                    assertThat(t.text()).isEqualTo("hi");
                });
        assertThat(ServerAuthoring.contents(new byte[] {0}, "a://b", null)).singleElement()
                .isInstanceOfSatisfying(McpSchema.BlobResourceContents.class,
                        b -> assertThat(b.mimeType()).isEqualTo("application/octet-stream"));
        assertThat(ServerAuthoring.contents(Map.of("k", 1), "a://b", null)).singleElement()
                .isInstanceOfSatisfying(McpSchema.TextResourceContents.class, t -> {
                    assertThat(t.mimeType()).isEqualTo("application/json");
                    assertThat(t.text()).isEqualTo("{\"k\":1}");
                });
        assertThat(ServerAuthoring.contents(List.of(McpResourceContents.text("a://c", null, "c"),
                McpResourceContents.blob("a://d", "image/png", new byte[] {1})), "a://b", null))
                .extracting(McpSchema.ResourceContents::uri).containsExactly("a://c", "a://d");
        assertThat(ServerAuthoring.contents(null, "a://b", null)).isEmpty();
    }

    @Test
    void promptResultsMustBeMessages() {
        assertThatThrownBy(() -> ServerAuthoring.promptResult(42, null))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.getMessage()).contains("java.lang.Integer"));
        assertThatThrownBy(() -> McpContent.toMcp(new McpPromptMessage(McpPromptMessage.Role.USER,
                List.of(new Content.Text("a"), new Content.Text("b")))))
                .isInstanceOfSatisfying(ProtoweftException.class,
                        e -> assertThat(e.category()).isEqualTo(ErrorCategory.UNSUPPORTED));
    }

    @Test
    void embeddedResourcesRoundTripAsALinkAndABody() {
        McpPromptMessage blob = McpPromptMessage.resource(McpPromptMessage.Role.ASSISTANT,
                McpResourceContents.blob("a://b", "application/pdf", new byte[] {1, 2}));
        McpSchema.PromptMessage wire = McpContent.toMcp(blob);

        assertThat(wire.content()).isInstanceOfSatisfying(McpSchema.EmbeddedResource.class,
                e -> assertThat(e.resource()).isInstanceOf(McpSchema.BlobResourceContents.class));
        assertThat(McpContent.toNeutral(wire)).isEqualTo(blob);
    }

    @Test
    void resourceContentsHoldTextOrBlob() {
        assertThatThrownBy(() -> new McpResourceContents("a://b", null, "t", "AA==")).isInstanceOf(
                IllegalArgumentException.class);
        assertThat(McpResourceContents.blob("a://b", null, new byte[] {7}).bytes()).containsExactly(7);
        assertThat(McpResourceContents.text("a://b", null, "é").bytes()).hasSize(2);
    }

    @Test
    void templateValuesArePercentDecoded() {
        assertThat(ServerAuthoring.percentDecode("J%C3%BCrgen%20M")).isEqualTo("Jürgen M");
        assertThat(ServerAuthoring.percentDecode("100%")).isEqualTo("100%");
        assertThat(ServerAuthoring.percentDecode("a%2")).isEqualTo("a%2");
        assertThat(ServerAuthoring.percentDecode("plain")).isEqualTo("plain");
    }

    @Test
    void prefixCompleterIgnoresCase() throws Exception {
        assertThat(McpCompleter.of(List.of("Paris", "pune", "Lyon"))
                .complete(new McpCompletionRequest("city", "P", null, null))).containsExactly("Paris", "pune");
    }
}
