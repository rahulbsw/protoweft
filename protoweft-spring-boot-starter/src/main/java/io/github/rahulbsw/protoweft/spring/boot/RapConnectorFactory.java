package io.github.rahulbsw.protoweft.spring.boot;

import io.github.rahulbsw.protoweft.core.auth.CredentialProvider;
import io.github.rahulbsw.protoweft.core.observe.ObservationHandler;
import io.github.rahulbsw.protoweft.core.tool.ToolConnector;
import io.github.rahulbsw.protoweft.rap.RapCallbackEndpoint;
import io.github.rahulbsw.protoweft.rap.RapCallbackListener;
import io.github.rahulbsw.protoweft.rap.RapClientConnector;
import io.github.rahulbsw.protoweft.rap.RapPromptHandler;
import io.github.rahulbsw.protoweft.rap.RapStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code protoweft.rap.servers.*} → {@link RapClientConnector}s, receiving callbacks on the mounted
 * {@link RapCallbackEndpoint} or, with {@code protoweft.rap.callback.listener.enabled}, the
 * {@link RapCallbackListener}; with the {@link RapStore} bean and an optional {@link RapPromptHandler}
 * bean. RAP connectors have no observation hook, so {@code observations} is not used.
 */
final class RapConnectorFactory implements ConnectorFactory {

    private final ObjectProvider<RapCallbackEndpoint> endpoint;
    private final ObjectProvider<RapCallbackListener> listener;
    private final ObjectProvider<RapPromptHandler> prompts;
    private final ObjectProvider<RapStore> store;

    RapConnectorFactory(ObjectProvider<RapCallbackEndpoint> endpoint, ObjectProvider<RapCallbackListener> listener,
                        ObjectProvider<RapPromptHandler> prompts, ObjectProvider<RapStore> store) {
        this.endpoint = endpoint;
        this.listener = listener;
        this.prompts = prompts;
        this.store = store;
    }

    @Override
    public Map<String, ToolConnector> create(ProtoweftProperties properties, CredentialProvider credentials,
                                             ObservationHandler observations) {
        ProtoweftProperties.Rap rap = properties.getRap();
        Map<String, ToolConnector> out = new LinkedHashMap<>();
        if (rap.getServers().isEmpty()) {
            return out;
        }
        boolean useListener = rap.getCallback().getListener().isEnabled();
        RapCallbackListener callbackListener = useListener ? listener.getIfAvailable() : null;
        RapCallbackEndpoint callbackEndpoint = useListener ? null : endpoint.getIfAvailable();
        if (callbackListener == null && callbackEndpoint == null) {
            throw new InvalidConfigurationPropertyValueException("protoweft.rap.callback.listener.enabled", useListener,
                    "RAP connectors receive results through HTTP callbacks: run a servlet web application (the "
                            + "callback endpoint is mounted on it) or set protoweft.rap.callback.listener.enabled=true");
        }
        RapPromptHandler handler = prompts.getIfAvailable();
        RapStore rapStore = store.getIfAvailable();
        rap.getServers().forEach((name, s) -> {
            RapClientConnector.Builder b = RapClientConnector.builder(s.getUrl()).credentials(credentials)
                    // A stable key across restarts that survives a server URL change.
                    .storeKey(name);
            s.getAdditionalUrls().forEach(b::addServer);
            if (callbackListener != null) {
                b.callbacks(callbackListener);
            } else {
                b.callbacks(callbackEndpoint);
            }
            if (s.getTimeout() != null) {
                b.timeout(s.getTimeout());
            }
            if (s.getRequestTimeout() != null) {
                b.requestTimeout(s.getRequestTimeout());
            }
            if (s.getThreadId() != null) {
                b.threadId(s.getThreadId());
            }
            if (handler != null) {
                b.promptHandler(handler);
            }
            if (rapStore != null) {
                b.store(rapStore);
            }
            out.put(name, b.build());
        });
        return out;
    }
}
