// The MCP conformance suite's client under test: a CLI built on Protoweft's McpClientConnector
// (and protoweft-oauth's OAuthClient for the auth/* scenarios). The suite runs it as
// `<command> <server-url>`. Run by scripts/conformance/mcp-client.sh. Never published.
plugins {
    application
}

dependencies {
    implementation(project(":protoweft-mcp"))
    implementation(project(":protoweft-oauth"))
    // Reads the suite's MCP_CONFORMANCE_CONTEXT JSON (pre-registered client credentials).
    implementation("tools.jackson.core:jackson-databind:${project.property("jackson3Version")}")
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.conformance.mcp.client.ConformanceMcpClient")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
