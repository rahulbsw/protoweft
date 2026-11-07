package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.server.AuthDecision;
import io.github.rahulbsw.protoweft.core.server.CallerIdentity;
import io.github.rahulbsw.protoweft.core.server.InboundAuthenticator;
import io.github.rahulbsw.protoweft.server.ServerEndpointProvider;
import io.github.rahulbsw.protoweft.server.ServletEndpoint;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Receives RAP callbacks (tool results, subscription events, prompts) on the application's own
 * Protoweft server: mount it like any endpoint and hand it to every {@link RapClientConnector}.
 *
 * <pre>{@code
 * RapCallbackEndpoint callbacks = RapCallbackEndpoint.create("https://agent.example.com");
 * ProtoweftServer.builder()
 *         .endpoints(callbacks)
 *         .authenticator(RapCallbackEndpoint.exemptCallbacks(myAuthenticator))
 *         .start();
 * RapClientConnector tools = RapClientConnector.builder("https://tools.example.com")
 *         .callbacks(callbacks).build();
 * }</pre>
 *
 * <p>Callbacks arrive at {@code <publicBaseUrl>/rap/callback/<token>}; the unguessable per-call
 * token authenticates the tool server, so the host's authenticator should let these paths through
 * (see {@link #exemptCallbacks}). One endpoint serves any number of connectors.
 */
public final class RapCallbackEndpoint implements ServerEndpointProvider {

    private final CallbackRouter router;

    private RapCallbackEndpoint(Supplier<String> publicBaseUrl) {
        this.router = new CallbackRouter(publicBaseUrl);
    }

    /** @param publicBaseUrl the URL tool servers reach this application at, for example {@code https://agent.example.com} */
    public static RapCallbackEndpoint create(String publicBaseUrl) {
        Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        return new RapCallbackEndpoint(() -> publicBaseUrl);
    }

    /** A base URL known only later, for example after binding port 0. */
    public static RapCallbackEndpoint create(Supplier<String> publicBaseUrl) {
        return new RapCallbackEndpoint(publicBaseUrl);
    }

    /**
     * Wraps the host's authenticator so RAP callback requests pass without credentials (their URL
     * token authenticates them) and every other request still goes through {@code delegate}.
     */
    public static InboundAuthenticator exemptCallbacks(InboundAuthenticator delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return request -> ProtocolId.RAP.equals(request.protocol()) && request.path().startsWith(CallbackRouter.PATH)
                ? AuthDecision.allow(CallerIdentity.ANONYMOUS)
                : delegate.authenticate(request);
    }

    @Override
    public List<ServletEndpoint> endpoints() {
        return List.of(new ServletEndpoint("rap-callback", ProtocolId.RAP, List.of(CallbackRouter.PATH + "*"),
                new CallbackServlet(router), false));
    }

    CallbackRouter router() {
        return router;
    }
}
