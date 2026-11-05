dependencies {
    api(project(":agentweave-core"))
    // Official MCP Java SDK (SDK major 2.x). `mcp` = mcp-core + Jackson 3 JSON mapper.
    implementation("io.modelcontextprotocol.sdk:mcp:${project.property("mcpSdkVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // slf4j-simple logs to stderr, which keeps the stdio test server's stdout clean for JSON-RPC.
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

// Tests launch TestMcpServer as a real stdio subprocess on the test runtime classpath.
tasks.test {
    systemProperty("agentweave.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

// Single source of truth for the SDK version reported in ComplianceReport.
tasks.processResources {
    val sdkVersion = project.property("mcpSdkVersion")
    inputs.property("mcpSdkVersion", sdkVersion)
    filesMatching("**/agentweave-mcp.properties") {
        expand(mapOf("mcpSdkVersion" to sdkVersion))
    }
}
