// Spring Boot 4 auto-configuration for Protoweft. Protocol modules are optional: each auto-config is
// guarded by @ConditionalOnClass, so applications add only the protocols they use.
dependencies {
    val boot = platform("org.springframework.boot:spring-boot-dependencies:${project.property("springBootVersion")}")

    api(project(":protoweft-core"))
    api(project(":protoweft-server"))

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
    compileOnly(project(":protoweft-mcp"))
    compileOnly(project(":protoweft-a2a"))
    compileOnly(project(":protoweft-utcp"))
    compileOnly(project(":protoweft-rap"))
    // Optional tool discovery (protoweft.discovery.enabled).
    compileOnly(project(":protoweft-discovery"))
    // Optional Spring AI integration (protoweft.spring-ai.*).
    val springAi = project.property("springAiVersion")
    compileOnly(project(":protoweft-spring-ai"))
    compileOnly("org.springframework.ai:spring-ai-model:$springAi")
    compileOnly("org.springframework.ai:spring-ai-client-chat:$springAi")
    compileOnly("org.springframework.ai:spring-ai-tool-search-tool:$springAi")
    compileOnly("org.springframework.ai:spring-ai-tool-search-advisor:$springAi")

    annotationProcessor(boot)
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation(boot)
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation(project(":protoweft-mcp"))
    testImplementation(project(":protoweft-a2a"))
    testImplementation(project(":protoweft-utcp"))
    testImplementation(project(":protoweft-rap"))
    testImplementation(project(":protoweft-discovery"))
    testImplementation(project(":protoweft-spring-ai"))
    testImplementation("org.springframework.ai:spring-ai-client-chat:$springAi")
    testImplementation("org.springframework.ai:spring-ai-tool-search-tool:$springAi")
    testImplementation("org.springframework.ai:spring-ai-tool-search-advisor:$springAi")
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
    systemProperty("protoweft.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

