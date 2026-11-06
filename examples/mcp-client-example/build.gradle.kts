plugins {
    application
}

dependencies {
    implementation(project(":protoweft-mcp"))
    // The bundled demo server uses the MCP SDK directly; the client side uses only Protoweft.
    implementation("io.modelcontextprotocol.sdk:mcp:${project.property("mcpSdkVersion")}")
    // Logs go to stderr, keeping the demo server's stdout clean for JSON-RPC.
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.examples.mcp.McpClientExample")
}

// Examples are not published.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
