package io.github.rahulbsw.protoweft.oauth;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/**
 * Sends the user to the authorization server's authorization endpoint during the interactive
 * (authorization code) flow. The redirect comes back to {@link OAuthClient}'s loopback receiver.
 */
@FunctionalInterface
public interface BrowserLauncher {

    /** Opens {@code authorizationRequest} in a user agent. */
    void open(URI authorizationRequest);

    /**
     * Opens the system browser through {@link Desktop#browse} when a desktop is available, and
     * otherwise (headless servers, CI) prints the URL to standard error for the user to open.
     */
    static BrowserLauncher system() {
        return uri -> {
            try {
                if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(uri);
                    return;
                }
            } catch (Exception | LinkageError e) {
                // fall through to printing
            }
            System.err.println("Open this URL in a browser to authorize: " + uri);
        };
    }
}
