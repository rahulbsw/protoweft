package io.github.rahulbsw.protoweft.discovery;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.core.tool.ToolInvocation;
import io.github.rahulbsw.protoweft.core.tool.ToolOutcome;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Fixtures {

    private Fixtures() {
    }

    public static class FileTools {
        @AgentTool(description = "Read the contents of a file from disk. Large files are truncated.",
                readOnly = true, tags = {"files"})
        public String readFile(@ToolParam(description = "Path of the file") String path) {
            return "contents of " + path;
        }

        @AgentTool(description = "Delete a file from disk.", destructive = true, tags = {"files"})
        public String deleteFile(String path) {
            return "deleted " + path;
        }

        @AgentTool(description = "List the entries of a directory.", readOnly = true, tags = {"files"})
        public List<String> listDirectory(String path) {
            return List.of("a.txt", "b.txt");
        }
    }

    public static class WeatherTools {
        @AgentTool(description = "Current weather forecast for a city.", readOnly = true, tags = {"weather"})
        public String forecast(String city) {
            return "sunny in " + city;
        }
    }

    static LocalTools files() {
        return LocalTools.builder().scan(new FileTools()).build();
    }

    static LocalTools weather() {
        return LocalTools.builder().scan(new WeatherTools()).build();
    }

    /** A connector-like source with a fixed protocol report and a mutable tool list. */
    static final class FakeConnector implements ToolConnector {
        final ProtocolId protocol;
        final String negotiated;
        final List<ToolSpec> tools = new ArrayList<>();
        boolean failListing;

        FakeConnector(ProtocolId protocol, String negotiated, ToolSpec... tools) {
            this.protocol = protocol;
            this.negotiated = negotiated;
            this.tools.addAll(List.of(tools));
        }

        public ProtocolId protocol() {
            return protocol;
        }

        public void start() {
        }

        public ComplianceReport compliance() {
            return new ComplianceReport(protocol, "fake-sdk", "9.9", List.of(negotiated), negotiated, Set.of(), Set.of());
        }

        public void close() {
        }

        public List<ToolSpec> listTools() {
            if (failListing) {
                throw new IllegalStateException("peer down");
            }
            return List.copyOf(tools);
        }

        public ToolOutcome invoke(ToolInvocation invocation) {
            return ToolOutcome.text(protocol + ":" + invocation.toolName());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static ToolSpec spec(String name, String description) {
        return new ToolSpec(name, description, Map.of("type", "object",
                "properties", Map.of("q", Map.of("type", "string")), "required", List.of("q")));
    }
}
