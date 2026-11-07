package io.github.rahulbsw.protoweft.rap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** This module's version, generated from the build. */
final class ModuleVersion {

    static final String VALUE = load();

    private ModuleVersion() {
    }

    private static String load() {
        Properties p = new Properties();
        try (InputStream in = ModuleVersion.class.getResourceAsStream("protoweft-rap.properties")) {
            if (in == null) {
                throw new IllegalStateException("protoweft-rap.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p.getProperty("rap.module.version");
    }
}
