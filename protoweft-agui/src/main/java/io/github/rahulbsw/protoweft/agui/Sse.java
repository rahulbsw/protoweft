package io.github.rahulbsw.protoweft.agui;

import java.nio.charset.StandardCharsets;

/** SSE framing for the AG-UI binding: one JSON event per {@code data:} line, LF line endings. */
final class Sse {

    private Sse() {
    }

    /**
     * One frame. Compact JSON never contains a raw line break (they are escaped inside strings), so
     * the event fits one {@code data:} line.
     */
    static byte[] frame(Object json) {
        return ("data: " + Json.write(json) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
