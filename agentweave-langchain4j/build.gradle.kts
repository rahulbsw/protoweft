// LangChain4j adapter: AgentWeave tools into AI Services, LangChain4j agents and @Tool methods out.
dependencies {
    api(project(":agentweave-core"))
    // CatalogToolSearchStrategy searches a CapabilityCatalog; its types appear in the public API.
    api(project(":agentweave-discovery"))
    api("dev.langchain4j:langchain4j:${project.property("langchain4jVersion")}")
    // Jackson 2 is already a compile dependency of LangChain4j; no version here so LangChain4j's governs.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}
