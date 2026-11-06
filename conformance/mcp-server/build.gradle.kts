// The MCP conformance suite's server under test: Protoweft's McpToolServer on embedded Jetty,
// publishing the suite's fixtures. Run by scripts/conformance/mcp-server.sh. Never published.
plugins {
    application
}

dependencies {
    implementation(project(":protoweft-server-jetty"))
    implementation(project(":protoweft-mcp"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.conformance.mcp.server.ConformanceMcpServer")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
