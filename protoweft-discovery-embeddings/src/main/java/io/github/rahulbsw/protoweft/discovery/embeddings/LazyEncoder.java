package io.github.rahulbsw.protoweft.discovery.embeddings;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Creates a {@link TextEncoder} on first use, exactly once even under concurrent first use. A
 * failed load is rethrown as {@link IllegalStateException} and retried on the next call.
 */
final class LazyEncoder {

    private final String modelName;
    private final Supplier<? extends TextEncoder> loader;
    private volatile TextEncoder encoder;

    LazyEncoder(String modelName, Supplier<? extends TextEncoder> loader) {
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    boolean isLoaded() {
        return encoder != null;
    }

    TextEncoder get() {
        TextEncoder e = encoder;
        if (e == null) {
            synchronized (this) {
                e = encoder;
                if (e == null) {
                    e = load();
                    encoder = e;
                }
            }
        }
        return e;
    }

    private TextEncoder load() {
        TextEncoder loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException("Could not load embedding model '" + modelName + "': " + e, e);
        }
        return Objects.requireNonNull(loaded, "loader returned null");
    }
}
