package io.github.rahulbsw.protoweft.rap;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;

/** {@code POST <base>/rap/callback/<token>} for {@link RapCallbackEndpoint}. */
final class CallbackServlet extends HttpServlet {

    private final transient CallbackRouter router;

    CallbackServlet(CallbackRouter router) {
        this.router = router;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        byte[] body = readLimited(req.getInputStream());
        int status = body == null ? 413 : router.dispatch(req.getRequestURI().substring(req.getContextPath().length()),
                body);
        res.setStatus(status);
        res.setContentLength(0);
    }

    static byte[] readLimited(InputStream in) throws IOException {
        byte[] body = in.readNBytes(CallbackRouter.MAX_BODY + 1);
        return body.length > CallbackRouter.MAX_BODY ? null : body;
    }
}
