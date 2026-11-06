# 5-minute quickstart

Three ways to go from nothing to a working Protoweft app. Each needs only JDK 17 or newer.

!!! note "Before the first release"
    These paths use the published `io.github.rahulbsw` artifacts on Maven Central. Until the first
    release, build from source first with `./gradlew publishToMavenLocal`, then add `mavenLocal()`
    to the template's repositories (or pass `--repos local=file://$HOME/.m2/repository` to JBang).

## 1. Zero setup: JBang (30 seconds)

[JBang](https://www.jbang.dev) runs a single Java file with its dependencies, with no project
needed:

```bash
jbang protoweft-demo@rahulbsw/protoweft Berlin
```

```
mcp.forecast  -> Sunny, 21.5 °C in Berlin
utcp.forecast -> Sunny, 21.5 °C in Berlin
a2a.ask       -> Sunny, 21.5 °C in Berlin
```

The demo (`jbang/ProtoweftDemo.java`) serves one `@AgentTool` method over MCP, UTCP and A2A from
an embedded server, then calls it back through all three protocols with a single `ToolRegistry`.

## 2. Spring Boot: serve your tools over MCP

Copy `templates/spring-boot-mcp-server`, then run it:

```bash
cp -R templates/spring-boot-mcp-server my-mcp-server && cd my-mcp-server
gradle bootRun          # or add a Gradle wrapper with: gradle wrapper
```

Every `@AgentTool` method on a Spring bean is now an MCP tool at `http://localhost:8080/mcp`.
Point any MCP client at it, for example the MCP Inspector (`npx @modelcontextprotocol/inspector`),
using the Streamable HTTP transport and that URL. The template's own test does the same with
Protoweft's MCP client.

To serve the same tools as a UTCP manual, set `protoweft.server.utcp.enabled: true`. To run an
A2A agent, set `protoweft.server.a2a.enabled: true` and define an `AgentHandler` bean. Before
exposing anything beyond localhost, add authentication; see [Security](concepts/security.md).

## 3. Plain Java: call tools anywhere

Copy `templates/plain-java-client`. It connects to whatever you pass and lists every tool
through one registry:

```bash
cp -R templates/plain-java-client my-client && cd my-client
gradle run --args="mcp-stdio=npx,-y,@modelcontextprotocol/server-everything call=mcp.echo message=hello"
# mcp.echo -> Echo: hello
gradle run --args="mcp=http://localhost:8080 utcp=https://api.example.com/utcp a2a=https://agent.example.com"
```

## Next

- [Getting started](getting-started.md) has the dependency coordinates and the BOM.
- [Tools and the registry](concepts/tools.md) covers `@AgentTool`, schemas and namespacing.
- Integrations: [Spring Boot](integrations/spring-boot.md), [LangChain4j](integrations/langchain4j.md),
  [Spring AI](integrations/spring-ai.md), [ARGI](integrations/argi.md), [OAuth](integrations/oauth.md).
- [Tool discovery](discovery/index.md) is for when you have too many tools for one prompt.
