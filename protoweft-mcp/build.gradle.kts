dependencies {
    api(project(":protoweft-core"))
    // McpToolServer.http() hands its servlet to any host through the server hosting contract.
    api(project(":protoweft-server"))
    // Provided by the servlet host; mcp-core declares it the same way.
    compileOnly("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")
    // Official MCP Java SDK (SDK major 2.x). `mcp` = mcp-core + Jackson 3 JSON mapper.
    implementation("io.modelcontextprotocol.sdk:mcp:${project.property("mcpSdkVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    // Embedded Jetty hosts McpToolServer.http() in the HTTP end-to-end tests.
    testImplementation(project(":protoweft-server-jetty"))
    // The discovery meta-tools are served over MCP in McpDiscoveryToolsTest.
    testImplementation(project(":protoweft-discovery"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // slf4j-simple logs to stderr, which keeps the stdio test server's stdout clean for JSON-RPC.
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

// Tests launch stdio servers (TestMcpServer, TestStdioToolServer) as subprocesses on the test runtime classpath.
tasks.test {
    systemProperty("protoweft.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

// Single source of truth for the SDK version reported in ComplianceReport.
tasks.processResources {
    val sdkVersion = project.property("mcpSdkVersion")
    inputs.property("mcpSdkVersion", sdkVersion)
    filesMatching("**/protoweft-mcp.properties") {
        expand(mapOf("mcpSdkVersion" to sdkVersion))
    }
}
