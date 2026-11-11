package io.github.rahulbsw.protoweft.agui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads Server-Sent Events, returning each event's {@code data} payload. Accepts the full SSE
 * grammar (LF, CR and CRLF line endings, comments, multi-line {@code data}); every field other than
 * {@code data} is ignored, as the AG-UI binding requires. A line, or an event's {@code data} lines
 * together, longer than {@code maxLineBytes} fails the stream, so a hostile peer cannot exhaust
 * memory with one long line or with many short ones.
 */
final class SseReader {

    private final InputStream in;
    private final int maxLineBytes;
    private boolean pendingCr;
    private boolean eof;
    /** UTF-8 size of the line {@link #line()} returned last. */
    private int lineBytes;

    SseReader(InputStream in, int maxLineBytes) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
    }

    /** The next event's data, or {@code null} at the end of the stream. */
    String next() throws IOException {
        StringBuilder data = null;
        long dataBytes = 0;
        while (true) {
            String line = line();
            if (line == null) {
                return data == null ? null : data.toString();   // an unterminated last event still counts
            }
            if (line.isEmpty()) {
                if (data != null) {
                    return data.toString();
                }
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;   // comment, e.g. keep-alive
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            if (!field.equals("data")) {
                continue;
            }
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            // The field name, colon and optional space before the value are ASCII: one byte each.
            dataBytes += lineBytes - (line.length() - value.length()) + (data == null ? 0 : 1);
            if (dataBytes > maxLineBytes) {
                throw new IOException("SSE event larger than " + maxLineBytes + " bytes");
            }
            if (data == null) {
                data = new StringBuilder(value);
            } else {
                data.append('\n').append(value);
            }
        }
    }

    /** One line without its terminator, or {@code null} at the end of the stream. */
    private String line() throws IOException {
        if (eof) {
            return null;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                eof = true;
                return buf.size() == 0 ? null : decode(buf);
            }
            if (pendingCr) {
                pendingCr = false;
                if (b == '\n') {
                    continue;   // the LF of a CRLF
                }
            }
            if (b == '\n') {
                return decode(buf);
            }
            if (b == '\r') {
                pendingCr = true;
                return decode(buf);
            }
            if (buf.size() >= maxLineBytes) {
                throw new IOException("SSE line longer than " + maxLineBytes + " bytes");
            }
            buf.write(b);
        }
    }

    private String decode(ByteArrayOutputStream buf) {
        lineBytes = buf.size();
        return buf.toString(StandardCharsets.UTF_8);
    }
}
