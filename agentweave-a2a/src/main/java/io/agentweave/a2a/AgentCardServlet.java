package io.agentweave.a2a;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** {@code GET /.well-known/agent-card.json}: the public agent card. */
final class AgentCardServlet extends HttpServlet {

    private final transient SdkServer sdk;

    AgentCardServlet(SdkServer sdk) {
        this.sdk = sdk;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        byte[] body = sdk.publicCardJson().getBytes(StandardCharsets.UTF_8);
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }
}
