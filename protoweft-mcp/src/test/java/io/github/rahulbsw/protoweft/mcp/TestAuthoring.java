package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolProgress;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resources, prompts and progress/logging tools served by the resource and prompt tests, over
 * HTTP and stdio alike. The resource URIs and prompt names are the MCP conformance suite's
 * fixtures, so these tests also prove the API can serve that suite.
 */
final class TestAuthoring {

    static final String STATIC_TEXT = "This is the content of the static text resource.";
    static final String EMBEDDED_TEXT = "Embedded resource content for testing.";

    /** The tools, resources and prompts one server publishes. */
    record Fixture(LocalTools tools, McpResources resources, McpPrompts prompts) {
    }

    private TestAuthoring() {
    }

    static Fixture create() {
        AtomicInteger version = new AtomicInteger();
        McpResources resources = McpResources.builder()
                .resource(new McpResourceInfo("test://static-text", "static-text", "Static text", "A text resource",
                        "text/plain"), req -> STATIC_TEXT)
                .resource("test://static-binary", "static-binary", "image/png",
                        req -> Base64.getDecoder().decode(TestTools.PNG))
                .resource("test://watched-resource", "watched-resource", "text/plain",
                        req -> "version " + version.get())
                .resource("test://whoami", "whoami", "text/plain", req -> req.caller().name())
                .template(new McpTemplateInfo("test://template/{id}/data", "template", null,
                        "Data for one id", "application/json"),
                        req -> Map.of("id", req.variable("id"), "templateTest", true,
                                "data", "Data for ID: " + req.variable("id")))
                .completion("test://template/{id}/data", "id", McpCompleter.of(List.of("1", "2", "10", "42")))
                .scan(new AnnotatedResources())
                .build();
        McpPrompts prompts = McpPrompts.builder()
                .prompt("test_simple_prompt", "A prompt without arguments",
                        req -> "This is a simple prompt for testing.")
                .prompt(McpPromptInfo.of("test_prompt_with_arguments", "A prompt with arguments",
                                McpPromptArgument.required("arg1", "First argument"),
                                McpPromptArgument.required("arg2", "Second argument")),
                        req -> "Prompt with arguments: arg1='" + req.argument("arg1") + "', arg2='"
                                + req.argument("arg2") + "'")
                .prompt(McpPromptInfo.of("test_prompt_with_embedded_resource", "A prompt embedding a resource",
                                McpPromptArgument.required("resourceUri", "URI of the resource to embed")),
                        req -> List.of(
                                McpPromptMessage.resource(McpPromptMessage.Role.USER, McpResourceContents.text(
                                        req.argument("resourceUri"), "text/plain", EMBEDDED_TEXT)),
                                McpPromptMessage.user("Please process the embedded resource above.")))
                .prompt("test_prompt_with_image", "A prompt with an image", req -> List.of(
                        McpPromptMessage.data(McpPromptMessage.Role.USER, "image/png", TestTools.PNG),
                        McpPromptMessage.user("Please analyze the image above.")))
                .prompt("whoami_prompt", "Names the caller", req -> new McpPromptResult("who is asking",
                        List.of(McpPromptMessage.assistant("You are " + req.caller().name()))))
                .prompt(McpPromptInfo.of("numbers", "Picks a number", McpPromptArgument.optional("n", null)),
                        req -> "You picked " + req.argument("n"))
                .completion("test_prompt_with_arguments", "arg1", McpCompleter.of(List.of("alpha", "beta", "alps")))
                .completion("numbers", "n", req -> java.util.stream.IntStream.range(0, 150).mapToObj(String::valueOf)
                        .toList())
                .scan(new AnnotatedPrompts())
                .build();
        LocalTools tools = LocalTools.builder()
                .tool("test_tool_with_progress", "Reports progress 0, 50, 100 of 100", Map.of("type", "object"),
                        (Map<String, Object> args, CallerIdentity caller, ToolProgress progress) -> {
                            for (int done = 0; done <= 100; done += 50) {
                                progress.progress(done, 100.0, "step " + done);
                            }
                            return ToolOutcome.text("progressed");
                        })
                .tool("test_tool_with_logging", "Logs at debug, info and warning", Map.of("type", "object"),
                        (Map<String, Object> args, CallerIdentity caller, ToolProgress progress) -> {
                            progress.log(ToolProgress.Level.DEBUG, "debug detail");
                            progress.log(ToolProgress.Level.INFO, "tool started");
                            progress.log(ToolProgress.Level.WARNING, "almost done");
                            return ToolOutcome.text("logged");
                        })
                .tool("touch", "Changes test://watched-resource", Map.of("type", "object"), (args, caller) -> {
                    version.incrementAndGet();
                    resources.notifyUpdated("test://watched-resource");
                    return ToolOutcome.text("touched");
                })
                .build();
        return new Fixture(tools, resources, prompts);
    }

    /** Annotation-authored resources. */
    public static final class AnnotatedResources {

        public record Profile(String user, int age) {
        }

        @McpResource(uri = "notes://today", description = "Today's note", mimeType = "text/markdown")
        public String todayNote(CallerIdentity caller) {
            return "# Note for " + caller.name();
        }

        @McpResource(uri = "notes://raw", name = "raw-bytes")
        public byte[] raw() {
            return new byte[] {1, 2, 3};
        }

        @McpResourceTemplate(uriTemplate = "users://{user}/profile/{age}", title = "User profile")
        public Profile profile(String user, int age) {
            return new Profile(user, age);
        }

        @McpComplete(uriTemplate = "users://{user}/profile/{age}", argument = "user")
        public List<String> users(String typed) {
            return List.of("ann", "anton", "bob").stream().filter(u -> u.startsWith(typed)).toList();
        }
    }

    /** Annotation-authored prompts. */
    public static final class AnnotatedPrompts {

        @McpPrompt(description = "Greets someone")
        public String greet(@io.github.rahulbsw.protoweft.core.tool.ToolParam(description = "Who to greet") String name,
                            java.util.Optional<String> punctuation) {
            return "Hello, " + name + punctuation.orElse("!");
        }

        @McpPrompt(name = "dialogue", title = "A short dialogue")
        public List<McpPromptMessage> dialogue(int turns, CallerIdentity caller) {
            return java.util.stream.IntStream.range(0, turns)
                    .mapToObj(i -> i % 2 == 0 ? McpPromptMessage.user(caller.name() + " says " + i)
                            : McpPromptMessage.assistant("reply " + i))
                    .toList();
        }

        @McpComplete(prompt = "greet", argument = "name")
        public List<String> names(McpCompletionRequest request) {
            return List.of("Ada", "Alan", "Grace").stream().filter(n -> n.startsWith(request.value())).toList();
        }
    }
}
