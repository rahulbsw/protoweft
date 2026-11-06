package io.github.rahulbsw.protoweft.utcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds hand-written UTCP manuals for tests. */
final class TestManuals {

    private TestManuals() {
    }

    static String manual(Map<?, ?>... tools) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("utcp_version", "1.1.0");
        m.put("manual_version", "2.0.0");
        m.put("tools", List.of(tools));
        return Json.write(m);
    }

    static Map<String, Object> tool(String name, Map<String, Object> template) {
        return tool(name, "", List.of(), template);
    }

    static Map<String, Object> tool(String name, String description, List<String> tags, Map<String, Object> template) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputs", Map.of("type", "object"));
        t.put("tags", tags);
        t.put("tool_call_template", template);
        return t;
    }

    /** An http call template; {@code extra} is key/value pairs. */
    static Map<String, Object> http(String method, String url, Object... extra) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("call_template_type", "http");
        t.put("http_method", method);
        t.put("url", url);
        for (int i = 0; i < extra.length; i += 2) {
            t.put((String) extra[i], extra[i + 1]);
        }
        return t;
    }

    static Map<String, Object> ofType(String type) {
        return Map.of("call_template_type", type);
    }

    /** A started connector over an inline manual whose http tools are allowed. */
    static UtcpClientConnector started(String manualName, String manualJson) {
        UtcpClientConnector c = UtcpClientConnector.text(manualName, manualJson)
                .allowedCommunicationProtocols("http", "text")
                .environment(k -> null)
                .build();
        c.start();
        return c;
    }
}
