// Spring AI adapter: Protoweft tools into ChatClient, the catalog as Spring AI's tool-search index,
// ChatClient agents served by Protoweft agent servers, and Spring AI tools out.
val springAiVersion = project.property("springAiVersion")

dependencies {
    api(project(":protoweft-core"))
    // ToolCallback, ToolCallbackProvider, ToolDefinition and ToolContext appear in the public API.
    api("org.springframework.ai:spring-ai-model:$springAiVersion")
    // Jackson 3 is a compile dependency of spring-ai-model; no version here so Spring AI's governs.
    implementation("tools.jackson.core:jackson-databind")

    // Optional: needed only by the classes that use them (SpringAiAgents, CatalogToolIndex).
    compileOnly("org.springframework.ai:spring-ai-client-chat:$springAiVersion")
    compileOnly("org.springframework.ai:spring-ai-tool-search-tool:$springAiVersion")
    compileOnly("org.springframework.ai:spring-ai-tool-search-advisor:$springAiVersion")
    compileOnly(project(":protoweft-discovery"))
    // Optional: AgUiPrompts, used only when a ChatClient agent is served over AG-UI.
    compileOnly(project(":protoweft-agui"))

    testImplementation("org.springframework.ai:spring-ai-client-chat:$springAiVersion")
    testImplementation("org.springframework.ai:spring-ai-tool-search-tool:$springAiVersion")
    testImplementation("org.springframework.ai:spring-ai-tool-search-advisor:$springAiVersion")
    testImplementation(project(":protoweft-discovery"))
    testImplementation(project(":protoweft-agui"))
    testImplementation(project(":protoweft-server-jetty"))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}
