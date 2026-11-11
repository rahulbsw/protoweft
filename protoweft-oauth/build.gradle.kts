// Protocol-neutral OAuth 2.1 support (MCP authorization 2025-11-25): an OAuth client for connectors
// and resource-server helpers for server endpoints. Built on Nimbus; no Nimbus type in the public API.
dependencies {
    api(project(":protoweft-core"))
    api(project(":protoweft-server"))
    implementation("com.nimbusds:oauth2-oidc-sdk:${project.property("nimbusOauthVersion")}")
    implementation("com.nimbusds:nimbus-jose-jwt:${project.property("nimbusJoseVersion")}")
    compileOnly("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")

    testImplementation(project(":protoweft-server-jetty"))
    testImplementation(project(":protoweft-mcp"))
    testImplementation("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    "fuzzImplementation"("com.code-intelligence:jazzer-junit:${project.property("jazzerVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}
