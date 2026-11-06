// A Spring Boot 4 app that serves its @AgentTool methods over MCP (and, if you enable them,
// UTCP and A2A). Copy this directory to start a new project.
plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories {
    mavenCentral()
}

val protoweftVersion: String by project

dependencies {
    implementation(platform("io.github.rahulbsw:protoweft-bom:$protoweftVersion"))
    implementation("io.github.rahulbsw:protoweft-spring-boot-starter")
    implementation("io.github.rahulbsw:protoweft-mcp")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters") // lets @AgentTool read parameter names
}

tasks.test {
    useJUnitPlatform()
}
