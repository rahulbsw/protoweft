// Typed OpenAI Responses API adapter for protoweft-discovery: builds tool-search request parts
// with the official OpenAI Java SDK types and answers tool_search / function calls from a
// CapabilityCatalog. It makes no model calls; users send the params with their own OpenAIClient.

// Kept local to this module (not in gradle.properties) so the shared build files stay untouched.
val openAiJavaVersion = "4.100.0"

dependencies {
    api(project(":protoweft-discovery"))
    // SDK types (Tool, Response, ResponseInputItem, ...) appear in the public API.
    api("com.openai:openai-java:$openAiJavaVersion")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
