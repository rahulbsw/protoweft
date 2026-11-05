// Bill of materials: import with `implementation(platform("io.agentweave:agentweave-bom:<version>"))`
// to align every AgentWeave module version.
plugins {
    `java-platform`
    `maven-publish`
}

group = rootProject.property("group") as String
version = rootProject.property("version") as String

// Every published library module (examples and the BOM itself are excluded).
val libraries = rootProject.subprojects.filter {
    it.name.startsWith("agentweave-") && it.name != "agentweave-bom" && it.buildFile.exists()
}

dependencies {
    constraints {
        libraries.forEach { api(project(it.path)) }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
            pom {
                name.set("agentweave-bom")
                description.set("AgentWeave bill of materials")
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
