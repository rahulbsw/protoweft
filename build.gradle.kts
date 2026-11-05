allprojects {
    repositories {
        mavenCentral()
    }
}

// Published library modules (examples and the BOM excluded).
val libraryProjects = subprojects.filter {
    it.name.startsWith("agentweave-") && it.name != "agentweave-bom" && it.buildFile.exists()
}

// One Javadoc site for all library modules, published with the documentation under /api/.
tasks.register<Javadoc>("aggregateJavadoc") {
    description = "Generates combined Javadoc for all AgentWeave library modules."
    group = "documentation"
    title = "AgentWeave ${project.property("version")} API"
    setDestinationDir(layout.buildDirectory.dir("docs/javadoc").get().asFile)
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
        links("https://docs.oracle.com/en/java/javase/17/docs/api/")
    }
    libraryProjects.forEach { p ->
        p.plugins.withId("java") {
            val main = p.extensions.getByType<SourceSetContainer>()["main"]
            source(main.allJava)
            classpath += main.compileClasspath + main.output
        }
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
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/license/mit")
                        }
                    }
                }
            }
        }
    }
}
