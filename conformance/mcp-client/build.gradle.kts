// The MCP conformance suite's client under test: a CLI built on AgentWeave's McpClientConnector.
// The suite runs it as `<command> <server-url>`. Run by scripts/conformance/mcp-client.sh.
// Never published.
plugins {
    application
}

dependencies {
    implementation(project(":agentweave-mcp"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.agentweave.conformance.mcp.client.ConformanceMcpClient")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
