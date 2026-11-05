package io.agentweave.discovery.openai;

import io.agentweave.core.tool.Content;
import io.agentweave.core.tool.ToolOutcome;

import java.util.ArrayList;
import java.util.List;

/** The {@code function_call_output} text for a tool outcome; failures start with {@code Error:}. */
final class OutcomeText {

    private OutcomeText() {
    }

    /** Text the model sees: text and JSON parts verbatim, links as URIs, other parts as markers. */
    static String of(ToolOutcome outcome) {
        List<String> lines = new ArrayList<>();
        for (Content part : outcome.content()) {
            if (part instanceof Content.Text t) {
                lines.add(t.text());
            } else if (part instanceof Content.Json j) {
                lines.add(SdkJson.write(j.value()));
            } else if (part instanceof Content.Link l) {
                lines.add(l.uri());
            } else if (part instanceof Content.Data d) {
                lines.add("[" + d.mimeType() + " data not forwarded]");
            } else if (part instanceof Content.Resource r) {
                lines.add(r.text() != null ? "[resource: " + r.uri() + "]\n" + r.text()
                        : "[resource: " + r.uri() + ", data not forwarded]");
            } else if (part instanceof Content.Unmapped u) {
                lines.add("[unmapped " + u.protocolType() + " content]");
            }
        }
        if (lines.isEmpty() && outcome.structured() != null) {
            lines.add(SdkJson.write(outcome.structured()));
        }
        String text = String.join("\n", lines);
        if (outcome.error()) {
            return "Error: " + (text.isBlank() ? "the capability failed without details" : text);
        }
        return text;
    }
}
