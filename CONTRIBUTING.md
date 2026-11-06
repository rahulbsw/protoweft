# Contributing to Protoweft

Thanks for helping. This guide covers how to build the project, what a change must include,
and the rules that keep Protoweft's protocol support honest.

## Build and test

Requirements: JDK 17 or newer. The Gradle wrapper is included.

```bash
./gradlew build          # compile, test and build Javadoc for every module
./gradlew :protoweft-mcp:test --tests '*McpClientConnectorTest'   # one module or test class
```

Some tests start real servers on free local ports. None of them need network access or API keys.

## Changing dependencies

The build verifies every dependency and plugin against `gradle/verification-metadata.xml`
(SHA-256 checksums and PGP signatures, with the public keys in `gradle/verification-keyring.keys`).
After adding or upgrading a dependency, regenerate the metadata over the whole build, including
the ARGI snapshot module, and review the diff before committing:

```bash
scripts/install-argi-snapshot.sh
./gradlew --write-verification-metadata sha256,pgp --export-keys -PargiSnapshot \
    build cyclonedxBom publishToMavenLocal -Pversion=0.0.0-verify
rm gradle/verification-keyring.gpg
rm -rf ~/.m2/repository/io/github/rahulbsw/protoweft/*/0.0.0-verify
```

A new trusted key or an unexpected checksum change in the diff deserves a look at who signed the
artifact.

## Project layout

| Path | What lives there |
|---|---|
| `protoweft-core` | Protocol-neutral API. Has no dependencies. |
| `protoweft-server`, `protoweft-server-jetty` | The servlet hosting contract and the embedded host |
| `protoweft-mcp`, `protoweft-a2a`, `protoweft-utcp` | One module per protocol |
| `protoweft-langchain4j`, `protoweft-spring-boot-starter` | Framework integrations |
| `protoweft-bom` | Version alignment for users |
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
- **Keep protocol types out of the core API.** `protoweft-core` must not depend on any protocol
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
