package io.agentweave.discovery;

import io.agentweave.core.ComplianceReport;
import io.agentweave.core.ProtocolConnector;
import io.agentweave.core.ProtocolId;
import io.agentweave.core.tool.ToolSource;
import io.agentweave.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds cards and index documents from tool specs. */
final class Cards {

    private Cards() {
    }

    static CapabilityCard card(String name, String sourceName, ToolSpec spec, ToolSource source, SourcePolicy policy,
                               int summaryLength) {
        ProtocolId protocol = policy.protocol() != null ? new ProtocolId(policy.protocol()) : CapabilityCatalog.LOCAL;
        String wire = policy.wireRevision();
        String sdk = null;
        if (source instanceof ProtocolConnector connector) {
            ComplianceReport report = connector.compliance();
            protocol = report.protocol();
            wire = report.negotiated().orElse(wire);
            sdk = report.sdkVersion();
        }
        Map<String, Object> meta = spec.metadata();
        List<String> tags = new ArrayList<>();
        if (meta.get(ToolSpec.META_TAGS) instanceof Collection<?> t) {
            t.forEach(x -> tags.add(String.valueOf(x)));
        }
        policy.tags().stream().filter(t -> !tags.contains(t)).forEach(tags::add);

        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("description", spec.description());
        hashed.put("inputSchema", spec.inputSchema());
        hashed.put("metadata", meta);
        String canonical = CanonicalJson.write(hashed);
        int tokens = CanonicalJson.write(Map.of("name", name, "description", spec.description(),
                "parameters", spec.inputSchema())).length() / 4;

        return new CapabilityCard(name, sourceName, spec.name(), protocol, wire, sdk,
                meta.get(ToolSpec.META_TITLE) instanceof String s ? s : null,
                Text.summary(spec.description(), summaryLength), tags, parameters(spec.inputSchema()),
                policy.scopesFor(spec.name()), CapabilityHint.from(meta), CanonicalJson.sha256(canonical), tokens);
    }

    static List<CapabilityCard.Parameter> parameters(Map<String, Object> schema) {
        List<CapabilityCard.Parameter> out = new ArrayList<>();
        Collection<?> required = schema.get("required") instanceof Collection<?> r ? r : List.of();
        if (schema.get("properties") instanceof Map<?, ?> props) {
            props.keySet().stream().map(String::valueOf).sorted()
                    .forEach(p -> out.add(new CapabilityCard.Parameter(p, required.contains(p))));
        }
        return out;
    }

    static CapabilityIndex.Document document(CapabilityCard card, ToolSpec spec) {
        Map<CapabilityIndex.Field, String> fields = new EnumMap<>(CapabilityIndex.Field.class);
        fields.put(CapabilityIndex.Field.NAME, card.tool());
        fields.put(CapabilityIndex.Field.SOURCE, card.source());
        if (card.title() != null) {
            fields.put(CapabilityIndex.Field.TITLE, card.title());
        }
        fields.put(CapabilityIndex.Field.SUMMARY, card.summary());
        fields.put(CapabilityIndex.Field.TAGS, String.join(" ", card.tags()));
        fields.put(CapabilityIndex.Field.PARAMETERS, parameterText(spec.inputSchema()));
        fields.put(CapabilityIndex.Field.DESCRIPTION, spec.description());
        return new CapabilityIndex.Document(card.name(), fields);
    }

    /** Parameter names plus their descriptions. */
    private static String parameterText(Map<String, Object> schema) {
        StringBuilder out = new StringBuilder();
        if (schema.get("properties") instanceof Map<?, ?> props) {
            props.forEach((k, v) -> {
                out.append(k).append(' ');
                if (v instanceof Map<?, ?> p && p.get("description") instanceof String d) {
                    out.append(d).append(' ');
                }
            });
        }
        return out.toString().strip();
    }
}
