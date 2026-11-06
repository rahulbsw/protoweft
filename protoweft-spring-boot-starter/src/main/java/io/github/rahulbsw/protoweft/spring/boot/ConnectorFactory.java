package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;

import java.util.Map;

/** Builds (not starts) the connectors one protocol module declares in {@link ProtoweftProperties}. */
interface ConnectorFactory {

    /** Connectors by registry name, in declaration order. */
    Map<String, ToolConnector> create(ProtoweftProperties properties, CredentialProvider credentials,
                                      ObservationHandler observations);
}
