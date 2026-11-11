// Spring Boot application: a Spring AI ChatClient served over AG-UI to a small browser page built
// on the official @ag-ui/client (frontend/). The page is built separately with npm (see README).
plugins {
    // Keep in step with springBootVersion in gradle.properties (plugin versions can't read properties).
    id("org.springframework.boot") version "4.1.2"
}

val springAiVersion = project.property("springAiVersion")

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${project.property("springBootVersion")}"))
    implementation(project(":protoweft-spring-ai"))
    // The starter scans @AgentTool beans and serves the AgentHandler bean over AG-UI.
    implementation(project(":protoweft-spring-boot-starter"))
    implementation(project(":protoweft-agui"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Any Spring AI chat model works; OpenAI's also talks to OpenAI-compatible servers (e.g. Ollama).
    implementation("org.springframework.ai:spring-ai-starter-model-openai:$springAiVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Examples are not published.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
