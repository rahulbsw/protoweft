// Spring Boot application serving one set of @AgentTool methods over MCP, UTCP and A2A.
plugins {
    // Keep in step with springBootVersion in gradle.properties (plugin versions can't read properties).
    id("org.springframework.boot") version "4.1.2"
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${project.property("springBootVersion")}"))
    implementation(project(":protoweft-spring-boot-starter"))
    // The protocols this application serves; the starter auto-configures only those present.
    implementation(project(":protoweft-mcp"))
    implementation(project(":protoweft-utcp"))
    implementation(project(":protoweft-a2a"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Examples are not published.
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
