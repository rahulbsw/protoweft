// Reactive Agent Protocol (Hydro project / AWS Infinity) client and tool server, implemented from the
// specification (no Java SDK exists).
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

// Single source of truth for the module version reported in ComplianceReport.
tasks.processResources {
    val moduleVersion = project.version.toString()
    inputs.property("moduleVersion", moduleVersion)
    filesMatching("**/protoweft-rap.properties") {
        expand(mapOf("moduleVersion" to moduleVersion))
    }
}
