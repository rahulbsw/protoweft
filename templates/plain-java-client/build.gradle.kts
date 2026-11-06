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

val protoweftVersion: String by project

dependencies {
    implementation(platform("io.github.rahulbsw:protoweft-bom:$protoweftVersion"))
    implementation("io.github.rahulbsw:protoweft-mcp")
    implementation("io.github.rahulbsw:protoweft-utcp")
    implementation("io.github.rahulbsw:protoweft-a2a")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17") // logs go to stderr
}

application {
    mainClass.set("com.example.client.Main")
}
