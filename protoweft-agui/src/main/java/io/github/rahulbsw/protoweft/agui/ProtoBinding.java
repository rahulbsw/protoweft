package io.github.rahulbsw.protoweft.agui;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The HTTP + Protobuf binding: 4-byte big-endian length, then one {@code ag_ui.Event} envelope.
 * protobuf-java is optional ({@code compileOnly}), so everything that touches it lives in
 * {@link ProtoCodec} and {@link ProtoWire}, reached only through here once {@link #available()}
 * said yes. This class itself loads without protobuf-java.
 */
final class ProtoBinding {

    private static final System.Logger LOG = System.getLogger(ProtoBinding.class.getName());
    private static final String GENERATED = "io.github.rahulbsw.protoweft.agui.internal.proto.Events";
    /** {@code null} when the binding works, else why it does not. */
    private static final String UNAVAILABLE = detect();

    private ProtoBinding() {
    }

    /** Whether protobuf-java is present and new enough for the generated wire classes. */
    static boolean available() {
        return UNAVAILABLE == null;
    }

    /** @throws IllegalStateException naming the missing dependency, when the binding cannot work */
    static void require(String what) {
        if (UNAVAILABLE != null) {
            throw new IllegalStateException(what + " needs the HTTP + Protobuf binding, which is unavailable: "
                    + UNAVAILABLE);
        }
    }

    /** One frame for an event in its JSON form ({@link EventCodec#encode}). */
    static byte[] frame(Map<String, Object> event) {
        byte[] payload = ProtoCodec.encode(event);
        byte[] frame = new byte[4 + payload.length];
        int n = payload.length;
        frame[0] = (byte) (n >>> 24);
        frame[1] = (byte) (n >>> 16);
        frame[2] = (byte) (n >>> 8);
        frame[3] = (byte) n;
        System.arraycopy(payload, 0, frame, 4, n);
        return frame;
    }

    /**
     * One frame's event in its JSON form, or {@code null} when it was dropped (an event type this
     * version does not know; warned).
     *
     * @throws AgUiFormatException if the frame is not a well-formed event
     */
    static Map<String, Object> decode(byte[] payload, List<String> warnings) {
        return ProtoCodec.decode(payload, warnings);
    }

    private static String detect() {
        ClassLoader loader = ProtoBinding.class.getClassLoader();
        try {
            Class.forName("com.google.protobuf.Message", false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return "com.google.protobuf:protobuf-java is not on the classpath";
        }
        try {
            // Initialising the generated class runs protobuf's gencode/runtime version check.
            Class.forName(GENERATED, true, loader);
            return null;
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            String why = "protobuf-java on the classpath cannot load the generated AG-UI classes (it must be "
                    + "4.36.2 or newer): " + e;
            LOG.log(System.Logger.Level.WARNING, "AG-UI HTTP + Protobuf binding disabled; " + why);
            return why;
        }
    }

    /** Reads frames; tolerates any chunking, since it reads exactly the bytes each frame declares. */
    static final class FrameReader {

        private final InputStream in;
        private final int maxFrameBytes;

        FrameReader(InputStream in, int maxFrameBytes) {
            this.in = in;
            this.maxFrameBytes = maxFrameBytes;
        }

        /**
         * The next frame's payload, or {@code null} at the end of the stream, including a stream that
         * ends mid-frame (a truncated run, as the spec says, not a malformed one).
         *
         * @throws IOException for a frame larger than allowed, before reading it
         */
        byte[] next() throws IOException {
            byte[] header = in.readNBytes(4);
            if (header.length < 4) {
                return null;
            }
            long length = ((header[0] & 0xffL) << 24) | ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8)
                    | (header[3] & 0xff);
            if (length > maxFrameBytes) {
                throw new IOException("protobuf frame of " + length + " bytes is larger than " + maxFrameBytes);
            }
            byte[] payload = in.readNBytes((int) length);
            return payload.length < length ? null : payload;
        }
    }
}
