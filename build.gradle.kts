import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
    id("org.cyclonedx.bom") version "3.5.1"
}

allprojects {
    repositories {
        mavenCentral()
    }
}

val repoUrl = "https://github.com/rahulbsw/agentweave"

// Modules that are libraries but must not be published yet (see their SPEC.md).
val unpublished = setOf("agentweave-argi-neutral")

// Published library modules: every agentweave-* module except the BOM (published as a platform).
val libraryProjects = subprojects.filter {
    it.name.startsWith("agentweave-") && it.name != "agentweave-bom" && it.name !in unpublished &&
        it.buildFile.exists()
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

/** Maven Central metadata and signing, shared by libraries and the BOM (see RELEASING.md). */
fun Project.publishToCentral(kind: com.vanniktech.maven.publish.Platform) {
    apply(plugin = "com.vanniktech.maven.publish.base")
    configure<MavenPublishBaseExtension> {
        publishToMavenCentral(true)
        // Signing only when a key is supplied (CI release); local publishToMavenLocal stays unsigned.
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        coordinates(rootProject.property("group") as String, project.name, project.version.toString())
        configure(kind)
        pom {
            name.set(project.name)
            description.set(project.description ?: "AgentWeave: interoperable agent and tool protocols for Java")
            url.set(repoUrl)
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("rahulbsw")
                    name.set("Rahul Jain")
                    url.set("https://github.com/rahulbsw")
                }
            }
            scm {
                url.set(repoUrl)
                connection.set("scm:git:git://github.com/rahulbsw/agentweave.git")
                developerConnection.set("scm:git:ssh://git@github.com/rahulbsw/agentweave.git")
            }
        }
    }
}

// Every Java module (directories with a build file); container directories like `examples/`
// and the java-platform BOM are skipped.
configure(subprojects.filter { it.buildFile.exists() && it.name != "agentweave-bom" }) {
    apply(plugin = "java-library")

    group = rootProject.property("group") as String
    version = rootProject.property("version") as String

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
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

    // A stable JPMS name for classpath and module-path users: io.agentweave.<module>.
    tasks.named<Jar>("jar") {
        manifest {
            attributes(
                "Automatic-Module-Name" to "io.agentweave." +
                    project.name.removePrefix("agentweave-").replace('-', '.'),
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version.toString(),
            )
        }
    }
}

configure(libraryProjects) {
    publishToCentral(JavaLibrary(JavadocJar.Javadoc(), SourcesJar.Sources()))
}

// SBOM (`./gradlew cyclonedxBom` -> build/reports/cyclonedx/bom.json): runtime dependencies of the
// published libraries only; examples, evals, conformance apps and the build itself are left out.
allprojects {
    tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
        enabled = project in libraryProjects
        includeConfigs = listOf("runtimeClasspath")
    }
}

project(":agentweave-bom") {
    group = rootProject.property("group") as String
    version = rootProject.property("version") as String
    description = "AgentWeave bill of materials: aligns the versions of every AgentWeave module"
    pluginManager.withPlugin("java-platform") { publishToCentral(JavaPlatform()) }
}
