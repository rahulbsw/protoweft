plugins {
    application
}

dependencies {
    implementation(project(":protoweft-server-jetty"))
    implementation(project(":protoweft-mcp"))
    implementation(project(":protoweft-utcp"))
    implementation(project(":protoweft-a2a"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.examples.gateway.GatewayExample")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
