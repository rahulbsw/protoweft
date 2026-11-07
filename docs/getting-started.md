# Getting started

This page adds Protoweft to a project, calls a tool on an MCP server, and publishes a tool of your
own over MCP and UTCP.

## Requirements

- **Java 17 or newer.** Protoweft is compiled for Java 17.
- **Gradle or Maven.** The examples use Gradle's Kotlin DSL; Maven coordinates are the same.
- **An SLF4J binding** if you want to see the protocol SDKs' logs, for example
  `org.slf4j:slf4j-simple`, which logs to stderr.
- **Node.js**, only to run the reference MCP servers used in some examples (`npx …`).

## Build Protoweft from source

!!! warning "Not yet on Maven Central"
    Protoweft `0.1.0-SNAPSHOT` isn't published to Maven Central. Build it from source and install
    it into your local Maven repository (`~/.m2/repository`):

```bash
./gradlew publishToMavenLocal
```

Run this from a clone of the Protoweft repository. It publishes every library module and the BOM
with group `io.github.rahulbsw` and version `0.1.0-SNAPSHOT`. The examples and `protoweft-argi-neutral`
aren't published.

## Add the dependencies

Import the BOM so every Protoweft module has the same version, then add only the modules you use.

=== "Gradle (Kotlin DSL)"

    ```kotlin
    repositories {
        mavenLocal()      // Protoweft, until it is published
        mavenCentral()    // the protocol SDKs and everything else
    }

    dependencies {
        implementation(platform("io.github.rahulbsw:protoweft-bom:0.1.0-SNAPSHOT"))
        implementation("io.github.rahulbsw:protoweft-mcp")
        implementation("io.github.rahulbsw:protoweft-utcp")
        implementation("io.github.rahulbsw:protoweft-server-jetty")   // to host servers in plain Java
        runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")   // lets @AgentTool read parameter names
    }
    ```

=== "Maven"

    ```xml
    <properties>
      <maven.compiler.release>17</maven.compiler.release>
      <!-- lets @AgentTool read parameter names -->
      <maven.compiler.parameters>true</maven.compiler.parameters>
    </properties>

    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>io.github.rahulbsw</groupId>
          <artifactId>protoweft-bom</artifactId>
          <version>0.1.0-SNAPSHOT</version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
      </dependencies>
    </dependencyManagement>

    <dependencies>
      <dependency>
        <groupId>io.github.rahulbsw</groupId>
        <artifactId>protoweft-mcp</artifactId>
      </dependency>
      <dependency>
        <groupId>io.github.rahulbsw</groupId>
        <artifactId>protoweft-utcp</artifactId>
      </dependency>
      <dependency>
        <groupId>io.github.rahulbsw</groupId>
        <artifactId>protoweft-server-jetty</artifactId>
      </dependency>
    </dependencies>
    ```

    Maven reads `~/.m2/repository` by default, so no extra repository is needed.

### Modules

| Artifact | Use it for |
|---|---|
| `protoweft-core` | The protocol-neutral API. Every other module depends on it; you rarely add it yourself. |
| `protoweft-mcp` | MCP client and tool server (stdio, Streamable HTTP). |
| `protoweft-a2a` | A2A client and agent server (JSON-RPC binding). |
| `protoweft-utcp` | UTCP client and manual provider. |
| `protoweft-rap` | [RAP](protocols/rap.md) (Reactive Agent Protocol, Hydro Infinity) client and tool server, with subscriptions, prompts and a durable store. |
| `protoweft-server` | The servlet hosting contract the protocol servers use. Comes with each protocol module. |
| `protoweft-server-jetty` | An embedded Jetty 12 host for servers in plain Java. |
| `protoweft-oauth` | [OAuth 2.1](integrations/oauth.md) for MCP authorization: a token-obtaining client for the MCP connector, and a bearer-token resource server for any endpoint. |
| `protoweft-discovery` | [Tool discovery](discovery/index.md): a searchable capability catalog, for when there are too many tools for one prompt. |
| `protoweft-discovery-embeddings` | Semantic ranking for discovery with an offline model (ONNX Runtime; the model comes in `protoweft-model-bge-small-en-v15`). |
| `protoweft-discovery-anthropic`, `protoweft-discovery-openai` | Native tool-search request parts for the Anthropic and OpenAI Java SDKs. |
| `protoweft-langchain4j` | [LangChain4j](integrations/langchain4j.md) adapter: Protoweft tools in, agents and `@Tool`s out. |
| `protoweft-spring-ai` | [Spring AI](integrations/spring-ai.md) adapter: tools in, catalog tool search, `ChatClient` agents and `@Tool`s out. |
| `protoweft-argi` | [ARGI](integrations/argi.md) `2.1.0-RC1` adapter: tools in, catalog tool selection, agents over A2A with approvals. |
| `protoweft-spring-boot-starter` | Spring Boot 4 auto-configuration. Add the protocol modules you use next to it. |
| `protoweft-bom` | Version alignment. |

`protoweft-argi-neutral`, the adapter for ARGI's Spring-free API, is in the repository but isn't
published: it builds only against a pinned ARGI snapshot (see [ARGI](integrations/argi.md#protoweft-argi-neutral-unpublished)).

## Call a tool on an MCP server

`McpClientConnector.stdio(...)` launches an MCP server as a subprocess and talks to it over
stdin/stdout. Only the builder names the protocol; the rest of the code uses the neutral
`ToolConnector` API.

```java
import io.github.rahulbsw.protoweft.core.tool.*;
import io.github.rahulbsw.protoweft.mcp.McpClientConnector;

import java.time.Duration;
import java.util.Map;

public class FirstClient {
    public static void main(String[] args) {
        try (ToolConnector mcp = McpClientConnector
                .stdio("npx", "-y", "@modelcontextprotocol/server-everything")
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {
            mcp.start();                     // launches the server and negotiates the revision
            for (ToolSpec tool : mcp.listTools()) {
                System.out.println(tool.name() + ": " + tool.description());
            }
            ToolOutcome out = mcp.invoke(ToolInvocation.of("echo", Map.of("message", "hi")));
            System.out.println(out.error() ? "tool failed: " + out.text() : out.text());
            System.out.println("negotiated " + mcp.compliance().negotiated().orElse("-"));
        }
    }
}
```

Things to know:

- `start()` is explicit. Calls made before it fail with `ErrorCategory.CONFIGURATION`.
- A tool that runs and fails returns `out.error() == true`. An `ProtoweftException` means the
  call couldn't be made. See [Errors and observability](concepts/errors-and-observability.md).
- `close()` (here, the end of the `try` block) shuts the session down and stops the subprocess.
- For a remote server, use `McpClientConnector.http("https://tools.example.com")`; the endpoint
  path defaults to `/mcp`. See the [MCP guide](protocols/mcp.md).

No Node.js? The [MCP client example](examples.md#mcp-client) launches a bundled Java MCP server
instead.

## Publish your first tools

Annotate a method with `@AgentTool`, collect it with `LocalTools`, and hand it to the protocol
servers. `ProtoweftServer` hosts them in embedded Jetty.

```java
import io.github.rahulbsw.protoweft.core.tool.AgentTool;
import io.github.rahulbsw.protoweft.core.tool.LocalTools;
import io.github.rahulbsw.protoweft.core.tool.ToolParam;
import io.github.rahulbsw.protoweft.mcp.McpToolServer;
import io.github.rahulbsw.protoweft.server.jetty.ProtoweftServer;
import io.github.rahulbsw.protoweft.utcp.UtcpManualProvider;

public class FirstServer {

    public static class Greeter {
        @AgentTool(description = "Greets someone by name")
        public String greet(@ToolParam(description = "Who to greet") String name) {
            return "Hello, " + name + "!";
        }
    }

    public static void main(String[] args) throws InterruptedException {
        LocalTools tools = LocalTools.builder().scan(new Greeter()).build();

        try (ProtoweftServer server = ProtoweftServer.builder()
                .port(8080)                                       // binds localhost by default
                .endpoints(McpToolServer.http(tools).serverInfo("greeter", "1.0.0"),
                           UtcpManualProvider.builder(tools).build())
                .start()) {
            System.out.println("MCP at " + server.baseUrl() + "/mcp, UTCP manual at " + server.baseUrl() + "/utcp");
            server.join();
        }
    }
}
```

The same `greet` tool is now:

- an MCP tool at `POST http://localhost:8080/mcp` (Streamable HTTP), and
- a UTCP tool listed in the manual at `GET http://localhost:8080/utcp` and called at
  `POST http://localhost:8080/utcp/tools/greet`.

Check it with curl:

```bash
curl -s localhost:8080/utcp
curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"Ada"}' localhost:8080/utcp/tools/greet
# Hello, Ada!
```

!!! warning "No authentication by default"
    `ProtoweftServer` admits every request until you set `.authenticator(...)`. It binds
    `localhost` by default; set an authenticator before binding another interface. See
    [Security and credentials](concepts/security.md).

## Next steps

- [Tools and the registry](concepts/tools.md): schemas, argument binding, result mapping, and
  combining sources.
- [Agents](concepts/agents.md): serve and call A2A agents.
- [Spring Boot](integrations/spring-boot.md): the same with auto-configuration.
- [Spring AI](integrations/spring-ai.md), [LangChain4j](integrations/langchain4j.md) and
  [ARGI](integrations/argi.md): Protoweft tools in your agent framework, and its agents over A2A.
- [Examples](examples.md): complete runnable programs.
