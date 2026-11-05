# Getting started

This page adds AgentWeave to a project, calls a tool on an MCP server, and publishes a tool of your
own over MCP and UTCP.

## Requirements

- **Java 17 or newer.** AgentWeave is compiled for Java 17.
- **Gradle or Maven.** The examples use Gradle's Kotlin DSL; Maven coordinates are the same.
- **An SLF4J binding** if you want to see the protocol SDKs' logs, for example
  `org.slf4j:slf4j-simple`, which logs to stderr.
- **Node.js**, only to run the reference MCP servers used in some examples (`npx …`).

## Build AgentWeave from source

!!! warning "Not yet on Maven Central"
    AgentWeave `0.1.0-SNAPSHOT` isn't published to Maven Central. Build it from source and install
    it into your local Maven repository (`~/.m2/repository`):

```bash
./gradlew publishToMavenLocal
```

Run this from a clone of the AgentWeave repository. It publishes every library module and the BOM
with group `io.agentweave` and version `0.1.0-SNAPSHOT`. The examples aren't published.

## Add the dependencies

Import the BOM so every AgentWeave module has the same version, then add only the modules you use.

=== "Gradle (Kotlin DSL)"

    ```kotlin
    repositories {
        mavenLocal()      // AgentWeave, until it is published
        mavenCentral()    // the protocol SDKs and everything else
    }

    dependencies {
        implementation(platform("io.agentweave:agentweave-bom:0.1.0-SNAPSHOT"))
        implementation("io.agentweave:agentweave-mcp")
        implementation("io.agentweave:agentweave-utcp")
        implementation("io.agentweave:agentweave-server-jetty")   // to host servers in plain Java
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
          <groupId>io.agentweave</groupId>
          <artifactId>agentweave-bom</artifactId>
          <version>0.1.0-SNAPSHOT</version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
      </dependencies>
    </dependencyManagement>

    <dependencies>
      <dependency>
        <groupId>io.agentweave</groupId>
        <artifactId>agentweave-mcp</artifactId>
      </dependency>
      <dependency>
        <groupId>io.agentweave</groupId>
        <artifactId>agentweave-utcp</artifactId>
      </dependency>
      <dependency>
        <groupId>io.agentweave</groupId>
        <artifactId>agentweave-server-jetty</artifactId>
      </dependency>
    </dependencies>
    ```

    Maven reads `~/.m2/repository` by default, so no extra repository is needed.

### Modules

| Artifact | Use it for |
|---|---|
| `agentweave-core` | The protocol-neutral API. Every other module depends on it; you rarely add it yourself. |
| `agentweave-mcp` | MCP client and tool server (stdio, Streamable HTTP). |
| `agentweave-a2a` | A2A client and agent server (JSON-RPC binding). |
| `agentweave-utcp` | UTCP client and manual provider. |
| `agentweave-server` | The servlet hosting contract the protocol servers use. Comes with each protocol module. |
| `agentweave-server-jetty` | An embedded Jetty 12 host for servers in plain Java. |
| `agentweave-langchain4j` | LangChain4j adapter: AgentWeave tools in, agents and `@Tool`s out. |
| `agentweave-spring-boot-starter` | Spring Boot 4 auto-configuration. Add the protocol modules you use next to it. |
| `agentweave-bom` | Version alignment. |

## Call a tool on an MCP server

`McpClientConnector.stdio(...)` launches an MCP server as a subprocess and talks to it over
stdin/stdout. Only the builder names the protocol; the rest of the code uses the neutral
`ToolConnector` API.

```java
import io.agentweave.core.tool.*;
import io.agentweave.mcp.McpClientConnector;

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
- A tool that runs and fails returns `out.error() == true`. An `AgentWeaveException` means the
  call couldn't be made. See [Errors and observability](concepts/errors-and-observability.md).
- `close()` (here, the end of the `try` block) shuts the session down and stops the subprocess.
- For a remote server, use `McpClientConnector.http("https://tools.example.com")`; the endpoint
  path defaults to `/mcp`. See the [MCP guide](protocols/mcp.md).

No Node.js? The [MCP client example](examples.md#mcp-client) launches a bundled Java MCP server
instead.

## Publish your first tools

Annotate a method with `@AgentTool`, collect it with `LocalTools`, and hand it to the protocol
servers. `AgentWeaveServer` hosts them in embedded Jetty.

```java
import io.agentweave.core.tool.AgentTool;
import io.agentweave.core.tool.LocalTools;
import io.agentweave.core.tool.ToolParam;
import io.agentweave.mcp.McpToolServer;
import io.agentweave.server.jetty.AgentWeaveServer;
import io.agentweave.utcp.UtcpManualProvider;

public class FirstServer {

    public static class Greeter {
        @AgentTool(description = "Greets someone by name")
        public String greet(@ToolParam(description = "Who to greet") String name) {
            return "Hello, " + name + "!";
        }
    }

    public static void main(String[] args) throws InterruptedException {
        LocalTools tools = LocalTools.builder().scan(new Greeter()).build();

        try (AgentWeaveServer server = AgentWeaveServer.builder()
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
    `AgentWeaveServer` admits every request until you set `.authenticator(...)`. It binds
    `localhost` by default; set an authenticator before binding another interface. See
    [Security and credentials](concepts/security.md).

## Next steps

- [Tools and the registry](concepts/tools.md): schemas, argument binding, result mapping, and
  combining sources.
- [Agents](concepts/agents.md): serve and call A2A agents.
- [Spring Boot](integrations/spring-boot.md): the same with auto-configuration.
- [Examples](examples.md): complete runnable programs.
