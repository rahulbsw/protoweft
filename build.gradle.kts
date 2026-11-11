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

val repoUrl = "https://github.com/rahulbsw/protoweft"

// Modules that are libraries but must not be published yet (see their SPEC.md).
val unpublished = setOf("protoweft-argi-neutral")

// Published library modules: every protoweft-* module except the BOM (published as a platform).
val libraryProjects = subprojects.filter {
    it.name.startsWith("protoweft-") && it.name != "protoweft-bom" && it.name !in unpublished &&
        it.buildFile.exists()
}

// Each library module builds its own Javadoc (resolving only its own compile classpath, which Gradle
// 9 allows, unlike a root task resolving another project's configuration). Apply the shared Javadoc
// options to every module so the per-module output is consistent and lenient about doc lint.
libraryProjects.forEach { p ->
    p.plugins.withId("java") {
        p.tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                encoding = "UTF-8"
                addStringOption("Xdoclint:none", "-quiet")
                links("https://docs.oracle.com/en/java/javase/17/docs/api/")
            }
        }
    }
}

// Collect every library module's Javadoc under one directory (published with the documentation under
// /api/<module>/), with a landing page linking each module's API.
tasks.register<Sync>("aggregateJavadoc") {
    description = "Collects each Protoweft library module's Javadoc for publishing."
    group = "documentation"
    into(layout.buildDirectory.dir("docs/javadoc"))
    libraryProjects.forEach { p ->
        p.plugins.withId("java") {
            val javadoc = p.tasks.named<Javadoc>("javadoc")
            from(javadoc.map { it.destinationDir!! }) { into(p.name) }
        }
    }
    doLast {
        val root = layout.buildDirectory.dir("docs/javadoc").get().asFile
        // Only link modules that actually produced Javadoc (a source-less module makes none).
        val modules = root.listFiles { f -> f.isDirectory && f.resolve("index.html").exists() }
            ?.map { it.name }?.sorted() ?: emptyList()
        val links = modules.joinToString("\n") { "      <li><a href=\"$it/index.html\">$it</a></li>" }
        root.resolve("index.html").writeText(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="utf-8"><title>Protoweft ${project.property("version")} API</title></head>
            <body>
              <h1>Protoweft ${project.property("version")} API</h1>
              <p>Javadoc by module:</p>
              <ul>
            $links
              </ul>
            </body>
            </html>
            """.trimIndent(),
        )
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
            description.set(project.description ?: "Protoweft: interoperable agent and tool protocols for Java")
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
                connection.set("scm:git:git://github.com/rahulbsw/protoweft.git")
                developerConnection.set("scm:git:ssh://git@github.com/rahulbsw/protoweft.git")
            }
        }
    }
}

// Every Java module (directories with a build file); container directories like `examples/`
// and the java-platform BOM are skipped.
configure(subprojects.filter { it.buildFile.exists() && it.name != "protoweft-bom" }) {
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

    // A stable JPMS name for classpath and module-path users: io.github.rahulbsw.protoweft.<module>.
    tasks.named<Jar>("jar") {
        manifest {
            attributes(
                "Automatic-Module-Name" to "io.github.rahulbsw.protoweft." +
                    project.name.removePrefix("protoweft-").replace('-', '.'),
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

project(":protoweft-bom") {
    group = rootProject.property("group") as String
    version = rootProject.property("version") as String
    description = "Protoweft bill of materials: aligns the versions of every Protoweft module"
    pluginManager.withPlugin("java-platform") { publishToCentral(JavaPlatform()) }
}

// Security floor: ARGI pulls jsoup 1.15.4 transitively. Substituting before resolution means its
// metadata is never even downloaded (see protoweft-argi/SPEC.md "Dependency overrides").
allprojects {
    val jsoupVersion = rootProject.property("jsoupVersion")
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.jsoup:jsoup")).using(module("org.jsoup:jsoup:$jsoupVersion"))
                .because("jsoup < 1.23.2 has GHSA-65r4-943x-97jj and GHSA-pmhh-3w7g-xqp8")
        }
    }
}
