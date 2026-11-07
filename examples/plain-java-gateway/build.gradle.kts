plugins {
    application
}

dependencies {
    implementation(project(":protoweft-server-jetty"))
    implementation(project(":protoweft-mcp"))
    implementation(project(":protoweft-utcp"))
    implementation(project(":protoweft-a2a"))
    implementation(project(":protoweft-rap"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.examples.gateway.GatewayExample")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
