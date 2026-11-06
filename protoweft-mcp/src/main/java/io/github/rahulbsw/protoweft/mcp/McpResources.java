package io.github.rahulbsw.protoweft.mcp;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resources an {@link McpToolServer} publishes: fixed-URI resources, resource templates, and
 * completers for template variables. Build one, hand it to
 * {@code McpToolServer.http(tools).resources(...)} (or {@code stdio}), and call
 * {@link #notifyUpdated(String)} when a resource changes so subscribed clients hear about it.
 *
 * <pre>{@code
 * McpResources resources = McpResources.builder()
 *         .resource("docs://readme", "readme", "text/markdown", req -> Files.readString(readme))
 *         .template("weather://{city}/today", "today", "application/json",
 *                   req -> forecast(req.variable("city")))
 *         .completion("weather://{city}/today", "city", McpCompleter.of(List.of("Paris", "Pune")))
 *         .scan(new DocsResources())                       // @McpResource / @McpResourceTemplate
 *         .build();
 * }</pre>
 *
 * <p>The set is fixed once built; handlers and completers must be thread-safe.
 */
public final class McpResources {

    private static final Duration NOTIFY_TIMEOUT = Duration.ofSeconds(10);

    record Fixed(McpResourceInfo info, McpResourceHandler handler) {
    }

    record Template(McpTemplateInfo info, McpResourceHandler handler, List<String> variables) {
    }

    private final List<Fixed> fixed;
    private final List<Template> templates;
    private final Map<String, Map<String, McpCompleter>> completers;
    private final List<McpAsyncServer> servers = new CopyOnWriteArrayList<>();

    private McpResources(Builder b) {
        this.fixed = List.copyOf(b.fixed.values());
        this.templates = List.copyOf(b.templates.values());
        Map<String, Map<String, McpCompleter>> copy = new LinkedHashMap<>();
        b.completers.forEach((template, byVariable) -> copy.put(template, Map.copyOf(byVariable)));
        this.completers = Map.copyOf(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Resources from the annotated methods of {@code targets} only. */
    public static McpResources scan(Object... targets) {
        return builder().scan(targets).build();
    }

    /** The fixed-URI resources, in registration order. */
    public List<McpResourceInfo> resources() {
        return fixed.stream().map(Fixed::info).toList();
    }

    /** The resource templates, in registration order. */
    public List<McpTemplateInfo> templates() {
        return templates.stream().map(Template::info).toList();
    }

    /**
     * Tells every client subscribed to {@code uri}, on every server serving these resources,
     * that it changed ({@code notifications/resources/updated}). Does nothing if no client is
     * subscribed; delivery failures are dropped.
     */
    public void notifyUpdated(String uri) {
        Objects.requireNonNull(uri, "uri");
        for (McpAsyncServer server : servers) {
            try {
                server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(uri)).block(NOTIFY_TIMEOUT);
            } catch (RuntimeException ignored) {
                // A subscriber that went away must not fail the publisher.
            }
        }
    }

    List<Fixed> fixed() {
        return fixed;
    }

    List<Template> templateEntries() {
        return templates;
    }

    Map<String, Map<String, McpCompleter>> completers() {
        return completers;
    }

    /** Routes {@link #notifyUpdated} to {@code server} until the returned action runs. */
    Runnable bind(McpAsyncServer server) {
        servers.add(server);
        return () -> servers.remove(server);
    }

    /** Builds {@link McpResources}. */
    public static final class Builder {

        private final Map<String, Fixed> fixed = new LinkedHashMap<>();
        private final Map<String, Template> templates = new LinkedHashMap<>();
        private final Map<String, Map<String, McpCompleter>> completers = new LinkedHashMap<>();

        private Builder() {
        }

        /** Adds a resource at a fixed URI. */
        public Builder resource(McpResourceInfo info, McpResourceHandler handler) {
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(handler, "handler");
            if (new DefaultMcpUriTemplateManager(info.uri()).isUriTemplate(info.uri())) {
                throw new IllegalArgumentException("resource URI contains a template variable; use template(...): "
                        + info.uri());
            }
            if (fixed.putIfAbsent(info.uri(), new Fixed(info, handler)) != null) {
                throw new IllegalArgumentException("duplicate resource URI: " + info.uri());
            }
            return this;
        }

        /** Adds a resource at a fixed URI. */
        public Builder resource(String uri, String name, String mimeType, McpResourceHandler handler) {
            return resource(McpResourceInfo.of(uri, name, mimeType), handler);
        }

        /** Adds a resource template; the handler gets the variables extracted from the URI. */
        public Builder template(McpTemplateInfo info, McpResourceHandler handler) {
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(handler, "handler");
            List<String> variables = new DefaultMcpUriTemplateManager(info.uriTemplate()).getVariableNames();
            if (variables.isEmpty()) {
                throw new IllegalArgumentException("URI template has no {variable}: " + info.uriTemplate());
            }
            if (templates.putIfAbsent(info.uriTemplate(), new Template(info, handler, variables)) != null) {
                throw new IllegalArgumentException("duplicate URI template: " + info.uriTemplate());
            }
            return this;
        }

        /** Adds a resource template; the handler gets the variables extracted from the URI. */
        public Builder template(String uriTemplate, String name, String mimeType, McpResourceHandler handler) {
            return template(McpTemplateInfo.of(uriTemplate, name, mimeType), handler);
        }

        /** Completes {@code variable} of the template {@code uriTemplate}. */
        public Builder completion(String uriTemplate, String variable, McpCompleter completer) {
            Objects.requireNonNull(uriTemplate, "uriTemplate");
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(completer, "completer");
            if (completers.computeIfAbsent(uriTemplate, k -> new LinkedHashMap<>()).putIfAbsent(variable, completer)
                    != null) {
                throw new IllegalArgumentException("duplicate completer for " + uriTemplate + " variable " + variable);
            }
            return this;
        }

        /**
         * Adds every public {@link McpResource}, {@link McpResourceTemplate} and
         * {@link McpComplete}{@code (uriTemplate = ...)} method of each object.
         *
         * @throws IllegalArgumentException if a method has an unsupported signature
         */
        public Builder scan(Object... targets) {
            for (Object target : targets) {
                AnnotationScanner.scanResources(Objects.requireNonNull(target, "target"), this);
            }
            return this;
        }

        /**
         * @throws IllegalArgumentException if a completer names an unknown template or variable
         */
        public McpResources build() {
            completers.forEach((uriTemplate, byVariable) -> {
                Template template = templates.get(uriTemplate);
                if (template == null) {
                    throw new IllegalArgumentException("completer for unknown URI template: " + uriTemplate);
                }
                for (String variable : byVariable.keySet()) {
                    if (!template.variables().contains(variable)) {
                        throw new IllegalArgumentException("URI template " + uriTemplate + " has no variable "
                                + variable);
                    }
                }
            });
            return new McpResources(this);
        }
    }
}
