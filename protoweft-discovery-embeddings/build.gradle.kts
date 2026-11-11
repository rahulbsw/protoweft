// Offline embedders for protoweft-discovery: BERT models run in-process on ONNX Runtime (CPU),
// with a pure-Java WordPiece tokenizer. No API key, no network.

dependencies {
    api(project(":protoweft-discovery"))
    implementation("com.microsoft.onnxruntime:onnxruntime:1.23.2")
    // The default model (bge-small-en-v1.5, quantized): resources only, so OfflineEmbedders.bgeSmallEnV15()
    // works with no further setup.
    runtimeOnly(project(":protoweft-model-bge-small-en-v15"))

    // Parity tests only: LangChain4j's in-process bge model (same ONNX file) and DJL's HuggingFace
    // tokenizer (Rust tokenizers) are the references the pure-Java implementation is compared with.
    testImplementation("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:${project.property("langchain4jVersion")}-beta33")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    "fuzzImplementation"("com.code-intelligence:jazzer-junit:${project.property("jazzerVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

tasks.named<Test>("test") {
    maxHeapSize = "2g"
    // Optional parity corpus: the discovery eval datasets (`evals/tool-discovery`, `download`), read
    // only if present. Override with -PparityData=<dir>.
    val parityData = providers.gradleProperty("parityData")
        .orElse(rootProject.layout.projectDirectory.dir("evals/tool-discovery/build/data").asFile.path)
    systemProperty("protoweft.parity.data", parityData.get())
}
