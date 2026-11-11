package io.github.rahulbsw.protoweft.agui;

/** Constants of the AG-UI protocol version this module implements. */
public final class AgUiProtocol {

    /** The protocol version this module speaks, declared on run input and {@code RUN_STARTED}. */
    public static final String VERSION = "1.0";

    /** The major line this module implements; inputs declaring another major are rejected. */
    public static final int MAJOR = 1;

    /** The SSE binding's response media type. */
    public static final String EVENT_STREAM = "text/event-stream";

    private AgUiProtocol() {
    }

    /** The declared major version, or {@code -1} if it cannot be read. */
    static int major(String declared) {
        if (declared == null) {
            return -1;
        }
        int dot = declared.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? declared : declared.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
