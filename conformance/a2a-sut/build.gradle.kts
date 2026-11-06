// The A2A TCK's system under test: Protoweft's A2aAgentServer on embedded Jetty, behaving as the
// TCK's Gherkin scenarios prescribe. Run by scripts/conformance/a2a-tck.sh. Never published.
plugins {
    application
}

dependencies {
    implementation(project(":protoweft-server-jetty"))
    implementation(project(":protoweft-a2a"))
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.conformance.a2a.ConformanceA2aSut")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
