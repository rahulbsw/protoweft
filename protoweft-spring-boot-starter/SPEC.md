---
id: protoweft-spring-boot-starter
type: module-design
status: draft
title: protoweft-spring-boot-starter — Spring Boot 4 auto-configuration
parent: protoweft-architecture
---

# protoweft-spring-boot-starter

## Responsibility

Makes Protoweft idiomatic in a **Spring Boot 4.x** application (built and tested on 4.1.2):

- Discovers `@AgentTool` beans and combines them into one `LocalTools` bean.
- Creates the client connectors declared in `application.yml` and merges them into one
  `ToolRegistry` bean.
- Serves tools over MCP, UTCP and RAP, and an agent over A2A, on the application's servlet
  container, only for the protocols that are opted in.
- Hosts the callbacks of RAP connectors (on the servlet container or a built-in listener) and
  provides their shared `RapStore`.
- Bridges observations to Micrometer and reports connectors through Actuator.
- Optionally builds a capability catalog for on-demand tool discovery and serves its meta-tools
  over MCP.

Nothing here is needed outside Spring. Every capability comes from `protoweft-core`,
`protoweft-server` and the protocol modules, and nothing depends on this module.

## Boundary

- Dependencies:
  - `api`: `protoweft-core`, `protoweft-server`.
  - `compileOnly`: Spring Boot (`spring-boot-autoconfigure`, `-health`, `-actuator`,
    `-actuator-autoconfigure`), the servlet API, Micrometer observation, `spring-security-core`
    and **the protocol modules** (`protoweft-mcp`, `protoweft-utcp`, `protoweft-a2a`,
    `protoweft-rap`), plus `protoweft-discovery`.
- Applications add the starter plus each protocol module they use, for example
  `implementation("io.github.rahulbsw:protoweft-mcp")`. Each auto-configuration is guarded by
  `@ConditionalOnClass` on its protocol.
- Auto-configurations, registered in `META-INF/spring/…AutoConfiguration.imports`:
  - `ProtoweftAutoConfiguration`: `LocalTools`, `ToolRegistry`, `ProtoweftConnectors`; with
    `protoweft-rap`, the `RapStore` and (opt-in) the `RapCallbackListener`.
  - `ProtoweftServerAutoConfiguration`: servlet web applications only. It provides
    `McpToolServer.Http`, `UtcpManualProvider`, `A2aAgentServer`, `RapToolServer`, and one
    `ProtoweftEndpointRegistrar` per enabled protocol; when RAP connectors are declared (and the
    listener isn't used), the `RapCallbackEndpoint` and its registrar
    `protoweftRapCallbackEndpoints`.
  - `ProtoweftMicrometerAutoConfiguration`: `MicrometerObservationHandler`.
  - `ProtoweftActuatorAutoConfiguration`: `ProtoweftHealthIndicator` (`protoweft`) and
    `ProtoweftInfoContributor` (`protoweft`).
  - `ProtoweftDiscoveryAutoConfiguration`: only with `protoweft.discovery.enabled=true`. It
    provides `CapabilityCatalog` and, with `expose-mcp=true` in a servlet web application, the
    `protoweftDiscoveryMcpEndpoints` registrar.
- Every bean the starter creates is `@ConditionalOnMissingBean`, so an application bean of the
  same type replaces it. Application beans the starter uses:
  - `@AgentTool` beans and `LocalTools.LocalTool` beans.
  - `InboundAuthenticator`.
  - `CredentialProvider`.
  - `ObservationHandler` (any number).
  - `AgentHandler`, with an optional `AgentDescriptor`.
  - `Embedder` (optional): enables hybrid discovery search.
  - `RapPromptHandler` (optional): shows RAP tools' OAuth and user-choice prompts, for every RAP
    connector.
  - `RapStore`, `RapCallbackEndpoint`, `RapCallbackListener`, `RapToolServer`: replace the
    starter's.

## Properties

| Prefix | Keys |
|---|---|
| `protoweft` | `fail-fast` (true) · `local-tools.enabled` (true) · `local-tools.name` (`local`) · `observations.enabled` (true) |
| `protoweft.mcp.clients.<name>` | `command` + `args` + `env` (stdio) **or** `url` + `endpoint` (`/mcp`) + `headers` (HTTP) · `request-timeout` |
| `protoweft.utcp.manuals.<name>` | `url` **or** `file` · `headers` · `variables` · `discovery-timeout` · `call-timeout` |
| `protoweft.a2a.agents.<name>` | `url` · `timeout` · `streaming` (true) |
| `protoweft.rap.servers.<name>` | `url` · `additional-urls` · `timeout` (connector: 10 min) · `request-timeout` (connector: 30 s) · `thread-id` |
| `protoweft.rap` | `callback.public-base-url` · `callback.listener.enabled` (false) · `callback.listener.host` (`127.0.0.1`) · `callback.listener.port` (0) · `store.dir` |
| `protoweft.server` | `auth-filter-order` (0) |
| `protoweft.server.mcp` | `enabled` · `path` (`/mcp`) · `expose` (`local`\|`registry`) · `server-name` · `server-version` · `allowed-origins` · `request-timeout` |
| `protoweft.server.utcp` | `enabled` · `base-url` · `expose` · `manual-version` · `api-key-header` + `api-key-variable` |
| `protoweft.discovery` | `enabled` (false) · `fallback` (`progressive`\|`all-schemas`\|`none`) · `sources.<name>.required-scopes` · `expose-mcp` (false) · `mcp-path` (`/mcp-discovery`) |
| `protoweft.server.a2a` | `enabled` · `public-url` · `path` (`/a2a`) · `name` · `description` · `version` · `skills[]` · `streaming` · `push-notifications` · `agent-timeout` |
| `protoweft.server.rap` | `enabled` · `base-path` (`/rap`) · `base-url` · `expose` · `name` (`protoweft`) · `description` · `allowed-callback-hosts` · `choice-timeout` |

`<name>` is the registry source name, so tools appear as `<name>.<tool>`; an A2A agent appears as
`<name>.ask`. The configuration processor generates metadata, including the
`management.{health,info}.protoweft.enabled` switches.

Properties are validated at startup, and errors are reported as
`InvalidConfigurationPropertyValueException`, so Boot's failure analyzer names the offending key.
The checks:
- An MCP client sets exactly one of `command` and `url`, and never uses options that belong to
  the other kind.
- A UTCP manual sets exactly one of `url` and `file`.
- An A2A agent has a `url`.
- A RAP connector has a `url`; it, its `additional-urls` and `protoweft.rap.callback.public-base-url`
  are absolute http(s) URLs. A `thread-id` isn't empty.
- RAP connectors without a servlet web application need `protoweft.rap.callback.listener.enabled`.
- The RAP server's `base-path` is `/` or an exact path not under `/rap/callback`; its `base-url` is
  absolute http(s); its `name` has 1 to 128 characters.
- Names match `[A-Za-z0-9_-]+` and are unique across protocols and the local tools.
- A protocol that is configured has its module on the classpath.
- Server paths are exact and don't collide. That includes `protoweft.discovery.mcp-path`.
- Discovery is enabled only with `protoweft-discovery` on the classpath, and `expose-mcp` only
  with `protoweft-mcp`.
- Each `protoweft.discovery.sources.<name>` names a known source.
- The UTCP api-key pair is set together, and URLs are absolute http(s).

## Decisions

- **Optional protocol modules.** Protocol dependencies are `compileOnly`, and each protocol's
  beans are guarded by `@ConditionalOnClass`, so an MCP-only application doesn't pull in a2a-java
  or Jackson for UTCP. If a protocol is configured but its module is missing, startup fails and
  names the artifact to add; it isn't silently ignored.
- **Connector lifecycle.**
  - `ProtoweftConnectors` is a `SmartLifecycle` in the default (last) phase. It builds the
    connectors when the bean is created, so bad URLs fail early.
  - It starts them after the web server, so an application can consume its own endpoints, and
    registers each one in the registry once it has started.
  - It closes them in `destroy()`, after the web server has drained during graceful shutdown.
  - Remote tools are therefore not in the registry until the context has refreshed. Use them
    from `ApplicationReadyEvent` onwards, not from `@PostConstruct`.
- **Failure policy.**
  - A connector that fails to start fails startup by default
    (`IllegalStateException: Protoweft <protocol> connector '<name>' failed to start …`).
  - With `protoweft.fail-fast=false`, the failure is logged at WARN, the connector is closed and
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
  - These registrations are held by one `ProtoweftEndpointRegistrar` per protocol, which is a
    `ServletContextInitializer` whose order is `protoweft.server.auth-filter-order` (default 0).
  - Boot registers filters in initializer order, so the auth filters sit after Spring Security's
    chain (`spring.security.filter.order`, -100). Spring Security protects routes as usual; note
    that its CSRF protection must ignore the protocol paths.
  - The authenticator is the `InboundAuthenticator` bean. If there isn't one and Spring Security
    is present, it is `SpringSecurityInboundAuthenticator`: it never rejects, and it names the
    caller after Spring Security's `Authentication`, with its authorities as an attribute.
    Otherwise it is `InboundAuthenticator.ALLOW_ALL`.
- **Micrometer naming.**
  - When an `ObservationRegistry` bean exists, each operation becomes a Micrometer observation
    named `protoweft.operation`, with contextual name `<protocol> <operation>`.
  - Low-cardinality keys: `protoweft.protocol` and `protoweft.operation`. Their values are
    bounded sets, such as `mcp` and `tools/call`.
  - High-cardinality key: `protoweft.target` (tool name, URL or task id).
  - Start and stop can happen on different threads (the MCP server is reactive), so in-flight
    observations are keyed weakly by the identity of the Protoweft observation. A call that is
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
- **Tool discovery (opt-in).**
  - **Sources.** The `CapabilityCatalog` bean has one source per registry source that exists
    when it is created: the local tools, plus whatever an application-defined `ToolRegistry`
    already holds. It also has one per declared connector. A catalog's sources are fixed when it
    is built, so declared connectors are added up front instead of waiting for the registry.
  - **Not-yet-started connectors.** Each declared connector is wrapped so that it lists tools
    only while its state is `UP`. It stays a `ToolConnector`, so cards still carry its protocol
    and negotiated revision. Its availability probe is `state == UP`. A pending, failed
    (`fail-fast=false`) or closed connector is reported unavailable with its state; the catalog
    never calls it.
  - **Refresh after the connectors start.** `CatalogRefresher` is a `SmartLifecycle` that depends
    on `ProtoweftConnectors`, so Spring starts the connectors first (same phase, dependencies
    first), and then `catalog.refresh()` runs. Sources registered in the registry later are not
    picked up. Define your own `CapabilityCatalog` bean for those, or for per-tool scopes, tags
    or a custom index.
  - **Embedder and observations.** An `Embedder` bean is used if present; otherwise search is
    keyword-only and says so. Observations go to the same composite handler as the servers.
  - **Fallback.** `fallback` maps onto `FallbackPolicy.of(mode)` with the module defaults.
    `sources.<name>.required-scopes` becomes the source's `SourcePolicy.requiredScopes`, matched
    against the caller attribute `scopes`.
  - **Spring Security scopes.** `SpringSecurityInboundAuthenticator` also sets `scopes`: the
    `SCOPE_x` authorities (Spring Security's OAuth2 resource-server convention), with the prefix
    removed.
  - **Serving over MCP.** `expose-mcp=true` serves `DiscoveryTools.of(catalog)` with
    `McpToolServer.http` at `mcp-path`, through an `ProtoweftEndpointRegistrar`. So it gets the
    same `InboundAuthFilter`, filter order and authenticator as the other servers, and the
    caller reaches the catalog's permission filter.
    - Server name, version, allowed origins and request timeout come from
      `protoweft.server.mcp.*`. The regular MCP server doesn't have to be enabled.
    - The meta-tool list is fixed, so this endpoint isn't deferred. Before the connectors have
      started, their capabilities are reported unavailable.
    - The MCP server isn't a bean, so it can't clash with the `McpToolServer.Http` bean. Its
      servlet closes it when the container destroys it.
  - **Optional module.** `protoweft-discovery` is `compileOnly`, and its beans are guarded by
    `@ConditionalOnClass`. Enabling discovery without the module fails startup and names the
    artifact.
- **RAP (`protoweft-rap`).**
  - *Connectors.* `RapConnectorFactory` builds one `RapClientConnector` per
    `protoweft.rap.servers.<name>` with the `CredentialProvider` bean, the `RapStore` bean, the
    optional `RapPromptHandler` bean, and store key `<name>` (stable across restarts even if the
    URL changes). They join `ProtoweftConnectors`, so lifecycle, fail-fast, health and info are
    the same as for other protocols. RAP connectors have no observation hook, so they emit no
    Micrometer observations.
  - *Callbacks on the servlet container (default).* When connectors are declared and the
    listener isn't enabled, a `RapCallbackEndpoint` bean is mounted at `/rap/callback/*` (fixed by
    the module) through `protoweftRapCallbackEndpoints`. Its `InboundAuthFilter` uses
    `RapCallbackEndpoint.exemptCallbacks(authenticator)`, because tool servers send no credentials:
    the unguessable per-call token in the URL authenticates them. Spring Security must permit
    `/rap/callback/**` and exempt it from CSRF. The public URL is
    `protoweft.rap.callback.public-base-url` (including any context path); without it the
    endpoint falls back to `http://localhost:<local.server.port><context-path>`, resolved per call,
    and a WARN is logged, as for the A2A card.
  - *Built-in listener.* `protoweft.rap.callback.listener.enabled=true` starts a
    `RapCallbackListener` bean (host, port, the same public URL) and no endpoint is mounted. A
    non-web application with RAP connectors must enable it; otherwise startup fails and names the
    property, rather than building connectors that can never receive a result.
  - *Store.* One `RapStore` bean serves connectors and the server: `InMemoryRapStore`, or
    `FileRapStore` in `protoweft.rap.store.dir`. Recovered results and subscriptions are read from
    the connector (`connectors.connector(name, RapClientConnector.class)`), which holds them until
    a listener is set.
  - *Server.* `protoweft.server.rap.enabled` builds `RapToolServer` over `expose` (as UTCP, the
    toolset is built per request, so `registry` is live) and mounts its five routes under
    `base-path` (default `/rap`, not the module's root default, so the routes don't sit at the
    application root) behind the normal `InboundAuthFilter`; the same authenticator protects
    `/close_thread` and `/cancel_tool_call`, as the RAP spec requires. `allowed-callback-hosts`
    becomes `RapCallbackPolicy.DEFAULT.allowingHosts(..)`; any other policy, a callback
    authorizer or retry policy needs an own `RapToolServer` bean. The bean is injectable, so the
    application can resume `recoveredSubscriptions()` after a restart.
- **Actuator.**
  - Health `protoweft` reports connector lifecycle state; it doesn't probe peers on every check.
    It is DOWN if any connector failed, OUT_OF_SERVICE while any is pending, and UP otherwise.
  - Info `protoweft.connectors.<name>` carries each connector's `ComplianceReport`.
- **Spring AI (`ProtoweftSpringAiAutoConfiguration`, optional `protoweft-spring-ai`).**
  Everything is opt-in.
  - `protoweft.spring-ai.tool-callbacks.enabled` registers an `ProtoweftToolCallbacks` bean
    (`expose=local`, the default, or `registry`) and adds it to the auto-configured
    `ChatClient.Builder`.
    - *Why opt-in:* Spring AI's MCP server re-serves every `ToolCallbackProvider` bean, so
      remote tools and their credentials would otherwise be republished silently.
  - `protoweft.spring-ai.tool-search.enabled` (with discovery enabled) adds Spring AI's
    `ToolSearchToolCallingAdvisor` backed by a `CatalogToolIndex`, plus the catalog's
    callbacks.
    - It replaces the plain-callbacks customizer, because registering both duplicates tool
      names and Spring AI rejects that.
  - `protoweft.server.a2a.from-chat-client=true` serves the application's `ChatClient` (or one
    built from `ChatClient.Builder`) as the A2A agent when there's no `AgentHandler` bean. It
    streams when `protoweft.server.a2a.streaming` is true.
  - There's no ordering against the server auto-configuration, which resolves the
    `AgentHandler` lazily. Ordering it before would form a cycle with discovery.
  - Both builder additions are `ChatClientBuilderCustomizer` beans. Spring AI 2.0 deprecates
    `ChatClientCustomizer` for removal; its `ChatClientAutoConfiguration` applies both kinds.

## Coverage

Implemented:
- Everything listed above.
- `ProtoweftConnectors.connector(name, Type)` for protocol-specific calls, for example A2A
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
- RAP through properties: static headers (use the `CredentialProvider` bean), per-connector prompt
  handlers, executors and retry policies; the server's callback authorizer, retry policy and a
  custom callback policy (define a `RapToolServer` bean); a configurable callback path; mounting
  an application-defined `RapCallbackEndpoint` when no RAP connector is declared; Micrometer
  observations for RAP.
- Discovery through properties: per-tool scopes, source tags, confidence thresholds and a
  names-only limit; a catalog over sources registered after startup; LangChain4j wiring. Define a
  `CapabilityCatalog` bean for those.

## Verification

`./gradlew :protoweft-spring-boot-starter:build` runs 53 tests on Boot 4.1.2:

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
  - Discovery (`ProtoweftDiscoveryConfigurationTest`):
    - It is opt-in.
    - The catalog covers the local tools, application-registered sources and a real stdio MCP
      connector. The connector is cataloged after it starts, with its protocol and revision.
    - Fallback mode and per-source scopes apply.
    - An `Embedder` bean makes search hybrid.
    - A failed connector (`fail-fast=false`) is reported unavailable.
    - Unknown sources, colliding or relative `mcp-path`, and a missing discovery module are
      rejected.
    - With `expose-mcp`, the meta-tools are mounted at `/mcp-discovery` behind the auth filter,
      next to `/mcp`.
  - `SpringSecurityInboundAuthenticator` maps `SCOPE_` authorities to `scopes`.
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
- **Real Tomcat, discovery (`ProtoweftDiscoveryHttpTest`):**
  - Our MCP client at `/mcp-discovery` sees exactly the three meta-tools.
  - `search_capabilities` finds the stdio connector's `files.echo`, and `call_capability` runs it.
  - The scoped local tools are found and called only with the bearer token that grants the scope,
    and the caller's name reaches the tool.
  - Anonymous clients get 401.
- **RAP, context runners (`ProtoweftRapConfigurationTest`):**
  - The store is in memory by default, a `FileRapStore` with `protoweft.rap.store.dir`, and an
    application bean replaces it.
  - A connector with the built-in listener (public URL honoured) is built, fails to start against
    an unreachable server with `fail-fast=false`, is left out of the registry, and appears in
    health (DOWN) and info (protocol `rap`, `infinity@e1a3e4b`, unsupported `view_update`).
  - A non-web application with RAP connectors and no listener fails, naming
    `protoweft.rap.callback.listener.enabled`.
  - Invalid connector properties and a missing `protoweft-rap` are rejected.
  - The server is mounted at `/rap/...` (or the root with `base-path=/`) behind the auth filter;
    declared connectors mount `/rap/callback/*` unless the listener is enabled; nothing is mounted
    without opt-in.
  - Invalid server configuration is rejected (base path, callback-path overlap, collision with
    `/mcp`, base URL, name length, missing module).
- **Real Tomcat, RAP (`ProtoweftRapHttpTest`):** one application serves its `@AgentTool`s over RAP
  behind a bearer `InboundAuthenticator` and consumes them through `protoweft.rap.servers.self`,
  with callbacks on the mounted endpoint:
  - results and the authenticated caller; the negotiated revision;
  - a subscription's events (associative, final) through `ToolRegistry`, and the unavailable
    `RapCallContext` stand-in over the local registry;
  - the `RapPromptHandler` bean answers a user choice;
  - health UP and info with the RAP compliance report;
  - server routes answer 401 without a token while the callback path is exempt (404 for an
    unknown token);
  - `allowed-callback-hosts` admits a named host and the default policy still refuses `10.x`;
  - a second, non-web context consumes the same server through the built-in listener.
- **Real Tomcat with Spring Security:** HTTP Basic on `/mcp` rejects anonymous MCP clients, and
  the authenticated user reaches the tool as the caller, which proves the filter order.

`examples/spring-boot-example` runs `@SpringBootTest(RANDOM_PORT)` end to end, with an MCP, a UTCP
and an A2A client (including `input-required`). The RAP wiring is exercised in plain Java by
`examples/plain-java-gateway` (`GatewayExampleTest`). It was also checked manually with curl and the
MCP Inspector CLI 2.13.0.
