package io.github.rahulbsw.protoweft.agui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** This module's version, filled in at build time. */
final class ModuleVersion {

    static final String VALUE = load();

    private ModuleVersion() {
    }

    private static String load() {
        Properties p = new Properties();
        try (InputStream in = ModuleVersion.class.getResourceAsStream("protoweft-agui.properties")) {
            if (in == null) {
                throw new IllegalStateException("protoweft-agui.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p.getProperty("agui.module.version");
    }
}
