package io.github.rahulbsw.protoweft.rap;

import io.github.rahulbsw.protoweft.core.ErrorCategory;
import io.github.rahulbsw.protoweft.core.ProtocolId;
import io.github.rahulbsw.protoweft.core.ProtoweftException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A {@link RapStore} kept as one JSON file ({@code rap-store.json}) in a directory. Every change
 * re-reads the file, applies the change and atomically replaces the file, all under an exclusive
 * lock on {@code rap-store.lock}, so several instances and processes can share the directory. On
 * POSIX file systems the files are readable by the owner only, since they hold callback URLs.
 *
 * <p>Thread-safe. Suited to modest volumes; use a database-backed store for heavy traffic.
 */
public final class FileRapStore implements RapStore {

    private static final String DATA_FILE = "rap-store.json";
    private static final String LOCK_FILE = "rap-store.lock";
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path data;
    private final Path lockFile;
    private final ReentrantLock jvmLock;

    /**
     * @param directory created if missing
     * @throws ProtoweftException with {@link ErrorCategory#CONFIGURATION} if it can't be created
     */
    public FileRapStore(Path directory) {
        try {
            Files.createDirectories(directory);
            Path dir = directory.toRealPath();
            this.data = dir.resolve(DATA_FILE);
            this.lockFile = dir.resolve(LOCK_FILE);
            this.jvmLock = JVM_LOCKS.computeIfAbsent(dir, d -> new ReentrantLock());
        } catch (IOException e) {
            throw new ProtoweftException(ErrorCategory.CONFIGURATION, ProtocolId.RAP,
                    "cannot use RAP store directory " + directory + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void saveCall(ClientCall call) {
        update(s -> s.calls.put(call.token(), call));
    }

    @Override
    public List<ClientCall> calls() {
        return read(s -> List.copyOf(s.calls.values()));
    }

    @Override
    public void deleteCall(String token) {
        update(s -> s.calls.remove(token));
    }

    @Override
    public boolean markProcessed(String key, Instant at) {
        return update(s -> s.markProcessed(key, at));
    }

    @Override
    public boolean isProcessed(String key) {
        return read(s -> s.processed.containsKey(key));
    }

    @Override
    public void purgeProcessed(Instant cutoff) {
        update(s -> {
            s.purgeProcessed(cutoff);
            return null;
        });
    }

    @Override
    public void saveSubscription(ServerSubscription subscription) {
        update(s -> {
            s.saveSubscription(subscription);
            return null;
        });
    }

    @Override
    public List<ServerSubscription> subscriptions() {
        return read(s -> List.copyOf(s.subscriptions.values()));
    }

    @Override
    public void deleteSubscription(String server, String threadId, String toolCallId) {
        update(s -> s.subscriptions.remove(StoreState.subscriptionKey(server, threadId, toolCallId)));
    }

    private <T> T read(Function<StoreState, T> query) {
        return locked(false, query);
    }

    private <T> T update(Function<StoreState, T> change) {
        return locked(true, change);
    }

    private <T> T locked(boolean write, Function<StoreState, T> action) {
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            StoreState state = load();
            T result = action.apply(state);
            if (write) {
                save(state);
            }
            return result;
        } catch (IOException e) {
            throw new ProtoweftException(ErrorCategory.INTERNAL, ProtocolId.RAP,
                    "RAP store " + data + " failed: " + e.getMessage(), e);
        } finally {
            jvmLock.unlock();
        }
    }

    private StoreState load() throws IOException {
        if (!Files.exists(data)) {
            return new StoreState();
        }
        byte[] bytes = Files.readAllBytes(data);
        if (bytes.length == 0) {
            return new StoreState();
        }
        return StoreState.fromPlain(Json.parseObject(bytes));
    }

    private void save(StoreState state) throws IOException {
        Path temp = Files.createTempFile(data.getParent(), DATA_FILE, ".tmp", ownerOnly());
        try {
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(Json.writeBytes(state.toPlain()));
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                out.force(true);
            }
            Files.move(temp, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static FileAttribute<?>[] ownerOnly() {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
        }
        return new FileAttribute<?>[0];
    }
}
