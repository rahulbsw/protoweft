// Embedders for agentweave-discovery: an adapter for any LangChain4j EmbeddingModel, plus
// offline in-process models (ONNX Runtime, CPU) that need no API key or network.

// LangChain4j publishes its in-process ONNX embedding models as beta artifacts alongside each
// release; 1.23.0 pairs with 1.23.0-beta33.
val langchain4jVersion = project.property("langchain4jVersion") as String
val inProcessEmbeddingsVersion = "$langchain4jVersion-beta33"

dependencies {
    api(project(":agentweave-discovery"))
    // EmbeddingModel appears in the public API (LangChain4jEmbedder).
    api("dev.langchain4j:langchain4j-core:$langchain4jVersion")

    // Default offline model (bge-small-en-v1.5, quantized, ~25 MB): shipped so that
    // OfflineEmbedders.bgeSmallEnV15() works with no further setup.
    api("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:$inProcessEmbeddingsVersion")
    // Alternative offline model (all-MiniLM-L6-v2, quantized, ~16 MB): opt-in. Users who want
    // OfflineEmbedders.allMiniLmL6V2() add this artifact to their runtime classpath.
    compileOnly("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:$inProcessEmbeddingsVersion")

    testImplementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:$inProcessEmbeddingsVersion")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}
