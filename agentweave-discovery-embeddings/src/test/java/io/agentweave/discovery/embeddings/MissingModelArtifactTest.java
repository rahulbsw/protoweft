package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Loads this module in a class loader without the model artifact, as a user's classpath might be. */
class MissingModelArtifactTest {

    private static final String MODEL_ARTIFACT = "agentweave-model-bge-small-en-v15";

    @Test
    void withoutTheModelArtifactTheFactoryNamesTheCoordinates() throws Exception {
        List<URL> urls = new ArrayList<>();
        int removed = 0;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.contains(MODEL_ARTIFACT)) {
                removed++;
            } else {
                urls.add(new File(entry).toURI().toURL());
            }
        }
        assertThat(removed).as("model artifact on the test classpath").isPositive();

        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> factory = Class.forName(OfflineEmbedders.class.getName(), true, isolated);
            assertThat(factory.getClassLoader()).isSameAs(isolated);
            try {
                factory.getMethod("bgeSmallEnV15").invoke(null);
                throw new AssertionError("bgeSmallEnV15() did not fail");
            } catch (InvocationTargetException e) {
                assertThat(e.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("io.agentweave:agentweave-model-bge-small-en-v15:")
                        .hasMessageContaining("io/agentweave/models/bge-small-en-v15/model.onnx");
            }
        }
    }
}
