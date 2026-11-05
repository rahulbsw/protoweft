package io.agentweave.spring.boot;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A servlet whose real servlet is built on the first request after {@code ready} turns true,
 * answering 503 before. Used for an MCP server over the whole registry, whose tool list is read
 * when it is built and so must wait until the connectors have started.
 */
final class DeferredServlet extends HttpServlet {

    private final transient Supplier<HttpServlet> factory;
    private final transient BooleanSupplier ready;
    private transient volatile HttpServlet delegate;

    DeferredServlet(Supplier<HttpServlet> factory, BooleanSupplier ready) {
        this.factory = factory;
        this.ready = ready;
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        HttpServlet servlet = delegate;
        if (servlet == null) {
            if (!ready.getAsBoolean()) {
                HttpServletResponse response = (HttpServletResponse) res;
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader("Retry-After", "1");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"starting\"}");
                return;
            }
            servlet = delegate();
        }
        servlet.service(req, res);
    }

    private synchronized HttpServlet delegate() throws ServletException {
        if (delegate == null) {
            HttpServlet servlet = factory.get();
            servlet.init(getServletConfig());
            delegate = servlet;
        }
        return delegate;
    }

    @Override
    public void destroy() {
        HttpServlet servlet = delegate;
        if (servlet != null) {
            servlet.destroy();
        }
    }
}
