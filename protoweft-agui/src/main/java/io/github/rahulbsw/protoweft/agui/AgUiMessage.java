package io.github.rahulbsw.protoweft.agui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One message of an AG-UI conversation ({@code Message} in the schema). Optional fields with no
 * value are {@code null}. {@code subagentRunId} attributes a message to a subagent invocation.
 */
public sealed interface AgUiMessage {

    String id();

    /** The wire role, for example {@code "assistant"}. */
    String role();

    Map<String, Object> metadata();

    String subagentRunId();

    record DeveloperMessage(String id, String content, String name, String encryptedValue, Map<String, Object> metadata,
                     String subagentRunId) implements AgUiMessage {
        public DeveloperMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
        }
        public String role() { return "developer"; }
    }

    record SystemMessage(String id, String content, String name, String encryptedValue, Map<String, Object> metadata,
                  String subagentRunId) implements AgUiMessage {
        public SystemMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
        }
        public String role() { return "system"; }
    }

    /** @param content text, or {@code null} when the message only calls tools */
    record AssistantMessage(String id, String content, List<ToolCall> toolCalls, String name, String encryptedValue,
                     Map<String, Object> metadata, String subagentRunId) implements AgUiMessage {
        public AssistantMessage {
            Objects.requireNonNull(id, "id");
            toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        }

        public static AssistantMessage text(String id, String content) {
            return new AssistantMessage(id, content, null, null, null, null, null);
        }

        public String role() { return "assistant"; }
    }

    /** @param content a {@code String} or a {@code List<ContentPart>} */
    record UserMessage(String id, Object content, String name, String encryptedValue, Map<String, Object> metadata,
                String subagentRunId) implements AgUiMessage {
        public UserMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
        }

        public static UserMessage text(String id, String content) {
            return new UserMessage(id, content, null, null, null, null);
        }

        public String role() { return "user"; }
    }

    /**
     * The answer to a tool call.
     *
     * @param content a {@code String} or a {@code List<ContentPart>}
     * @param error   set when the call failed or was declined, with what went wrong
     */
    record ToolMessage(String id, Object content, String toolCallId, String error, String encryptedValue,
                Map<String, Object> metadata, String subagentRunId) implements AgUiMessage {
        public ToolMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(toolCallId, "toolCallId");
        }

        public static ToolMessage result(String id, String toolCallId, String content) {
            return new ToolMessage(id, content, toolCallId, null, null, null, null);
        }

        public String role() { return "tool"; }
    }

    /** Rendering material for the consumer; never sent back to the agent. */
    record ActivityMessage(String id, String activityType, Map<String, Object> content, Map<String, Object> metadata,
                    String subagentRunId) implements AgUiMessage {
        public ActivityMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(activityType, "activityType");
            Objects.requireNonNull(content, "content");
        }
        public String role() { return "activity"; }
    }

    record ReasoningMessage(String id, String content, String encryptedValue, Map<String, Object> metadata,
                     String subagentRunId) implements AgUiMessage {
        public ReasoningMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
        }
        public String role() { return "reasoning"; }
    }

    /** A function call on an assistant message; {@code arguments} is text, conventionally JSON. */
    record ToolCall(String id, String name, String arguments, String encryptedValue, Map<String, Object> metadata) {
        public ToolCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
        }

        public static ToolCall of(String id, String name, String arguments) {
            return new ToolCall(id, name, arguments, null, null);
        }
    }

    /** A multimodal content part of a user or tool message. */
    sealed interface ContentPart {

        String id();

        Object metadata();

        record Text(String text, String id, Object metadata) implements ContentPart {
            public Text {
                Objects.requireNonNull(text, "text");
            }

            public static Text of(String text) {
                return new Text(text, null, null);
            }
        }

        /** @param kind {@code "image"}, {@code "audio"}, {@code "video"} or {@code "document"} */
        record Media(String kind, Source source, String id, Object metadata) implements ContentPart {
            public static final List<String> KINDS = List.of("image", "audio", "video", "document");

            public Media {
                if (!KINDS.contains(kind)) {
                    throw new IllegalArgumentException("unknown media part kind: " + kind);
                }
                Objects.requireNonNull(source, "source");
            }
        }

        /** Where a media part's bytes are. */
        sealed interface Source {

            /** Inline base64 data. */
            record Data(String value, String mimeType) implements Source {
                public Data {
                    Objects.requireNonNull(value, "value");
                    Objects.requireNonNull(mimeType, "mimeType");
                }
            }

            record Url(String value, String mimeType) implements Source {
                public Url {
                    Objects.requireNonNull(value, "value");
                }
            }

            /** A model provider's file handle: opaque, never fetched or parsed. */
            record File(String value, String provider, String mimeType) implements Source {
                public File {
                    Objects.requireNonNull(value, "value");
                }
            }
        }
    }
}
