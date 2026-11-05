// Spring Boot application: a Spring AI ChatClient that uses AgentWeave tools, served over A2A.
plugins {
    // Keep in step with springBootVersion in gradle.properties (plugin versions can't read properties).
    id("org.springframework.boot") version "4.1.2"
}

val springAiVersion = project.property("springAiVersion")

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${project.property("springBootVersion")}"))
    implementation(project(":agentweave-spring-ai"))
    // The starter scans @AgentTool beans, builds the ToolRegistry (plus MCP clients from config)
    // and serves the AgentHandler bean over A2A.
    implementation(project(":agentweave-spring-boot-starter"))
    implementation(project(":agentweave-a2a"))
    implementation(project(":agentweave-mcp"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Any Spring AI chat model works; OpenAI's also talks to OpenAI-compatible servers (e.g. Ollama).
    implementation("org.springframework.ai:spring-ai-starter-model-openai:$springAiVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Examples are not published.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
