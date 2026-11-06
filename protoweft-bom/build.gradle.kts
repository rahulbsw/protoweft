// Bill of materials: import with `implementation(platform("io.github.rahulbsw:protoweft-bom:<version>"))`
// to align every Protoweft module version. Publishing is configured in the root build.
plugins {
    `java-platform`
}

// Every published library module (examples, evals, conformance apps and the BOM itself excluded).
val libraries = rootProject.subprojects.filter {
    it.name.startsWith("protoweft-") && it.name != "protoweft-bom" && it.name != "protoweft-argi-neutral" &&
        it.buildFile.exists()
}

dependencies {
    constraints {
        libraries.forEach { api(project(it.path)) }
    }
}
