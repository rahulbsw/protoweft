// ARGI 2.1.0-RC1 adapter: Protoweft tools into ARGI agents, catalog-based tool selection as a
// ModelInterceptor, and ARGI agents (including human-in-the-loop interrupts) served by Protoweft
// agent servers.
description = "Protoweft ARGI adapter: tools in, catalog tool selection, ARGI agents over A2A"

val springAiVersion = project.property("springAiVersion")

dependencies {
    // ProtoweftToolCallbacks / CatalogToolIndex are reused, and appear in the public API.
    api(project(":protoweft-spring-ai"))
    // ReactAgent, Agent, ModelInterceptor, RunnableConfig and InterruptionMetadata appear in the
    // public API. ARGI RC1 is built against Spring AI 2.0.1; the explicit spring-ai-client-chat
    // keeps it aligned with the 2.0.2 spring-ai-model that protoweft-spring-ai exports.
    api("io.github.agentic-ai-java:argi-agent-framework:${project.property("argiVersion")}")
    implementation("org.springframework.ai:spring-ai-client-chat:$springAiVersion")
    // Jackson 3 (edited tool arguments in human feedback); unversioned, so Spring AI's governs.
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api:${project.property("slf4jVersion")}")
    // Security floor for ARGI's HTML-to-Markdown converter; see SPEC.md "Dependency overrides".
    runtimeOnly("org.jsoup:jsoup:${project.property("jsoupVersion")}")

    // Optional: only CatalogToolSelectionInterceptor needs it.
    compileOnly(project(":protoweft-discovery"))
    // Optional: ArgiAgUi, used only when an ARGI agent is served over AG-UI.
    compileOnly(project(":protoweft-agui"))
    // The interceptor reuses CatalogToolIndex's catalog callbacks, which load ToolIndex; the jar
    // has no required dependencies besides jackson-annotations (Lucene and vector store are optional).
    implementation("org.springframework.ai:spring-ai-tool-search-tool:$springAiVersion")

    testImplementation(project(":protoweft-discovery"))
    testImplementation(project(":protoweft-a2a"))
    testImplementation(project(":protoweft-agui"))
    testImplementation(project(":protoweft-server-jetty"))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}
