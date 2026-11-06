package io.github.rahulbsw.protoweft.a2a;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** SDK coordinates generated from {@code gradle.properties} at build time, plus small shared helpers. */
final class SdkInfo {

    static final String CLIENT_NAME;
    static final String SERVER_NAME;
    static final String VERSION;

    static {
        Properties p = new Properties();
        try (InputStream in = SdkInfo.class.getResourceAsStream("protoweft-a2a.properties")) {
            if (in == null) {
                throw new IllegalStateException("protoweft-a2a.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CLIENT_NAME = p.getProperty("a2a.sdk.client.name");
        SERVER_NAME = p.getProperty("a2a.sdk.server.name");
        VERSION = p.getProperty("a2a.sdk.version");
    }

    private SdkInfo() {
    }

    static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
