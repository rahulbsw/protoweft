---
id: agentweave-spring-boot-starter
type: module-design
status: draft
title: agentweave-spring-boot-starter — Spring Boot 4 auto-configuration
parent: agentweave-architecture
---

# agentweave-spring-boot-starter

## Responsibility

Makes AgentWeave idiomatic in a **Spring Boot 4.x** application (built and tested on 4.1.2):

- Discovers `@AgentTool` beans and combines them into one `LocalTools` bean.
- Creates the client connectors declared in `application.yml` and merges them into one
  `ToolRegistry` bean.
- Serves tools over MCP, UTCP and A2A on the application's servlet container, only for the
  protocols that are opted in.
- Bridges observations to Micrometer and reports connectors through Actuator.

Nothing here is needed outside Spring. Every capability comes from `agentweave-core`,
`agentweave-server` and the protocol modules, and nothing depends on this module.

## Boundary

- Dependencies:
  - `api`: `agentweave-core`, `agentweave-server`.
  - `compileOnly`: Spring Boot (`spring-boot-autoconfigure`, `-health`, `-actuator`,
    `-actuator-autoconfigure`), the servlet API, Micrometer observation, `spring-security-core`
    and **the protocol modules** (`agentweave-mcp`, `agentweave-utcp`, `agentweave-a2a`).
- Applications add the starter plus each protocol module they use, for example
  `implementation("io.agentweave:agentweave-mcp")`. Each auto-configuration is guarded by
  `@ConditionalOnClass` on its protocol.
- Auto-configurations, registered in `META-INF/spring/…AutoConfiguration.imports`:
  - `AgentWeaveAutoConfiguration`: `LocalTools`, `ToolRegistry`, `AgentWeaveConnectors`.
  - `AgentWeaveServerAutoConfiguration`: servlet web applications only. It provides
    `McpToolServer.Http`, `UtcpManualProvider`, `A2aAgentServer`, and one
    `AgentWeaveEndpointRegistrar` per enabled protocol.
  - `AgentWeaveMicrometerAutoConfiguration`: `MicrometerObservationHandler`.
  - `AgentWeaveActuatorAutoConfiguration`: `AgentWeaveHealthIndicator` (`agentweave`) and
    `AgentWeaveInfoContributor` (`agentweave`).
- Every bean the starter creates is `@ConditionalOnMissingBean`, so an application bean of the
  same type replaces it. Application beans the starter uses:
  - `@AgentTool` beans and `LocalTools.LocalTool` beans.
  - `InboundAuthenticator`.
  - `CredentialProvider`.
  - `ObservationHandler` (any number).
  - `AgentHandler`, with an optional `AgentDescriptor`.

## Properties

| Prefix | Keys |
|---|---|
| `agentweave` | `fail-fast` (true) · `local-tools.enabled` (true) · `local-tools.name` (`local`) · `observations.enabled` (true) |
| `agentweave.mcp.clients.<name>` | `command` + `args` + `env` (stdio) **or** `url` + `endpoint` (`/mcp`) + `headers` (HTTP) · `request-timeout` |
| `agentweave.utcp.manuals.<name>` | `url` **or** `file` · `headers` · `variables` · `discovery-timeout` · `call-timeout` |
| `agentweave.a2a.agents.<name>` | `url` · `timeout` · `streaming` (true) |
| `agentweave.server` | `auth-filter-order` (0) |
| `agentweave.server.mcp` | `enabled` · `path` (`/mcp`) · `expose` (`local`\|`registry`) · `server-name` · `server-version` · `allowed-origins` · `request-timeout` |
| `agentweave.server.utcp` | `enabled` · `base-url` · `expose` · `manual-version` · `api-key-header` + `api-key-variable` |
| `agentweave.server.a2a` | `enabled` · `public-url` · `path` (`/a2a`) · `name` · `description` · `version` · `skills[]` · `streaming` · `push-notifications` · `agent-timeout` |

`<name>` is the registry source name, so tools appear as `<name>.<tool>`; an A2A agent appears as
`<name>.ask`. The configuration processor generates metadata, including the
`management.{health,info}.agentweave.enabled` switches.

Properties are validated at startup, and errors are reported as
`InvalidConfigurationPropertyValueException`, so Boot's failure analyzer names the offending key.
The checks:
- An MCP client sets exactly one of `command` and `url`, and never uses options that belong to
  the other kind.
- A UTCP manual sets exactly one of `url` and `file`.
- An A2A agent has a `url`.
- Names match `[A-Za-z0-9_-]+` and are unique across protocols and the local tools.
- A protocol that is configured has its module on the classpath.
- Server paths are exact and don't collide.
- The UTCP api-key pair is set together, and URLs are absolute http(s).

## Decisions

- **Optional protocol modules.** Protocol dependencies are `compileOnly`, and each protocol's
  beans are guarded by `@ConditionalOnClass`, so an MCP-only application doesn't pull in a2a-java
  or Jackson for UTCP. If a protocol is configured but its module is missing, startup fails and
  names the artifact to add; it isn't silently ignored.
- **Connector lifecycle.**
  - `AgentWeaveConnectors` is a `SmartLifecycle` in the default (last) phase. It builds the
    connectors when the bean is created, so bad URLs fail early.
  - It starts them after the web server, so an application can consume its own endpoints, and
    registers each one in the registry once it has started.
  - It closes them in `destroy()`, after the web server has drained during graceful shutdown.
  - Remote tools are therefore not in the registry until the context has refreshed. Use them
    from `ApplicationReadyEvent` onwards, not from `@PostConstruct`.
- **Failure policy.**
  - A connector that fails to start fails startup by default
    (`IllegalStateException: AgentWeave <protocol> connector '<name>' failed to start …`).
  - With `agentweave.fail-fast=false`, the failure is logged at WARN, the connector is closed and
    left out of the registry (so it can't break listing), and health reports it `FAILED` (DOWN).
  - There is no retry, and connectors are never restarted.
- **Registry exposure default: local only.** By default a tool server publishes only the
  application's own `@AgentTool`s. Re-serving remote tools turns the application into a proxy
  that spends its connectors' credentials for its own callers, so that must be an explicit choice:
  `expose=registry`.
  - With UTCP, `expose=registry` is live, because the manual is built per request.
  - MCP reads its tool list once, when the server is built. So with `expose=registry` the MCP
    servlet is deferred: until the connectors have started it answers 503 with `Retry-After: 1`,
    and then it is built over the full registry.
- **Base URLs.**
  - UTCP `base-url` is optional. When it's unset, tool URLs are derived from each request
    (scheme, host, port, context path). Behind a proxy that's correct only with
    `server.forward-headers-strategy` set; otherwise set `base-url`.
  - The A2A card's URL is resolved once, so it can't follow requests. Set `public-url` for any
    deployment. Without it, the card advertises `http://localhost:<local.server.port><context-path>`
    and a WARN is logged; that only works for local development and tests.
- **Mounting and filter order.**
  - Each server's `ServletEndpoint`s become `ServletRegistrationBean`s, each with an
    `InboundAuthFilter` `FilterRegistrationBean` on the same patterns (REQUEST dispatch).
    Async support follows `ServletEndpoint.asyncSupported()` on both.
  - These registrations are held by one `AgentWeaveEndpointRegistrar` per protocol, which is a
    `ServletContextInitializer` whose order is `agentweave.server.auth-filter-order` (default 0).
  - Boot registers filters in initializer order, so the auth filters sit after Spring Security's
    chain (`spring.security.filter.order`, -100). Spring Security protects routes as usual; note
    that its CSRF protection must ignore the protocol paths.
  - The authenticator is the `InboundAuthenticator` bean. If there isn't one and Spring Security
    is present, it is `SpringSecurityInboundAuthenticator`: it never rejects, and it names the
    caller after Spring Security's `Authentication`, with its authorities as an attribute.
    Otherwise it is `InboundAuthenticator.ALLOW_ALL`.
- **Micrometer naming.**
  - When an `ObservationRegistry` bean exists, each operation becomes a Micrometer observation
    named `agentweave.operation`, with contextual name `<protocol> <operation>`.
  - Low-cardinality keys: `agentweave.protocol` and `agentweave.operation`. Their values are
    bounded sets, such as `mcp` and `tools/call`.
  - High-cardinality key: `agentweave.target` (tool name, URL or task id).
  - Start and stop can happen on different threads (the MCP server is reactive), so in-flight
    observations are keyed weakly by the identity of the AgentWeave observation. A call that is
    abandoned without stopping isn't recorded, and nothing leaks.
  - Every `ObservationHandler` bean receives every event, and one that fails doesn't affect the
    others.
- **Local tool discovery.**
  - Singleton beans are scanned by type without eager initialization, and only beans with
    `@AgentTool` methods are instantiated.
  - `LocalTools` reads annotations from the object's class, so AOP proxies are unwrapped to their
    singleton target. Advice such as `@Transactional` is bypassed on tool calls.
  - A tool bean that needs the `ToolRegistry` must inject `ObjectProvider<ToolRegistry>`, because
    the registry depends on the local tools.
- **Actuator.**
  - Health `agentweave` reports connector lifecycle state; it doesn't probe peers on every check.
    It is DOWN if any connector failed, OUT_OF_SERVICE while any is pending, and UP otherwise.
  - Info `agentweave.connectors.<name>` carries each connector's `ComplianceReport`.

## Coverage

Implemented:
- Everything listed above.
- `AgentWeaveConnectors.connector(name, Type)` for protocol-specific calls, for example A2A
  `send`.

Not implemented:
- Spring Boot 3.x and reactive (WebFlux) hosting.
- MCP stdio serving in Spring.
- Per-connector fail-fast, retries and reconnection.
- Connectors other than those built from properties. Register them in the `ToolRegistry` bean
  yourself and manage their lifecycle.
- UTCP dotenv files, inline manuals and manual `auth` through properties.
- A2A extended cards and custom executors through properties. Define an `A2aAgentServer` bean for
  those.
- Server compliance reports in Actuator info.

## Verification

`./gradlew :agentweave-spring-boot-starter:build` runs 21 tests on Boot 4.1.2:

- **`ApplicationContextRunner`:**
  - `@AgentTool` and `LocalTool` beans end up in the registry, and the local name is
    configurable or discovery can be disabled.
  - A real stdio MCP server, launched with `java -cp`, is declared in properties. It is started,
    namespaced (`files.echo`), invoked, and closed on shutdown.
  - Health and info are populated.
  - The Micrometer bridge and an application handler both observe the calls.
  - Fail-fast startup failure, and `fail-fast=false` leaving the connector out with health DOWN.
  - Validation errors.
  - A missing protocol module.
- **`WebApplicationContextRunner`:**
  - Nothing is exposed without opt-in.
  - Each server is mounted at its configured paths, async, with the auth filter and order.
  - The deferred MCP servlet for `expose=registry`.
  - A2A from properties or an `AgentDescriptor` bean.
  - Invalid server configuration.
- **Real Tomcat:**
  - MCP (`expose=registry`), UTCP and A2A behind a bearer `InboundAuthenticator`: 401 on every
    route without a token.
  - The application consumes its own UTCP manual and A2A agent through properties-declared
    connectors (`self.add`, `me.ask`).
  - An external MCP client sees the whole registry.
  - Micrometer records the server-side `tools/call`.
  - The A2A card carries the local-URL fallback.
- **Real Tomcat with Spring Security:** HTTP Basic on `/mcp` rejects anonymous MCP clients, and
  the authenticated user reaches the tool as the caller, which proves the filter order.

`examples/spring-boot-example` runs `@SpringBootTest(RANDOM_PORT)` end to end, with an MCP, a UTCP
and an A2A client (including `input-required`). It was also checked manually with curl and the
MCP Inspector CLI 2.13.0.
