package io.github.rahulbsw.protoweft.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A connector's explicit statement of what it implements.
 *
 * <p>The SDK version and the wire-protocol revisions are deliberately separate fields:
 * for some protocols (notably MCP) they are independent version axes and must never be
 * conflated.
 *
 * @param protocol            the protocol this report describes
 * @param sdkName             the underlying SDK artifact, or {@code "none"} for a connector
 *                            implemented directly from the specification
 * @param sdkVersion          the underlying SDK version
 * @param wireRevisions       protocol revisions the connector can speak, oldest first
 * @param negotiatedRevision  the revision agreed with the peer, or {@code null} before
 *                            the connector is started
 * @param supported           feature identifiers the connector implements
 * @param unsupported         feature identifiers the protocol defines but the connector
 *                            does not implement
 */
public record ComplianceReport(
        ProtocolId protocol,
        String sdkName,
        String sdkVersion,
        List<String> wireRevisions,
        String negotiatedRevision,
        Set<String> supported,
        Set<String> unsupported) {

    public ComplianceReport {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(sdkName, "sdkName");
        Objects.requireNonNull(sdkVersion, "sdkVersion");
        wireRevisions = List.copyOf(wireRevisions);
        supported = Set.copyOf(supported);
        unsupported = Set.copyOf(unsupported);
        for (String feature : supported) {
            if (unsupported.contains(feature)) {
                throw new IllegalArgumentException("feature listed as both supported and unsupported: " + feature);
            }
        }
    }

    /** The negotiated revision, empty until the connector has started. */
    public Optional<String> negotiated() {
        return Optional.ofNullable(negotiatedRevision);
    }

    public boolean supports(String feature) {
        return supported.contains(feature);
    }

    /**
     * Fails fast when a feature is not implemented.
     *
     * @throws ProtoweftException with {@link ErrorCategory#UNSUPPORTED}
     */
    public void require(String feature) {
        if (!supports(feature)) {
            throw new ProtoweftException(ErrorCategory.UNSUPPORTED, protocol,
                    "feature '" + feature + "' is not supported by the " + protocol + " connector");
        }
    }

    /** Returns a copy carrying the revision agreed with the peer. */
    public ComplianceReport withNegotiatedRevision(String revision) {
        return new ComplianceReport(protocol, sdkName, sdkVersion, wireRevisions, revision, supported, unsupported);
    }
}
