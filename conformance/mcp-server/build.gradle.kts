// The MCP conformance suite's server under test: AgentWeave's McpToolServer on embedded Jetty,
// publishing the suite's fixtures. Run by scripts/conformance/mcp-server.sh. Never published.
plugins {
    application
}

dependencies {
    implementation(project(":agentweave-server-jetty"))
    implementation(project(":agentweave-mcp"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.agentweave.conformance.mcp.server.ConformanceMcpServer")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
