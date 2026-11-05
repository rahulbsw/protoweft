# Spring Boot

`agentweave-spring-boot-starter` makes AgentWeave idiomatic in a **Spring Boot 4** application
(built and tested on 4.1.2):

- `@AgentTool` methods of your beans are collected into one `LocalTools` bean.
- Client connectors declared in `application.yml` are created, started and merged into one
  `ToolRegistry` bean.
- Tools are served over MCP and UTCP, and an `AgentHandler` bean over A2A, on the application's
  servlet container, **only for the protocols you enable**.
- Observations are bridged to Micrometer, and connectors are reported through Actuator.

Nothing in the starter is needed outside Spring: it only wires the core and protocol modules.

## Dependencies

Add the starter plus each protocol module you use. The starter declares the protocol modules
`compileOnly`, so an MCP-only application doesn't pull in the A2A SDK.

```kotlin
plugins {
    id("org.springframework.boot") version "4.1.2"
}

dependencies {
    implementation(platform("io.agentweave:agentweave-bom:0.1.0-SNAPSHOT"))
    implementation("io.agentweave:agentweave-spring-boot-starter")
    implementation("io.agentweave:agentweave-mcp")     // only the protocols you use
    implementation("io.agentweave:agentweave-utcp")
    implementation("io.agentweave:agentweave-a2a")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")     // for the servers
    implementation("org.springframework.boot:spring-boot-starter-actuator")   // optional
}
```

If a protocol is configured but its module is missing, startup fails and names the artifact to
add. See [Getting started](../getting-started.md#build-agentweave-from-source) for building
AgentWeave into your local Maven repository.

## What gets configured

| Bean | Created when | Replace or disable |
|---|---|---|
| `LocalTools` | `agentweave.local-tools.enabled` (default) | defining your own `LocalTools` bean |
| `ToolRegistry` | always; holds the local tools under `agentweave.local-tools.name`, then every connector once started | defining your own `ToolRegistry` bean |
| `AgentWeaveConnectors` | always; the declared client connectors and their lifecycle | — |
| `McpToolServer.Http` | `agentweave.server.mcp.enabled=true`, servlet web app, `agentweave-mcp` present | defining your own bean |
| `UtcpManualProvider` | `agentweave.server.utcp.enabled=true`, servlet web app, `agentweave-utcp` present | defining your own bean |
| `A2aAgentServer` | `agentweave.server.a2a.enabled=true`, servlet web app, `agentweave-a2a` present, and an `AgentHandler` bean | defining your own bean |
| `InboundAuthenticator` | Spring Security present and no bean of your own: `SpringSecurityInboundAuthenticator` | defining your own bean |
| `MicrometerObservationHandler` | an `ObservationRegistry` bean exists and `agentweave.observations.enabled` (default) | defining your own bean |
| Health indicator `agentweave`, info contributor `agentweave` | Actuator present | beans named `agentweaveHealthIndicator` / `agentweaveInfoContributor`, or `management.health.agentweave.enabled` / `management.info.agentweave.enabled` |

Beans of yours that the starter uses: `@AgentTool` beans and `LocalTools.LocalTool` beans,
`InboundAuthenticator`, `CredentialProvider`, any number of `ObservationHandler`s, and an
`AgentHandler` with an optional `AgentDescriptor`.

## Local tools

Any singleton bean with `@AgentTool` methods contributes tools:

```java
@Component
public class WeatherTools {
    @AgentTool(description = "Current temperature in a city, in degrees Celsius")
    public double temperature(@ToolParam(description = "City name") String city) {
        return 21.5;
    }
}
```

The tools appear in the registry as `local.<tool>` and, when a server is enabled with the default
`expose=local`, are served under their plain names (`temperature`).

- Spring Boot's Gradle plugin and `spring-boot-starter-parent` compile with `-parameters`, so
  parameter names are available. Otherwise add `-parameters` yourself or use `@ToolParam(name = …)`.
- Beans are scanned by type without eager initialization; only beans with `@AgentTool` methods are
  instantiated.
- AOP proxies are unwrapped to their target, so **advice such as `@Transactional` is bypassed** on
  tool calls.
- A tool bean that needs the registry must inject `ObjectProvider<ToolRegistry>`, because the
  registry depends on the local tools:

```java
@Component
public class Planner {
    private final ObjectProvider<ToolRegistry> registry;

    public Planner(ObjectProvider<ToolRegistry> registry) {
        this.registry = registry;
    }

    @AgentTool(description = "Plans a trip using other tools")
    public String plan(String city) {
        return registry.getObject().invoke(ToolInvocation.of("local.temperature", Map.of("city", city))).text();
    }
}
```

## Client connectors

Declare connectors under their registry names; tools appear as `<name>.<tool>`, and an A2A agent as
`<name>.ask`.

```yaml
agentweave:
  mcp:
    clients:
      files:                                   # stdio: command + args + env
        command: npx
        args: [-y, "@modelcontextprotocol/server-filesystem", /tmp]
      tools:                                   # Streamable HTTP: url (+ endpoint, headers)
        url: https://tools.example.com
        headers: { X-Tenant: acme }
        request-timeout: 30s
  utcp:
    manuals:
      weather:                                 # url or file
        url: https://api.example.com/utcp
        variables: { API_KEY: "${WEATHER_API_KEY}" }   # ${API_KEY} in the manual
  a2a:
    agents:
      travel:
        url: https://travel.example.com
        timeout: 120s
```

UTCP `variables` are keyed by the name used in the manual; the starter namespaces them by the
manual name for you (`API_KEY` in manual `weather` becomes `weather_API_KEY`). Unset variables fall
back to the environment, namespaced.

A `CredentialProvider` bean is used by **every** connector built from properties; branch on the
protocol and target:

```java
@Bean
CredentialProvider credentials() {
    return (protocol, target) -> protocol.equals(ProtocolId.MCP) && target.startsWith("https://tools.example.com")
            ? List.of(new Credential.BearerToken(System.getenv("TOOLS_TOKEN")))
            : List.of();
}
```

### Lifecycle

- Connectors are **built** when the `AgentWeaveConnectors` bean is created, so bad URLs fail early.
- They are **started after the web server**, so an application can consume its own endpoints, and
  each is added to the `ToolRegistry` once it has started.
- They are **closed** after the web server has drained during graceful shutdown.

Remote tools are therefore not in the registry until the context has refreshed. Use them from
`ApplicationReadyEvent` onwards, not from `@PostConstruct`:

```java
@Component
public class Startup {
    private final ToolRegistry registry;
    private final AgentWeaveConnectors connectors;

    public Startup(ToolRegistry registry, AgentWeaveConnectors connectors) {
        this.registry = registry;
        this.connectors = connectors;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        registry.listTools().forEach(t -> System.out.println(t.name()));
        // protocol-specific calls through the concrete connector type
        AgentReply reply = connectors.connector("travel", A2aAgentConnector.class)
                .send(AgentMessage.user("Find a flight to Berlin"));
    }
}
```

`AgentWeaveConnectors` also offers `connector(name)`, `status()` (each connector's `PENDING`, `UP`,
`FAILED` or `CLOSED` state) and `compliance()`.

### Failure policy

- By default, a connector that fails to start **fails application startup**:
  `IllegalStateException: AgentWeave <protocol> connector '<name>' failed to start: …`.
- With `agentweave.fail-fast=false`, the failure is logged at WARN, the connector is closed and
  left out of the registry (so it can't break listing), and health reports it `FAILED` (DOWN).
- There are no retries, and connectors are never restarted.

## Serving tools and agents

Every server is off until you enable it:

```yaml
spring:
  application:
    name: weather-agent

agentweave:
  server:
    mcp:
      enabled: true                 # Streamable HTTP at /mcp
      server-name: weather
    utcp:
      enabled: true                 # GET /utcp, POST /utcp/tools/<tool>
      api-key-header: Authorization # announce the header UTCP clients must send...
      api-key-variable: WEATHER_TOKEN   # ...from their WEATHER_TOKEN variable
    a2a:
      enabled: true                 # card at /.well-known/agent-card.json, JSON-RPC at /a2a
      public-url: https://weather.example.com
      description: Answers questions about the weather
      skills:
        - id: weather
          name: Weather
          description: Current temperature in a city
          tags: [weather]
          examples: ["What is the weather in Paris?"]
```

The A2A server needs an `AgentHandler` bean. The card's name, description, version and skills come
from `agentweave.server.a2a.*` (the name defaults to `spring.application.name`), unless you define
an `AgentDescriptor` bean.

### What a tool server publishes

`expose` chooses the tools:

- `local` (default): only the application's own `@AgentTool`s, under their plain names.
- `registry`: the whole registry, local and remote, as `<source>.<tool>`. Re-serving remote tools
  turns the application into a proxy that spends its connectors' credentials on behalf of its own
  callers, so this must be an explicit choice.

With UTCP, `expose=registry` is always current, because the manual is built per request. MCP reads
its tool list once, so with `expose=registry` the MCP endpoint is deferred: until the connectors
have started it answers **503** with `Retry-After: 1`, and then it is built over the full registry.

### Public URLs

- **A2A:** set `public-url` for any real deployment. The card's URL is resolved once and can't
  follow requests. Without it, the card advertises
  `http://localhost:<local.server.port><context-path>` and a WARN is logged; that only works for
  local clients and tests.
- **UTCP:** `base-url` is optional. When unset, tool URLs are derived from each request (scheme,
  host, port, context path). Behind a proxy that's correct only with
  `server.forward-headers-strategy` set; otherwise set `base-url`.

### Mounting and filter order

Each endpoint becomes a `ServletRegistrationBean` with an `InboundAuthFilter` registered on the
same URL patterns (REQUEST dispatch), async where the endpoint needs it. Their order is
`agentweave.server.auth-filter-order` (default `0`), which puts them after Spring Security's filter
chain (`spring.security.filter.order`, `-100`).

## Security

Without Spring Security and without an `InboundAuthenticator` bean, the endpoints admit everyone
(`InboundAuthenticator.ALLOW_ALL`). Choose one of:

**An `InboundAuthenticator` bean.** It protects every AgentWeave endpoint and names the caller.
The [Spring Boot example](../examples.md#spring-boot) uses a bearer-token authenticator that leaves
the A2A card public.

**Spring Security.** Protect the protocol paths as usual, and make CSRF protection ignore them:
protocol clients don't send CSRF tokens.

```java
@Configuration
public class SecurityConfig {
    private static final String[] PROTOCOL_PATHS = {"/mcp", "/a2a", "/utcp", "/utcp/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/.well-known/agent-card.json").permitAll()
                        .requestMatchers(PROTOCOL_PATHS).authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers(PROTOCOL_PATHS))
                .build();
    }
}
```

With Spring Security present and no `InboundAuthenticator` bean of your own, the starter installs
`SpringSecurityInboundAuthenticator`. It never rejects (Spring Security's rules do that), and it
names the caller after Spring Security's `Authentication`, with the sorted authority names in the
`authorities` attribute. So an `@AgentTool` method with a `CallerIdentity` parameter, or an
`AgentHandler` reading `request.caller()`, sees the Spring user. Defining your own
`InboundAuthenticator` bean replaces it.

## Micrometer

When an `ObservationRegistry` bean exists (for example with Actuator), each AgentWeave operation
becomes a Micrometer observation:

| | |
|---|---|
| Name | `agentweave.operation` |
| Contextual name | `<protocol> <operation>`, for example `mcp tools/call` |
| Low-cardinality keys | `agentweave.protocol` (`mcp`, `a2a`, `utcp`), `agentweave.operation` (`tools/call`, `SendMessage`, …) |
| High-cardinality key | `agentweave.target` (tool name, URL or task id) |

The bridge covers the client connectors built from properties and the MCP and A2A servers. Every
`ObservationHandler` bean receives every event, and one that fails doesn't affect the others. Turn
the bridge off with `agentweave.observations.enabled=false`.

## Actuator

- **Health** `agentweave` reports connector lifecycle state; it doesn't probe peers on each check.
  It is DOWN if any connector failed, OUT_OF_SERVICE while any is pending, and UP otherwise. Each
  connector's protocol, state and error appear as details.
- **Info** `agentweave.connectors.<name>` carries each client connector's `ComplianceReport`
  (`protocol`, `sdkName`, `sdkVersion`, `wireRevisions`, `negotiatedRevision`, `supported`,
  `unsupported`). Server reports aren't included.

## Property reference

All properties are validated at startup. Errors are reported as
`InvalidConfigurationPropertyValueException`, so Spring Boot's failure analyzer names the offending
key. Durations use Spring's format (`30s`, `2m`).

### General

| Property | Type | Default | Description |
|---|---|---|---|
| `agentweave.fail-fast` | boolean | `true` | Whether a connector that fails to start fails application startup. |
| `agentweave.local-tools.enabled` | boolean | `true` | Collect `@AgentTool` methods of beans into one `LocalTools` bean. |
| `agentweave.local-tools.name` | string | `local` | Registry name of the local tools (`<name>.<tool>`). |
| `agentweave.observations.enabled` | boolean | `true` | Record operations as Micrometer observations when an `ObservationRegistry` exists. |
| `management.health.agentweave.enabled` | boolean | `true` | The connectors health check. |
| `management.info.agentweave.enabled` | boolean | `management.info.defaults.enabled` | The compliance info contributor. |

### MCP clients: `agentweave.mcp.clients.<name>.*`

Set exactly one of `command` (stdio) and `url` (Streamable HTTP), and only the options of that kind.

| Key | Type | Default | Description |
|---|---|---|---|
| `command` | string | — | Executable of an stdio MCP server, launched as a subprocess. |
| `args` | list | `[]` | Arguments of the stdio command. |
| `env` | map | `{}` | Environment variables of the server process. |
| `url` | string | — | Base URL of a Streamable HTTP server, for example `https://mcp.example.com`. |
| `endpoint` | string | `/mcp` | Endpoint path, resolved against `url`. |
| `headers` | map | `{}` | Static headers sent with every HTTP request. |
| `request-timeout` | duration | 20 s (connector default) | Timeout for initialization and each request. |

### UTCP manuals: `agentweave.utcp.manuals.<name>.*`

Set exactly one of `url` and `file`. `<name>` is also the UTCP manual name.

| Key | Type | Default | Description |
|---|---|---|---|
| `url` | string | — | URL of the manual (`https`, or `http` on loopback). |
| `file` | path | — | A manual JSON file. |
| `headers` | map | `{}` | Static headers on the discovery request. |
| `variables` | map | `{}` | Variables referenced as `${NAME}`, keyed by `NAME`; namespaced by the manual name automatically. |
| `discovery-timeout` | duration | 10 s (connector default) | Manual discovery timeout. |
| `call-timeout` | duration | 30 s (connector default) | Tool call timeout. |

### A2A agents: `agentweave.a2a.agents.<name>.*`

| Key | Type | Default | Description |
|---|---|---|---|
| `url` | string | — (required) | Base URL of the agent; its card is read from `<url>/.well-known/agent-card.json`. |
| `timeout` | duration | 60 s (connector default) | Connect timeout and the longest a call waits for the task. |
| `streaming` | boolean | `true` | Whether calls may stream when the agent supports it. |

### Servers: `agentweave.server.*`

| Property | Type | Default | Description |
|---|---|---|---|
| `agentweave.server.auth-filter-order` | int | `0` | Order of the inbound-authentication filters; the default runs them after Spring Security (`-100`). |
| `agentweave.server.mcp.enabled` | boolean | `false` | Serve tools over MCP (Streamable HTTP). |
| `agentweave.server.mcp.path` | string | `/mcp` | Servlet path of the MCP endpoint. |
| `agentweave.server.mcp.expose` | `local` \| `registry` | `local` | Which tools to serve. |
| `agentweave.server.mcp.server-name` | string | `agentweave` | `serverInfo` name. |
| `agentweave.server.mcp.server-version` | string | `0.1.0` | `serverInfo` version. |
| `agentweave.server.mcp.allowed-origins` | list | `[]` | Browser origins allowed to call the endpoint; other origins get 403. |
| `agentweave.server.mcp.request-timeout` | duration | 60 s (server default) | Longest a `tools/call` may run. |
| `agentweave.server.utcp.enabled` | boolean | `false` | Publish tools as a UTCP manual. |
| `agentweave.server.utcp.base-url` | string | from each request | Public base URL used in tool URLs. |
| `agentweave.server.utcp.expose` | `local` \| `registry` | `local` | Which tools to publish. |
| `agentweave.server.utcp.manual-version` | string | `1.0.0` | `manual_version` of the manual. |
| `agentweave.server.utcp.api-key-header` | string | — | Header clients must send on tool calls, published as `api_key` auth. Requires `api-key-variable`. |
| `agentweave.server.utcp.api-key-variable` | string | — | Client-side UTCP variable holding that header's value. |
| `agentweave.server.a2a.enabled` | boolean | `false` | Serve the `AgentHandler` bean over A2A. |
| `agentweave.server.a2a.public-url` | string | `http://localhost:<port><context-path>` | Externally reachable base URL advertised in the card. |
| `agentweave.server.a2a.path` | string | `/a2a` | Servlet path of the JSON-RPC endpoint. |
| `agentweave.server.a2a.name` | string | `spring.application.name` | Agent name in the card. Ignored with an `AgentDescriptor` bean. |
| `agentweave.server.a2a.description` | string | `""` | Agent description. |
| `agentweave.server.a2a.version` | string | `1.0.0` | Agent version. |
| `agentweave.server.a2a.skills[]` | list | `[]` | Skills: `id`, `name` (default: the id), `description`, `tags`, `examples`. |
| `agentweave.server.a2a.streaming` | boolean | `true` | Offer `SendStreamingMessage` and `SubscribeToTask`. |
| `agentweave.server.a2a.push-notifications` | boolean | `true` | Offer push notifications. |
| `agentweave.server.a2a.agent-timeout` | duration | 30 s (server default) | How long a blocking `SendMessage` waits for the handler. |
| `agentweave.server.a2a.from-chat-client` | boolean | `false` | With `agentweave-spring-ai` and no `AgentHandler` bean, serve the application's Spring AI `ChatClient` as the agent. See [Spring AI](spring-ai.md#spring-boot). |

### Tool discovery: `agentweave.discovery.*`

Needs `agentweave-discovery` on the classpath. See [Tool discovery: Spring Boot](../discovery/index.md#spring-boot).

| Property | Type | Default | Description |
|---|---|---|---|
| `agentweave.discovery.enabled` | boolean | `false` | Build a `CapabilityCatalog` bean over the tool registry. |
| `agentweave.discovery.fallback` | `progressive` \| `all-schemas` \| `none` | `progressive` | What a low-confidence search does. |
| `agentweave.discovery.sources.<name>.required-scopes` | list | `[]` | Scopes a caller needs to see the source's tools (matched against the caller's `scopes`). |
| `agentweave.discovery.expose-mcp` | boolean | `false` | Serve the discovery meta-tools over MCP. |
| `agentweave.discovery.mcp-path` | string | `/mcp-discovery` | Path of that MCP endpoint. |

### Spring AI: `agentweave.spring-ai.*`

Needs `agentweave-spring-ai` on the classpath; everything is off by default. See
[Spring AI: Spring Boot](spring-ai.md#spring-boot) for why.

| Property | Type | Default | Description |
|---|---|---|---|
| `agentweave.spring-ai.tool-callbacks.enabled` | boolean | `false` | Register an `AgentWeaveToolCallbacks` bean and add it to the auto-configured `ChatClient.Builder`. |
| `agentweave.spring-ai.tool-callbacks.expose` | `local` \| `registry` | `local` | Expose only the `@AgentTool` methods, or every registry tool. |
| `agentweave.spring-ai.tool-search.enabled` | boolean | `false` | Add Spring AI's tool-search advisor backed by the capability catalog instead (needs `agentweave.discovery.enabled`). |

### Validation rules

- An MCP client sets exactly one of `command` and `url`, and never options of the other kind.
- A UTCP manual sets exactly one of `url` and `file`.
- An A2A agent has a `url`.
- Names match `[A-Za-z0-9_-]+` and are unique across protocols and the local tools.
- A configured protocol has its module on the classpath.
- Server paths are exact and don't collide.
- `api-key-header` and `api-key-variable` are set together, and URLs are absolute `http(s)`.

## Not supported

- Spring Boot 3.x, and reactive (WebFlux) hosting.
- MCP stdio serving inside Spring.
- Per-connector fail-fast, retries and reconnection.
- Connectors other than those built from properties. Register them in the `ToolRegistry` bean
  yourself and manage their lifecycle.
- UTCP dotenv files, inline manuals and manual `auth` through properties.
- A2A extended cards, input/output modes and custom executors through properties. Define an
  `A2aAgentServer` bean for those.
- Server compliance reports in Actuator info.
