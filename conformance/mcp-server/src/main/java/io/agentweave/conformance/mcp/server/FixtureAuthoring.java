package io.agentweave.conformance.mcp.server;

import io.agentweave.mcp.McpCompleter;
import io.agentweave.mcp.McpPromptArgument;
import io.agentweave.mcp.McpPromptInfo;
import io.agentweave.mcp.McpPromptMessage;
import io.agentweave.mcp.McpPrompts;
import io.agentweave.mcp.McpResourceContents;
import io.agentweave.mcp.McpResourceInfo;
import io.agentweave.mcp.McpResources;
import io.agentweave.mcp.McpTemplateInfo;

import java.util.Base64;
import java.util.List;

/**
 * The resources, resource template, prompts and completion of the MCP conformance suite's server
 * scenarios ({@code resources.ts}, {@code prompts.ts}, {@code utils.ts}), with the suite's exact
 * URIs, names and texts.
 */
final class FixtureAuthoring {

    private static final String TEMPLATE = "test://template/{id}/data";

    private FixtureAuthoring() {
    }

    static McpResources resources() {
        return McpResources.builder()
                .resource(new McpResourceInfo("test://static-text", "Static Text Resource", null,
                                "A static text resource for testing", "text/plain"),
                        req -> "This is the content of the static text resource.")
                .resource(new McpResourceInfo("test://static-binary", "Static Binary Resource", null,
                                "A static binary resource for testing", "image/png"),
                        req -> Base64.getDecoder().decode(FixtureTools.RED_PIXEL_PNG))
                .resource(new McpResourceInfo("test://watched-resource", "Watched Resource", null,
                                "A resource that can be subscribed to for updates", "text/plain"),
                        req -> "This is a watched resource content.")
                .template(new McpTemplateInfo(TEMPLATE, "Template Resource", null,
                                "A resource template for testing parameter substitution", "application/json"),
                        req -> {
                            String id = req.variable("id");
                            return McpResourceContents.text(req.uri(), "application/json",
                                    "{\"id\":\"" + id + "\",\"templateTest\":true,\"data\":\"Data for ID: " + id + "\"}");
                        })
                .build();
    }

    static McpPrompts prompts() {
        return McpPrompts.builder()
                .prompt("test_simple_prompt", "A simple prompt for testing",
                        req -> "This is a simple prompt for testing.")
                .prompt(McpPromptInfo.of("test_prompt_with_arguments", "A prompt with arguments for testing",
                                McpPromptArgument.required("arg1", "First test argument"),
                                McpPromptArgument.required("arg2", "Second test argument")),
                        req -> "Prompt with arguments: arg1='" + req.argument("arg1") + "', arg2='"
                                + req.argument("arg2") + "'")
                .prompt(McpPromptInfo.of("test_prompt_with_embedded_resource",
                                "A prompt with embedded resource for testing",
                                McpPromptArgument.required("resourceUri", "URI of the resource to embed")),
                        req -> List.of(
                                McpPromptMessage.resource(McpPromptMessage.Role.USER, McpResourceContents.text(
                                        req.argument("resourceUri"), "text/plain",
                                        "Embedded resource content for testing.")),
                                McpPromptMessage.user("Please process the embedded resource above.")))
                .prompt("test_prompt_with_image", "A prompt with image content for testing", req -> List.of(
                        McpPromptMessage.data(McpPromptMessage.Role.USER, "image/png", FixtureTools.RED_PIXEL_PNG),
                        McpPromptMessage.user("Please analyze the image above.")))
                // completion-complete asks for arg1 of test_prompt_with_arguments.
                .completion("test_prompt_with_arguments", "arg1",
                        McpCompleter.of(List.of("paris", "park", "party")))
                .build();
    }
}
