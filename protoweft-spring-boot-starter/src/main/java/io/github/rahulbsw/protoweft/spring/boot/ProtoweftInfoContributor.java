package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.ComplianceReport;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Adds {@code protoweft.connectors.<name>}: each connector's {@link ComplianceReport}. */
public final class ProtoweftInfoContributor implements InfoContributor {

    private final ProtoweftConnectors connectors;

    public ProtoweftInfoContributor(ProtoweftConnectors connectors) {
        this.connectors = connectors;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> reports = new LinkedHashMap<>();
        connectors.compliance().forEach((name, r) -> reports.put(name, toMap(r)));
        builder.withDetail("protoweft", Map.of("connectors", reports));
    }

    static Map<String, Object> toMap(ComplianceReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocol", r.protocol().value());
        m.put("sdkName", r.sdkName());
        m.put("sdkVersion", r.sdkVersion());
        m.put("wireRevisions", r.wireRevisions());
        m.put("negotiatedRevision", r.negotiatedRevision());
        m.put("supported", new TreeSet<>(r.supported()));
        m.put("unsupported", new TreeSet<>(r.unsupported()));
        return m;
    }
}
