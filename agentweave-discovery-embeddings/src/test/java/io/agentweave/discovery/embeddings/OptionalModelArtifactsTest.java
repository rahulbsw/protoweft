package io.agentweave.discovery.embeddings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loads this module in a class loader without some model jars, as a user's classpath would be:
 * all-MiniLM-L6-v2 is compileOnly, and bge may be excluded by users of hosted models only.
 */
class OptionalModelArtifactsTest {

    @Test
    void withoutTheMiniLmJarOnlyItsFactoryFails() throws Exception {
        try (URLClassLoader isolated = classpathWithout("langchain4j-embeddings-all-minilm-l6-v2-q")) {
            Class<?> factory = Class.forName(OfflineEmbedders.class.getName(), true, isolated);
            assertThat(factory.getClassLoader()).isSameAs(isolated);

            // bge's factory still works and loads nothing: loading waits for the first embedding.
            Object bge = factory.getMethod("bgeSmallEnV15").invoke(null);
            assertThat(bge.getClass().getMethod("dimension").invoke(bge)).isEqualTo(384);

            assertThat(failureOf(factory, "allMiniLmL6V2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.23.0-beta33");
        }
    }

    @Test
    void withoutAnyInProcessRuntimeTheAdapterStillWorks() throws Exception {
        try (URLClassLoader isolated = classpathWithout(
                "langchain4j-embeddings-bge-small-en-v15-q",
                "langchain4j-embeddings-all-minilm-l6-v2-q",
                "langchain4j-embeddings-1.23.0",
                "onnxruntime",
                "ai.djl")) {
            Class<?> factory = Class.forName(OfflineEmbedders.class.getName(), true, isolated);
            assertThat(failureOf(factory, "bgeSmallEnV15"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:1.23.0-beta33");

            Class<?> modelType = Class.forName("dev.langchain4j.model.embedding.EmbeddingModel", true, isolated);
            var fakeConstructor = Class.forName(RecordingEmbeddingModel.class.getName(), true, isolated)
                    .getDeclaredConstructor(int.class);
            fakeConstructor.setAccessible(true); // package-private, and a different runtime package here
            Object fakeModel = fakeConstructor.newInstance(3);
            Class<?> adapter = Class.forName(LangChain4jEmbedder.class.getName(), true, isolated);
            Object embedder = adapter.getMethod("of", modelType).invoke(null, fakeModel);
            Object vector = adapter.getMethod("embedDocument", String.class).invoke(embedder, "x");
            assertThat((float[]) vector).hasSize(3);
        }
    }

    private static URLClassLoader classpathWithout(String... fragments) throws Exception {
        List<URL> urls = new ArrayList<>();
        int removed = 0;
        entries:
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            for (String fragment : fragments) {
                if (entry.contains(fragment)) {
                    removed++;
                    continue entries;
                }
            }
            urls.add(new File(entry).toURI().toURL());
        }
        assertThat(removed).as("jars removed from the test classpath").isGreaterThanOrEqualTo(fragments.length);
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static Throwable failureOf(Class<?> factory, String method) throws Exception {
        try {
            factory.getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
        throw new AssertionError(method + " did not fail");
    }
}
