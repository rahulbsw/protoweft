// Spring Boot application: an ARGI ReactAgent that uses AgentWeave tools picked by the capability
// catalog, served over A2A with human approval of a sensitive tool.
plugins {
    // Keep in step with springBootVersion in gradle.properties (plugin versions can't read properties).
    id("org.springframework.boot") version "4.1.2"
}

val springAiVersion = project.property("springAiVersion")

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${project.property("springBootVersion")}"))
    implementation(project(":agentweave-argi"))
    // The starter scans @AgentTool beans into the ToolRegistry, builds the CapabilityCatalog
    // (agentweave.discovery.enabled) and serves the AgentHandler bean over A2A.
    implementation(project(":agentweave-spring-boot-starter"))
    implementation(project(":agentweave-discovery"))
    implementation(project(":agentweave-a2a"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Any Spring AI chat model works; OpenAI's also talks to OpenAI-compatible servers (e.g. Ollama).
    implementation("org.springframework.ai:spring-ai-starter-model-openai:$springAiVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Examples are not published.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
