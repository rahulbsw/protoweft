package io.github.rahulbsw.protoweft.evals.discovery.retrieval;

import io.github.rahulbsw.protoweft.discovery.Embedder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link Embedder} that caches vectors on disk, so repeated runs over the 44k ToolRet tools do
 * not re-embed them. One append-only file per model ({@code <dir>/<model>.vec}); each record is a
 * kind byte (document or query), the SHA-256 of {@code model \0 kind \0 text}, the dimension and
 * the floats. The file is loaded into memory when the embedder is created; a truncated tail (an
 * interrupted run) is cut off. Misses are embedded in batches through the delegate and appended
 * after every batch, as one write under an exclusive file lock, so concurrent runs may share a
 * cache directory. Thread-safe.
 */
public final class CachingEmbedder implements Embedder {

    private static final int MAGIC = 0x41574543; // "AWEC"
    private static final byte DOCUMENT = 0;
    private static final byte QUERY = 1;
    private static final int BATCH = 256;

    private final Embedder delegate;
    private final Path file;
    private final Map<String, float[]> vectors = new HashMap<>();
    private long hits;
    private long misses;

    public CachingEmbedder(Embedder delegate, Path dir) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.file = dir.resolve(delegate.name().replaceAll("[^A-Za-z0-9._-]", "_") + ".vec");
        load();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public float[] embedDocument(String text) {
        return embedDocuments(List.of(text)).get(0);
    }

    @Override
    public synchronized float[] embedQuery(String text) {
        String key = key(QUERY, text);
        float[] v = vectors.get(key);
        if (v != null) {
            hits++;
            return v.clone();
        }
        misses++;
        float[] embedded = delegate.embedQuery(text);
        append(Map.of(key, embedded), QUERY);
        return embedded.clone();
    }

    @Override
    public synchronized List<float[]> embedDocuments(List<String> texts) {
        Map<String, String> missing = new LinkedHashMap<>(); // key -> text
        List<String> keys = new ArrayList<>(texts.size());
        for (String text : texts) {
            String key = key(DOCUMENT, text);
            keys.add(key);
            if (vectors.containsKey(key)) {
                hits++;
            } else if (missing.putIfAbsent(key, text) == null) {
                misses++;
            }
        }
        List<Map.Entry<String, String>> todo = new ArrayList<>(missing.entrySet());
        for (int from = 0; from < todo.size(); from += BATCH) {
            List<Map.Entry<String, String>> batch = todo.subList(from, Math.min(todo.size(), from + BATCH));
            List<float[]> embedded = delegate.embedDocuments(batch.stream().map(Map.Entry::getValue).toList());
            Map<String, float[]> fresh = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                fresh.put(batch.get(i).getKey(), embedded.get(i));
            }
            append(fresh, DOCUMENT);
            int done = from + batch.size();
            if (todo.size() >= 2000 && (done % 2048 < BATCH || done == todo.size())) {
                System.err.printf("embedding cache %s: embedded %d/%d new documents%n", name(), done, todo.size());
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        keys.forEach(k -> out.add(vectors.get(k).clone()));
        return out;
    }

    /** Cache hits (documents and queries) since creation. */
    public synchronized long hits() {
        return hits;
    }

    /** Texts that had to be embedded since creation. */
    public synchronized long misses() {
        return misses;
    }

    /** Vectors currently cached (documents and queries). */
    public synchronized int size() {
        return vectors.size();
    }

    public Path file() {
        return file;
    }

    private String key(byte kind, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(delegate.name().getBytes(StandardCharsets.UTF_8));
            md.update(new byte[] {0, kind, 0});
            md.update(text.getBytes(StandardCharsets.UTF_8));
            return kind + HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        // Under the exclusive lock, so a cut-off tail is never a record another run is appending.
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = ch.lock()) {
            long valid = read(new DataInputStream(new BufferedInputStream(Channels.newInputStream(ch), 1 << 16)));
            if (ch.size() > valid) {
                ch.truncate(valid);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read embedding cache " + file, e);
        }
    }

    /** Reads records into memory; returns the length of the valid prefix. */
    private long read(DataInputStream in) throws IOException {
        long valid = 0;
        try {
            if (in.readInt() != MAGIC) {
                throw new IllegalStateException("not an embedding cache file: " + file);
            }
            valid = 4;
            while (true) {
                byte kind = in.readByte();
                byte[] hash = new byte[32];
                in.readFully(hash);
                int dim = in.readInt();
                if (dim <= 0 || dim > 1 << 16) {
                    break;
                }
                float[] v = new float[dim];
                for (int i = 0; i < dim; i++) {
                    v[i] = in.readFloat();
                }
                vectors.put(kind + HexFormat.of().formatHex(hash), v);
                valid += 1 + 32 + 4 + 4L * dim;
            }
        } catch (EOFException e) {
            // end of file, or a truncated last record
        }
        return valid;
    }

    private void append(Map<String, float[]> fresh, byte kind) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (Map.Entry<String, float[]> e : fresh.entrySet()) {
                out.writeByte(kind);
                out.write(HexFormat.of().parseHex(e.getKey().substring(1)));
                out.writeInt(e.getValue().length);
                for (float f : e.getValue()) {
                    out.writeFloat(f);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.createDirectories(file.getParent());
            // One locked append per batch, so concurrent runs sharing the cache never interleave records.
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND); java.nio.channels.FileLock lock = ch.lock()) {
                if (ch.size() == 0) {
                    writeFully(ch, java.nio.ByteBuffer.allocate(4).putInt(MAGIC).flip());
                }
                writeFully(ch, java.nio.ByteBuffer.wrap(bytes.toByteArray()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write embedding cache " + file, e);
        }
        fresh.forEach((k, v) -> vectors.put(k, v.clone()));
    }

    private static void writeFully(FileChannel ch, java.nio.ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }
}
