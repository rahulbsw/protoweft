package io.github.rahulbsw.protoweft.evals.discovery.data;

import io.github.rahulbsw.protoweft.evals.discovery.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File helpers shared by the loaders: listing, hashing and fetch manifests. */
public final class DataFiles {

    /** Name of the manifest the fetch scripts write next to the data. */
    public static final String MANIFEST = "manifest.json";

    private DataFiles() {
    }

    /** Regular files under {@code dir} (recursive) ending with {@code suffix}, sorted by path. */
    public static List<Path> list(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("data directory not found: " + dir
                    + " (run the fetch script first, see evals/tool-discovery/README.md)");
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SHA-256 over the contents of the files, in the given order. */
    public static String sha256(List<Path> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (Path f : files) {
                try (InputStream in = new DigestInputStream(Files.newInputStream(f), md)) {
                    while (in.read(buf) >= 0) {
                        // digesting
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code revision} recorded by the fetch script's manifest, or {@code "unknown"}. */
    public static String manifestRevision(Path dir) {
        Path m = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(m)) {
            return "unknown";
        }
        Map<String, Object> manifest = Json.asMap(Json.read(m));
        Object rev = manifest.get("revision");
        return rev == null ? "unknown" : rev.toString();
    }
}
