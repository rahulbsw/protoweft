plugins {
    application
}

dependencies {
    implementation(project(":agentweave-server-jetty"))
    implementation(project(":agentweave-mcp"))
    implementation(project(":agentweave-utcp"))
    implementation(project(":agentweave-a2a"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.agentweave.examples.gateway.GatewayExample")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
