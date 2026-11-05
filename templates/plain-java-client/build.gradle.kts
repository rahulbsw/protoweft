// A plain-Java program (no framework) that calls tools on MCP servers, UTCP manuals and A2A
// agents through one registry. Copy this directory to start a new project.
plugins {
    application
}

group = "com.example"
version = "0.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories {
    mavenCentral()
}

val agentweaveVersion: String by project

dependencies {
    implementation(platform("io.agentweave:agentweave-bom:$agentweaveVersion"))
    implementation("io.agentweave:agentweave-mcp")
    implementation("io.agentweave:agentweave-utcp")
    implementation("io.agentweave:agentweave-a2a")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17") // logs go to stderr
}

application {
    mainClass.set("com.example.client.Main")
}
