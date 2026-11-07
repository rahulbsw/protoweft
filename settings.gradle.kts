rootProject.name = "protoweft"

// Every module of the build. A module is included once its directory exists, so modules can
// be added without editing this file again.
val modules = listOf(
    "protoweft-bom",
    "protoweft-core",
    "protoweft-server",
    "protoweft-server-jetty",
    "protoweft-mcp",
    "protoweft-a2a",
    "protoweft-utcp",
    "protoweft-rap",
    "protoweft-discovery",
    "protoweft-discovery-embeddings",
    "protoweft-model-bge-small-en-v15",
    "protoweft-discovery-anthropic",
    "protoweft-discovery-openai",
    "protoweft-oauth",
    "protoweft-langchain4j",
    "protoweft-spring-ai",
    "protoweft-argi",
    "protoweft-spring-boot-starter",
    "examples:mcp-client-example",
    "examples:plain-java-gateway",
    "examples:spring-boot-example",
    "examples:langchain4j-example",
    "examples:spring-ai-example",
    "examples:argi-example",
    "evals:tool-discovery",
    "conformance:mcp-server",
    "conformance:mcp-client",
    "conformance:a2a-sut",
)

modules.filter { file(it.replace(':', '/')).isDirectory }.forEach { include(it) }

// Built against ARGI's unpublished neutral API (a pinned ARGI commit installed to mavenLocal);
// included only with -PargiSnapshot.
if (providers.gradleProperty("argiSnapshot").isPresent && file("protoweft-argi-neutral").isDirectory) {
    include("protoweft-argi-neutral")
}
