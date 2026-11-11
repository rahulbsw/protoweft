// The AG-UI conformance checker's and the official SDKs' system under test: Protoweft's
// AgUiAgentServer on embedded Jetty with a scripted agent that uses every event family. Run by
// scripts/conformance/agui.sh and scripts/interop/agui.sh. Never published.
plugins {
    application
}

dependencies {
    implementation(project(":protoweft-server-jetty"))
    implementation(project(":protoweft-agui"))
}

application {
    mainClass.set("io.github.rahulbsw.protoweft.conformance.agui.ConformanceAgUiSut")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
