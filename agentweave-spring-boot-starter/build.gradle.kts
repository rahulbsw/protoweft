// Spring Boot 4 auto-configuration for AgentWeave. Protocol modules are optional: each auto-config is
// guarded by @ConditionalOnClass, so applications add only the protocols they use.
dependencies {
    val boot = platform("org.springframework.boot:spring-boot-dependencies:${project.property("springBootVersion")}")

    api(project(":agentweave-core"))
    api(project(":agentweave-server"))

    // Provided by the Spring Boot application; versions come from the Boot BOM.
    compileOnly(boot)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("io.micrometer:micrometer-observation")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
    compileOnly("org.springframework.security:spring-security-core")
    // Annotations on protocol SDK types read while compiling against them.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    // Optional protocols: the application adds the ones it uses.
    compileOnly(project(":agentweave-mcp"))
    compileOnly(project(":agentweave-a2a"))
    compileOnly(project(":agentweave-utcp"))

    annotationProcessor(boot)
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation(boot)
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation(project(":agentweave-mcp"))
    testImplementation(project(":agentweave-a2a"))
    testImplementation(project(":agentweave-utcp"))
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-tomcat")
    testImplementation("org.springframework.boot:spring-boot-health")
    testImplementation("org.springframework.boot:spring-boot-actuator")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("org.springframework.boot:spring-boot-micrometer-observation")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework:spring-test")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The configuration processor merges additional-spring-configuration-metadata.json from the resources.
tasks.compileJava {
    inputs.files(tasks.processResources)
}

// Tests launch a stdio MCP server as a subprocess on the test runtime classpath.
tasks.test {
    systemProperty("agentweave.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

