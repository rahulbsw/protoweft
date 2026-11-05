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
    "agentweave-langchain4j",
    "agentweave-spring-boot-starter",
    "examples:mcp-client-example",
    "examples:plain-java-gateway",
    "examples:spring-boot-example",
    "examples:langchain4j-example",
    "evals:tool-discovery",
)

modules.filter { file(it.replace(':', '/')).isDirectory }.forEach { include(it) }
