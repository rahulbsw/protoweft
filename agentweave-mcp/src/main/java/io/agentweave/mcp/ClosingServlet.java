package io.agentweave.mcp;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;

import java.io.IOException;

/**
 * Delegates to the SDK's transport servlet and closes the MCP server when the host destroys
 * the servlet; the SDK servlet's own {@code destroy()} leaves its session sweeper running.
 */
final class ClosingServlet extends HttpServlet {

    private final HttpServlet delegate;
    private final Runnable onDestroy;

    ClosingServlet(HttpServlet delegate, Runnable onDestroy) {
        this.delegate = delegate;
        this.onDestroy = onDestroy;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        delegate.init(config);
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        delegate.service(req, res);
    }

    @Override
    public void destroy() {
        try {
            onDestroy.run();
        } finally {
            delegate.destroy();
        }
    }
}
