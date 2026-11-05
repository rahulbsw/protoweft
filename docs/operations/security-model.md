# Security model

A summary of who AgentWeave trusts and what it protects. Details are in
[Security and credentials](../concepts/security.md) and the protocol guides.

## Inbound requests

- **Nothing is protected by default.** No AgentWeave server endpoint (MCP over HTTP, both A2A
  endpoints, the UTCP manual and tool routes) requires authentication until you configure an
  `InboundAuthenticator`, or put Spring Security in front of it in Spring Boot.
- **One authenticator for all protocols.** Every endpoint runs the same authenticator before the
  protocol code sees the request. Denials are 401 (with `WWW-Authenticate: Bearer`, or the
  authenticator's own challenge) or 403 with a JSON body; an authenticator that throws yields 401.
  `agentweave-oauth`'s `BearerTokenAuthenticator` validates OAuth bearer tokens, including their
  audience, and never exposes the token itself to handlers.
- **The embedded Jetty host binds `localhost` by default**, and admits everyone until you set an
  authenticator.
- **The Spring Boot starter exposes nothing until you opt in** per protocol with
  `agentweave.server.<protocol>.enabled=true`.
- **Callers reach your code.** The authenticated `CallerIdentity` is passed to tools and agent
  handlers, so authorization decisions can live there.
- **A2A tasks are isolated per caller.** Only the caller that created a task can read, continue,
  cancel, subscribe to, or configure push for it; all anonymous callers share one identity. The
  extended agent card is served only to non-anonymous callers.
- **MCP browser origins.** The MCP endpoint rejects requests whose `Origin` isn't explicitly
  allowed (DNS-rebinding protection).
- **Re-serving is explicit.** Serving a registry with remote connectors spends their credentials on
  behalf of your callers. Spring Boot serves only local tools unless you set `expose=registry`, and
  registers no Spring AI `ToolCallbackProvider` bean (which Spring AI's MCP server would re-serve)
  unless you set `agentweave.spring-ai.tool-callbacks.enabled`.

## Outbound credentials

- Credentials come only from your `CredentialProvider`, per protocol and target, or, for MCP over
  HTTP, from an `HttpAuthorizer` you configure, such as `agentweave-oauth`'s `OAuthClient`. The
  OAuth client sends a token only to the resource it was issued for, only in the `Authorization`
  header, and talks to authorization servers only over `https` (or literal loopback addresses).
- They never reach observation hooks or Micrometer tags: telemetry sees only the target (an
  executable without arguments, or a URL without user info, query or fragment) and never tool
  arguments.
- `Credential` `toString()` values are masked.
- **MCP stdio:** the server runs as a subprocess with the environment you configure; credentials
  reach it only as environment variables. It is a child process with your privileges: launch only
  servers you trust.
- **UTCP:** your credentials apply only to manual discovery. Tool calls authenticate with the
  `auth` objects in the manual, filled from variables you configure, so a manual can only use the
  variables in its own namespace.

## UTCP transport rules

Following the UTCP 1.1 HTTP security guidance and the reference client:

- Manuals, tools and token endpoints are reached only over `https`, or `http` on literal loopback
  addresses (decided without DNS).
- A manual fetched from a remote origin may not declare loopback tool or token URLs, and an
  argument can't steer a request to loopback.
- Redirects are re-validated on every hop, never followed from remote into loopback, and drop
  authentication headers, query and body across origins.

## A2A push notifications

Webhook targets must be `https` on public addresses unless you enable `insecurePushTargets(true)`
for development.

## Reporting vulnerabilities

See `SECURITY.md` in the repository: report privately through GitHub's private vulnerability
reporting, not in a public issue.
