// Bill of materials: import with `implementation(platform("io.agentweave:agentweave-bom:<version>"))`
// to align every AgentWeave module version. Publishing is configured in the root build.
plugins {
    `java-platform`
}

// Every published library module (examples, evals, conformance apps and the BOM itself excluded).
val libraries = rootProject.subprojects.filter {
    it.name.startsWith("agentweave-") && it.name != "agentweave-bom" && it.name != "agentweave-argi-neutral" &&
        it.buildFile.exists()
}

dependencies {
    constraints {
        libraries.forEach { api(project(it.path)) }
    }
}
