rootProject.name = "agentweave"

// Every module of the build. A module is included once its directory exists, so modules can
// be added without editing this file again.
val modules = listOf(
    "agentweave-bom",
    "agentweave-core",
    "agentweave-server",
    "agentweave-server-jetty",
    "agentweave-mcp",
    "agentweave-a2a",
    "agentweave-utcp",
    "agentweave-discovery",
    "agentweave-discovery-embeddings",
    "agentweave-discovery-anthropic",
    "agentweave-discovery-openai",
    "agentweave-oauth",
    "agentweave-langchain4j",
    "agentweave-spring-ai",
    "agentweave-argi",
    "agentweave-spring-boot-starter",
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
if (providers.gradleProperty("argiSnapshot").isPresent && file("agentweave-argi-neutral").isDirectory) {
    include("agentweave-argi-neutral")
}
