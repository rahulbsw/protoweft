// UTCP 1.1 client connector and manual provider, implemented from the specification (no official Java SDK).
dependencies {
    api(project(":agentweave-core"))
    api(project(":agentweave-server"))
    implementation("tools.jackson.core:jackson-databind:${project.property("jackson3Version")}")
    compileOnly("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")

    testImplementation(project(":agentweave-server-jetty"))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

// Single source of truth for the module version reported in ComplianceReport.
tasks.processResources {
    val moduleVersion = project.version.toString()
    inputs.property("moduleVersion", moduleVersion)
    filesMatching("**/agentweave-utcp.properties") {
        expand(mapOf("moduleVersion" to moduleVersion))
    }
}
