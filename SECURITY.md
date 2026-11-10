# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub's private vulnerability reporting:
<https://github.com/rahulbsw/protoweft/security/advisories/new> (the repository's **Security →
Report a vulnerability** form). Don't open a public issue.
Include the affected module and version, a reproduction if you have one, and the impact you
expect.

We aim to acknowledge reports within five working days.

## Supported versions

Protoweft is pre-1.0. Only the latest released version receives security fixes.

## Security model in brief

- **Inbound requests.** No Protoweft server endpoint (MCP over HTTP, A2A, UTCP manual and tool
  routes) is protected unless you configure an `InboundAuthenticator`, or put Spring Security in
  front of it in Spring Boot. The Spring Boot starter exposes no endpoint until you enable it per
  protocol.
- **Outbound credentials.** Credentials come from your `CredentialProvider`. They are never
  passed to observation hooks: telemetry sees only the target (an executable or base URL), never
  arguments or secrets.
- **UTCP.** Manuals are fetched only over `https` or from literal loopback addresses. A manual
  fetched from a remote origin may not declare loopback tool URLs. Both rules follow the UTCP 1.1
  HTTP security guidance.
- **MCP over stdio.** The server runs as a subprocess with the environment you configure.
  Credentials reach it only as environment variables you supply.
