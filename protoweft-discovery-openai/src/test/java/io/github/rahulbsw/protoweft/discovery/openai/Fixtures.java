package io.github.rahulbsw.protoweft.discovery.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.discovery.CapabilityCatalog;
import io.github.rahulbsw.protoweft.discovery.DiscoveryQuery;
import io.github.rahulbsw.protoweft.discovery.SourcePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class Fixtures {

    static final CallerIdentity WRITER = new CallerIdentity("writer",
            Map.of(DiscoveryQuery.SCOPES_ATTRIBUTE, List.of("files:write")));

    private Fixtures() {
    }

    public static class FileTools {
        final AtomicInteger deletes = new AtomicInteger();

        @AgentTool(description = "Read the contents of a file from disk. Large files are truncated.",
                readOnly = true, tags = {"files"})
        public String readFile(@ToolParam(description = "Path of the file") String path) {
            return "contents of " + path;
        }

        @AgentTool(description = "Delete a file from disk.", destructive = true, tags = {"files"})
        public String deleteFile(String path) {
            deletes.incrementAndGet();
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

        @AgentTool(description = "Historical weather archive lookup for a city and date.", readOnly = true)
        public String archive(String city, String date) {
            throw new IllegalStateException("archive backend timed out");
        }
    }

    /** files (deleteFile needs scope files:write) and weather. */
    static CapabilityCatalog catalog(FileTools files) {
        return CapabilityCatalog.builder()
                .source("files", LocalTools.builder().scan(files).build(),
                        SourcePolicy.builder().toolScopes("deleteFile", "files:write").build())
                .source("weather", LocalTools.builder().scan(new WeatherTools()).build())
                .build();
    }

    static JsonNode tree(Object value) {
        return ObjectMappers.jsonMapper().valueToTree(value);
    }

    static JsonNode tree(String json) {
        try {
            return ObjectMappers.jsonMapper().readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T parse(String json, Class<T> type) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
