// Typed Anthropic adapter for protoweft-discovery: tool-search request parts and response
// handling built on the official Anthropic Java SDK. Protoweft sends no requests itself.
val anthropicSdkVersion = "3.5.0"

dependencies {
    api(project(":protoweft-discovery"))
    // SDK types (Tool, ToolUnion, ToolResultBlockParam, Message, ...) appear in the public API.
    api("com.anthropic:anthropic-java:$anthropicSdkVersion")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
