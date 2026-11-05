allprojects {
    repositories {
        mavenCentral()
    }
}

// Only real Java modules (directories with a build file); container directories like `examples/`
// and the java-platform BOM are skipped.
configure(subprojects.filter { it.buildFile.exists() && it.name != "agentweave-bom" }) {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = rootProject.property("group") as String
    version = rootProject.property("version") as String

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("AgentWeave: interoperable agent and tool protocols for Java")
                }
            }
        }
    }
}
