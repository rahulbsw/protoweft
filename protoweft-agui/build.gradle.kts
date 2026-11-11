// Agent-User Interaction Protocol (AG-UI) client and agent server, implemented from spec 1.0 with no
// third-party runtime dependency beyond the Jackson 3 the other modules already use.
dependencies {
    api(project(":protoweft-core"))
    api(project(":protoweft-server"))
    implementation("tools.jackson.core:jackson-databind:${project.property("jackson3Version")}")
    compileOnly("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")

    testImplementation(project(":protoweft-server-jetty"))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    "fuzzImplementation"("com.code-intelligence:jazzer-junit:${project.property("jazzerVersion")}")
    // Test-only: checks produced events against the vendored official schema.json.
    testImplementation("com.networknt:json-schema-validator:${project.property("jsonSchemaValidatorVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

// Single source of truth for the module version reported in ComplianceReport.
tasks.processResources {
    val moduleVersion = project.version.toString()
    inputs.property("moduleVersion", moduleVersion)
    filesMatching("**/protoweft-agui.properties") {
        expand(mapOf("moduleVersion" to moduleVersion))
    }
}

// The schema validator asks for a newer Jackson 3.1.x; keep tests on the version users get at runtime.
configurations.matching { it.name.startsWith("test") || it.name.startsWith("fuzz") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("tools.jackson")) {
            useVersion(project.property("jackson3Version").toString())
        }
    }
}
