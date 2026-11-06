// ARGI neutral-API adapter: Protoweft tools as ARGI Tools, catalog-based tool selection, and ARGI
// Agents served by Protoweft agent servers. Built only with -PargiSnapshot, against ARGI main
// installed into mavenLocal by scripts/install-argi-snapshot.sh (pinned commit, see SPEC.md).
// Not published (root build `unpublished`) until ARGI publishes 2.1.0-RC2.
val argiVersion = "2.1.0-RC2-SNAPSHOT"

repositories {
    // ARGI's group only ever comes from mavenLocal, and nothing else does.
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeGroup("io.github.agentic-ai-java") }
    }
}

dependencies {
    api(project(":protoweft-core"))
    // Tool, ToolRegistry, ToolContext, Agent, AgentRequest and ModelInterceptor appear in the public API.
    // argi-agent-api brings argi-runtime-api (AgentExecutionContext). No Spring anywhere.
    api("io.github.agentic-ai-java:argi-api:$argiVersion")
    api("io.github.agentic-ai-java:argi-agent-api:$argiVersion")
    // Schemas and arguments: ARGI's tool contract carries JSON text.
    implementation("tools.jackson.core:jackson-databind:${project.property("jackson3Version")}")

    // Optional: only CatalogToolSelectionInterceptor uses it.
    compileOnly(project(":protoweft-discovery"))

    // A real ReActAgent (Spring-free) drives the integration tests.
    testImplementation("io.github.agentic-ai-java:argi-agent-core:$argiVersion")
    testImplementation(project(":protoweft-discovery"))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
