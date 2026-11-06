package io.github.rahulbsw.protoweft.mcp;

import io.github.rahulbsw.protoweft.core.ProtoweftException;
import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler.Observation;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncCompletionSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Turns {@link McpResources} and {@link McpPrompts} into the SDK's server specifications. */
final class ServerAuthoring {

    /** MCP caps a completion response at 100 values. */
    private static final int MAX_COMPLETIONS = 100;

    private final ObservationHandler observations;
    private final Duration requestTimeout;

    ServerAuthoring(ObservationHandler observations, Duration requestTimeout) {
        this.observations = observations;
        this.requestTimeout = requestTimeout;
    }

    List<AsyncResourceSpecification> resources(McpResources resources) {
        List<AsyncResourceSpecification> specs = new ArrayList<>();
        for (McpResources.Fixed r : resources.fixed()) {
            McpResourceInfo info = r.info();
            McpSchema.Resource resource = McpSchema.Resource.builder(info.uri(), info.name()).title(info.title())
                    .description(info.description()).mimeType(info.mimeType()).build();
            specs.add(new AsyncResourceSpecification(resource, (exchange, request) -> run("resources/read",
                    request.uri(), () -> McpSchema.ReadResourceResult.builder(contents(r.handler().read(
                            new McpResourceRequest(request.uri(), Map.of(), caller(exchange))),
                            request.uri(), info.mimeType())).build())));
        }
        return specs;
    }

    List<AsyncResourceTemplateSpecification> templates(McpResources resources) {
        List<AsyncResourceTemplateSpecification> specs = new ArrayList<>();
        for (McpResources.Template t : resources.templateEntries()) {
            McpTemplateInfo info = t.info();
            McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder(info.uriTemplate(), info.name())
                    .title(info.title()).description(info.description()).mimeType(info.mimeType()).build();
            DefaultMcpUriTemplateManager matcher = new DefaultMcpUriTemplateManager(info.uriTemplate());
            specs.add(new AsyncResourceTemplateSpecification(template, (exchange, request) -> run("resources/read",
                    request.uri(), () -> McpSchema.ReadResourceResult.builder(contents(t.handler().read(
                            new McpResourceRequest(request.uri(), decoded(matcher.extractVariableValues(request.uri())),
                                    caller(exchange))), request.uri(), info.mimeType())).build())));
        }
        return specs;
    }

    List<AsyncPromptSpecification> prompts(McpPrompts prompts) {
        List<AsyncPromptSpecification> specs = new ArrayList<>();
        for (McpPrompts.Entry entry : prompts.entries()) {
            McpPromptInfo info = entry.info();
            List<McpSchema.PromptArgument> arguments = info.arguments().stream()
                    .map(a -> McpSchema.PromptArgument.builder(a.name()).title(a.title()).description(a.description())
                            .required(a.required()).build())
                    .toList();
            McpSchema.Prompt prompt = McpSchema.Prompt.builder(info.name()).title(info.title())
                    .description(info.description()).arguments(arguments).build();
            specs.add(new AsyncPromptSpecification(prompt, (exchange, request) -> run("prompts/get", info.name(), () -> {
                Map<String, String> args = strings(request.arguments());
                for (McpPromptArgument a : info.arguments()) {
                    if (a.required() && args.get(a.name()) == null) {
                        throw new ProtoweftException(ErrorCategory.INVALID_ARGUMENTS, ProtocolId.MCP,
                                "missing required argument '" + a.name() + "' for prompt " + info.name());
                    }
                }
                return promptResult(entry.handler().render(new McpPromptRequest(info.name(), args, caller(exchange))),
                        info.description());
            })));
        }
        return specs;
    }

    List<AsyncCompletionSpecification> completions(McpResources resources, McpPrompts prompts) {
        List<AsyncCompletionSpecification> specs = new ArrayList<>();
        if (prompts != null) {
            prompts.completers().forEach((name, byArgument) -> specs.add(new AsyncCompletionSpecification(
                    new McpSchema.PromptReference(name), (exchange, request) -> complete(byArgument, exchange, request))));
        }
        if (resources != null) {
            resources.completers().forEach((uriTemplate, byVariable) -> specs.add(new AsyncCompletionSpecification(
                    new McpSchema.ResourceReference(uriTemplate),
                    (exchange, request) -> complete(byVariable, exchange, request))));
        }
        return specs;
    }

    private Mono<McpSchema.CompleteResult> complete(Map<String, McpCompleter> completers,
                                                    McpAsyncServerExchange exchange, McpSchema.CompleteRequest request) {
        McpCompleter completer = completers.get(request.argument().name());
        String what = McpContent.refName(request.ref()) + "#" + request.argument().name();
        return run("completion/complete", what, () -> {
            List<String> values = completer == null ? List.of() : completer.complete(new McpCompletionRequest(
                    request.argument().name(), request.argument().value(),
                    request.context() == null ? Map.of() : request.context().arguments(), caller(exchange)));
            List<String> all = values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
            List<String> page = all.size() > MAX_COMPLETIONS ? all.subList(0, MAX_COMPLETIONS) : all;
            return new McpSchema.CompleteResult(new McpSchema.CompleteResult.CompleteCompletion(
                    List.copyOf(page), all.size(), all.size() > MAX_COMPLETIONS));
        });
    }

    /** Runs a handler off the transport thread, observed, timed out, and with errors mapped to JSON-RPC. */
    private <T> Mono<T> run(String operation, String target, Callable<T> handler) {
        Mono<T> work = Mono.fromCallable(handler)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(requestTimeout, Mono.error(() -> new ProtoweftException(ErrorCategory.TIMEOUT, ProtocolId.MCP,
                        operation + " '" + target + "' timed out after " + requestTimeout)));
        return ToolServerCore.observed(observations, new Observation(ProtocolId.MCP, operation, target), work)
                .onErrorResume(ServerAuthoring::toProtocolError);
    }

    /** INVALID_ARGUMENTS → -32602 (as MCP asks for bad prompt arguments); other failures → -32603. */
    static <T> Mono<T> toProtocolError(Throwable error) {
        if (error instanceof McpError) {
            return Mono.error(error);
        }
        if (error instanceof ProtoweftException e) {
            String message = Objects.requireNonNullElse(e.getMessage(), e.category().name());
            int code = e.category() == ErrorCategory.INVALID_ARGUMENTS || e.category() == ErrorCategory.TOOL_NOT_FOUND
                    ? McpSchema.ErrorCodes.INVALID_PARAMS : McpSchema.ErrorCodes.INTERNAL_ERROR;
            return Mono.error(McpError.builder(code).message(message).build());
        }
        // Anything else is a bug in the handler; its message may carry internals.
        return Mono.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message("internal error").build());
    }

    private static CallerIdentity caller(McpAsyncServerExchange exchange) {
        return ToolServerCore.caller(exchange.transportContext());
    }

    /** Converts a resource handler's return value; see {@link McpResourceHandler}. */
    static List<McpSchema.ResourceContents> contents(Object result, String uri, String mimeType) {
        if (result == null) {
            return List.of();
        }
        if (result instanceof String text) {
            return List.of(McpContent.text(uri, mimeType == null ? "text/plain" : mimeType, text));
        }
        if (result instanceof byte[] bytes) {
            return List.of(McpContent.blob(uri, mimeType == null ? "application/octet-stream" : mimeType,
                    Base64.getEncoder().encodeToString(bytes)));
        }
        if (result instanceof McpResourceContents one) {
            return List.of(McpContent.toMcp(one));
        }
        if (result instanceof Collection<?> many && !many.isEmpty()
                && many.stream().allMatch(McpResourceContents.class::isInstance)) {
            return many.stream().map(c -> McpContent.toMcp((McpResourceContents) c)).toList();
        }
        return List.of(McpContent.text(uri, mimeType == null ? "application/json" : mimeType, McpContent.json(result)));
    }

    /** Converts a prompt handler's return value; see {@link McpPromptHandler}. */
    static McpSchema.GetPromptResult promptResult(Object result, String description) {
        McpPromptResult rendered;
        if (result instanceof McpPromptResult r) {
            rendered = r;
        } else if (result instanceof String text) {
            rendered = new McpPromptResult(null, List.of(McpPromptMessage.user(text)));
        } else if (result instanceof McpPromptMessage message) {
            rendered = new McpPromptResult(null, List.of(message));
        } else if (result instanceof Collection<?> many && many.stream().allMatch(McpPromptMessage.class::isInstance)) {
            rendered = new McpPromptResult(null, many.stream().map(McpPromptMessage.class::cast).toList());
        } else {
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.MCP, "a prompt handler must return a "
                    + "String, McpPromptMessage, List<McpPromptMessage> or McpPromptResult, not "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        return McpSchema.GetPromptResult.builder(rendered.messages().stream().map(McpContent::toMcp).toList())
                .description(rendered.description() != null ? rendered.description() : description).build();
    }

    private static Map<String, String> strings(Map<String, Object> arguments) {
        Map<String, String> out = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, String.valueOf(v));
                }
            });
        }
        return out;
    }

    /** Template values arrive percent-encoded, as RFC 6570 simple expansion produces them. */
    private static Map<String, String> decoded(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, percentDecode(v)));
        return out;
    }

    static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) == '%' && i + 2 < value.length() && Character.digit(value.charAt(i + 1), 16) >= 0
                    && Character.digit(value.charAt(i + 2), 16) >= 0) {
                bytes.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                int cp = value.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
