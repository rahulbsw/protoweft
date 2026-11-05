# Contributing to AgentWeave

Thanks for helping. This guide covers how to build the project, what a change must include,
and the rules that keep AgentWeave's protocol support honest.

## Build and test

Requirements: JDK 17 or newer. The Gradle wrapper is included.

```bash
./gradlew build          # compile, test and build Javadoc for every module
./gradlew :agentweave-mcp:test --tests '*McpClientConnectorTest'   # one module or test class
```

Some tests start real servers on free local ports. None of them need network access or API keys.

## Project layout

| Path | What lives there |
|---|---|
| `agentweave-core` | Protocol-neutral API. Has no dependencies. |
| `agentweave-server`, `agentweave-server-jetty` | The servlet hosting contract and the embedded host |
| `agentweave-mcp`, `agentweave-a2a`, `agentweave-utcp` | One module per protocol |
| `agentweave-langchain4j`, `agentweave-spring-boot-starter` | Framework integrations |
| `agentweave-bom` | Version alignment for users |
| `examples/` | Runnable examples (not published) |
| `docs/` | Documentation site sources |
| `*.md` specs with `id`/`type` frontmatter | Design specs: goals, architecture, module decisions |

A new module needs only a directory with a `build.gradle.kts` file. The build includes it
automatically.

## What every change must include

- **Tests.** New behavior gets a focused test. Protocol behavior gets an end-to-end test against a
  real server or client, not a mock of the protocol.
- **Honest compliance.** A connector's `ComplianceReport` lists only features that are implemented
  and tested. A feature that exists in the protocol but isn't implemented belongs in
  `unsupported`.
- **No invented protocol behavior.** Follow the official specification and reference SDK. If you
  must deviate, record the deviation and the reason in that module's `SPEC.md`.
- **Keep protocol types out of the core API.** `agentweave-core` must not depend on any protocol
  SDK.
- **Docs.** Update the user guide in `docs/` when you change behavior users can see, and update
  the owning `SPEC.md` when you change a decision, boundary or invariant. Rationale belongs in
  specs, not in code comments.
- **Size.** Keep files under roughly 500 lines.

## Commit messages

Write imperative, prose subjects ("Add UTCP OAuth token expiry"). Don't add `Co-Authored-By`
trailers for tools.

## Reporting security issues

Please don't open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
