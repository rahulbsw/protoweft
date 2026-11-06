// A2A (spec 1.0, JSON-RPC binding) client connector and agent server, built on a2a-java.
dependencies {
    api(project(":protoweft-core"))
    api(project(":protoweft-server"))
    // Servlet API is provided by the host (embedded Jetty or the Spring Boot container).
    compileOnly("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")

    val a2a = project.property("a2aSdkVersion")
    // Client: includes the JSON-RPC client transport and the JDK HTTP client.
    implementation("org.a2aproject.sdk:a2a-java-sdk-client:$a2a")
    // Server: framework-neutral request handling plus the JSON-RPC transport handler.
    implementation("org.a2aproject.sdk:a2a-java-sdk-server-common:$a2a")
    implementation("org.a2aproject.sdk:a2a-java-sdk-transport-jsonrpc:$a2a")

    testImplementation(project(":protoweft-server-jetty"))
    testImplementation("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

// Single source of truth for the SDK version reported in ComplianceReport.
tasks.processResources {
    val sdkVersion = project.property("a2aSdkVersion")
    inputs.property("a2aSdkVersion", sdkVersion)
    filesMatching("**/protoweft-a2a.properties") {
        expand(mapOf("a2aSdkVersion" to sdkVersion))
    }
}
