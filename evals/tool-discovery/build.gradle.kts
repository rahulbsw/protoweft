// Evaluation harness for tool discovery. Eval-only: never published, so the LLM SDKs below do
// not leak into any AgentWeave library (the "ships no LLM clients" non-goal holds).
plugins {
    application
}

dependencies {
    implementation(project(":agentweave-core"))
    // The discovery layer under test: catalog retrievers, the generic search arm, native payloads.
    implementation(project(":agentweave-discovery"))
    implementation(project(":agentweave-discovery-embeddings"))
    // OfflineEmbedders.allMiniLmL6V2() (the catalog-hybrid-minilm retriever) needs its model
    // artifact at runtime; agentweave-discovery-embeddings only declares it compileOnly.
    runtimeOnly("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:${project.property("langchain4jVersion")}-beta33")
    implementation("tools.jackson.core:jackson-databind:${project.property("jackson3Version")}")
    implementation("com.anthropic:anthropic-java:3.5.0")
    implementation("com.openai:openai-java:4.100.0")
    implementation("com.knuddels:jtokkit:1.1.0")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.agentweave.evals.discovery.EvalMain")
    applicationName = "tool-discovery-eval"
}

// Run from the module directory so default paths (build/data, build/eval-results) resolve here.
tasks.named<JavaExec>("run") {
    workingDir = projectDir
}

// The harness is not a library: never publish it.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
