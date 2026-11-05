# Contributing

Contributions are welcome. The full guide is `CONTRIBUTING.md` at the repository root; this page
summarizes it.

## Build and test

You need JDK 17 or newer; the Gradle wrapper is included.

```bash
./gradlew build                                                    # compile, test and build Javadoc
./gradlew :agentweave-mcp:test --tests '*McpClientConnectorTest'   # one module or test class
./gradlew aggregateJavadoc                                         # the combined API reference
```

Some tests start real servers on free local ports. None of them needs network access or API keys.

## What a change must include

- **Tests.** New behavior gets a focused test. Protocol behavior gets an end-to-end test against a
  real server or client, not a mock of the protocol.
- **Honest compliance.** A `ComplianceReport` lists only features that are implemented and tested;
  a protocol feature that isn't implemented belongs in `unsupported`.
- **No invented protocol behavior.** Follow the official specification and reference SDK. Record
  any deviation, with the reason, in the module's `SPEC.md`.
- **Protocol types stay out of the core API.** `agentweave-core` must not depend on any protocol
  SDK.
- **Docs.** Update this site (`docs/`) when behavior users can see changes, and the owning
  `SPEC.md` when a decision, boundary or invariant changes.
- **Size.** Keep files under roughly 500 lines.

Commit subjects are imperative prose ("Add UTCP OAuth token expiry").

## Project layout

| Path | Contents |
|---|---|
| `agentweave-core` | The protocol-neutral API; no dependencies. |
| `agentweave-server`, `agentweave-server-jetty` | The servlet hosting contract and the embedded host. |
| `agentweave-mcp`, `agentweave-a2a`, `agentweave-utcp` | One module per protocol. |
| `agentweave-langchain4j`, `agentweave-spring-boot-starter` | Framework integrations. |
| `agentweave-bom` | Version alignment. |
| `examples/` | Runnable examples (not published). |
| `docs/`, `mkdocs.yml` | This documentation site. |
| `*.md` files with `id`/`type` front matter | Design specs: goals, architecture, module decisions. |

A new module needs only a directory with a `build.gradle.kts`; the build includes it
automatically.

## Building this site

The site is built with [Zensical](https://zensical.org), which reads `mkdocs.yml`:

```bash
pip install zensical
zensical serve                       # live preview at http://localhost:8000
zensical build --clean               # static site in site/
./gradlew aggregateJavadoc && cp -R build/docs/javadoc site/api   # the API reference under /api/
```

The `Documentation` GitHub Actions workflow (`.github/workflows/docs.yml`) runs the same steps and
deploys to GitHub Pages on every push to `main` that touches the docs or the Java sources.

## Security issues

Don't open public issues for vulnerabilities; see `SECURITY.md`.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](reference/license.md).
