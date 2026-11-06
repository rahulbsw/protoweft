package io.github.rahulbsw.protoweft.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.Content;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns LangChain4j {@link Tool @Tool} methods into Protoweft local tools, so the same tool
 * objects can be served over any Protoweft server protocol (MCP server, UTCP manual, …).
 *
 * <pre>{@code
 * LocalTools tools = LocalTools.builder()
 *         .addAll(LangChain4jTools.from(new WeatherTools()))   // @Tool methods
 *         .build();
 * }</pre>
 *
 * <p>Specifications come from {@link ToolSpecifications#toolSpecificationsFrom(Object)} and calls
 * run through LangChain4j's own {@link DefaultToolExecutor}, so argument binding and result
 * formatting are exactly LangChain4j's.
 */
public final class LangChain4jTools {

    /**
     * {@link InvocationParameters} key under which the calling {@link CallerIdentity} is passed;
     * a {@code @Tool} method reads it by declaring an {@code InvocationParameters} parameter.
     */
    public static final String CALLER_PARAMETER = "protoweft.caller";

    private static final Pattern LOCAL_TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private LangChain4jTools() {
    }

    /**
     * Returns one local tool per {@code @Tool} method of each object.
     *
     * @throws IllegalArgumentException if a tool name is not a valid Protoweft local tool name
     *                                  ({@code [A-Za-z0-9_-]{1,64}}; set {@code @Tool(name = ...)})
     *                                  or appears twice
     */
    public static List<LocalTools.LocalTool> from(Object... toolObjects) {
        List<LocalTools.LocalTool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object target : toolObjects) {
            Objects.requireNonNull(target, "toolObject");
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(target)) {
                String name = spec.name();
                if (!LOCAL_TOOL_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("tool name must match " + LOCAL_TOOL_NAME
                            + " (set @Tool(name = ...)): " + name);
                }
                if (!names.add(name)) {
                    throw new IllegalArgumentException("duplicate tool name: " + name);
                }
                DefaultToolExecutor executor = new DefaultToolExecutor(target,
                        ToolExecutionRequest.builder().name(name).arguments("{}").build());
                ToolSpec toolSpec = new ToolSpec(name, spec.description(), JsonSchemas.toMap(spec.parameters()));
                tools.add(new LocalTools.LocalTool(toolSpec, (args, caller) -> invoke(executor, name, args, caller)));
            }
        }
        return List.copyOf(tools);
    }

    private static ToolOutcome invoke(DefaultToolExecutor executor, String name, Map<String, Object> args,
                                      CallerIdentity caller) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .arguments(Json.write(args))
                .build();
        InvocationContext context = InvocationContext.builder()
                .invocationParameters(InvocationParameters.from(CALLER_PARAMETER, caller))
                .build();
        ToolExecutionResult result;
        try {
            result = executor.executeWithContext(request, context);
        } catch (RuntimeException e) {
            // DefaultToolExecutor reports the method's own exceptions as error results; what escapes
            // is argument binding (missing, mistyped, unparsable arguments).
            throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, null,
                    "arguments for tool '" + name + "': " + message(e), e);
        }
        return new ToolOutcome(toContent(result.resultContents()), result.isError(), null);
    }

    private static List<Content> toContent(List<dev.langchain4j.data.message.Content> contents) {
        List<Content> parts = new ArrayList<>(contents.size());
        for (dev.langchain4j.data.message.Content c : contents) {
            if (c instanceof TextContent text) {
                parts.add(new Content.Text(text.text()));
            } else if (c instanceof ImageContent imageContent) {
                Image image = imageContent.image();
                if (image.base64Data() != null) {
                    parts.add(new Content.Data(image.mimeType() != null ? image.mimeType() : "image/*", image.base64Data()));
                } else if (image.url() != null) {
                    parts.add(new Content.Link(image.url().toString(), image.mimeType(), null));
                } else {
                    parts.add(new Content.Unmapped(c.type().name()));
                }
            } else {
                parts.add(new Content.Unmapped(c.type().name()));
            }
        }
        return parts;
    }

    private static String message(Throwable e) {
        Throwable cause = e.getCause() != null && e.getMessage() == null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
