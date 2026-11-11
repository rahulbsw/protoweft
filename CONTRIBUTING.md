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

## Fuzzing

The parsers of untrusted input have [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
fuzz tests (`@FuzzTest`, classes named `*FuzzTest`): RAP callbacks and toolset manifests, UTCP
manuals and URL checks, OAuth `WWW-Authenticate` challenges and protected-resource metadata, the
WordPiece tokenizer, and AG-UI run input, events and JSON Patch. They live in their own source set, `src/fuzz/java` (seeds in
`src/fuzz/resources/<package>/<TestClass>Inputs/<method>/`), run by each module's `fuzzTest` task.
`./gradlew build` (through `check`) runs them in regression mode, once per seed input, which is fast
and deterministic.

To fuzz, set `JAZZER_FUZZ=1` and select exactly one fuzz test per run (Jazzer fuzzes a single test
per JVM). It stops after the test's `maxDuration` (2 minutes) or `JAZZER_MAX_DURATION`:

```bash
JAZZER_FUZZ=1 ./gradlew :protoweft-rap:fuzzTest --tests '*ToolsetFuzzTest.manifest' --rerun
JAZZER_FUZZ=1 JAZZER_MAX_DURATION=10m ./gradlew :protoweft-utcp:fuzzTest --tests '*ManualFuzzTest.manual' --rerun
```

`--rerun` stops Gradle from answering from its build cache. Coverage-increasing inputs collect in
`<module>/.cifuzz-corpus/` (local only, don't commit it). A crashing input is written as
`crash-<sha1>`, but not next to the seeds: Jazzer hard-codes Maven's `src/test/resources`, so it
lands in `<module>/src/test/resources/<package>/<TestClass>Inputs/<method>/` (or the module root
if a module has no `src/test/resources`). Move it into the seed directory under
`src/fuzz/resources/` so regression runs replay it; once the bug is fixed, rename it to describe
the case and keep it. Delete the stray `…Inputs` directory Jazzer created under `src/test/resources`. `JAZZER_KEEP_GOING=<n>` continues past findings to collect
several, but then the test reports success, so look for new `crash-*` files. Inputs that crash
because of a known, still-open bug live in a sibling `<method>KnownCrashes/` directory, run by a
`@Disabled` fuzz test until the fix lands. CI's `fuzz` job fuzzes every target for 40 seconds and
uploads crashing inputs when it fails.

JSON targets accept two input forms (see the test's `FuzzJson`): plain JSON, as in the seed files,
or a leading NUL byte followed by bytes that select and edit a valid template, which reaches
type and null confusions inside otherwise valid documents.

To add fuzz tests to another module, create `src/fuzz/java` there and add
`"fuzzImplementation"("com.code-intelligence:jazzer-junit:...")`; the root build then creates the
source set and `fuzzTest` task. Fuzz classes see the module's main and test classes. Keep them out
of `src/test`: Jazzer's agent rewrites classes loaded after it in the same JVM (dropping the
parameter names `LocalTools` needs), and OpenSSF Scorecard ignores `src/test/` when looking for
fuzzers (ossf/scorecard#5458).

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
rm -rf ~/.m2/repository/io/github/rahulbsw/protoweft-*/0.0.0-verify
```

A new trusted key or an unexpected checksum change in the diff deserves a look at who signed the
artifact.

Dependabot opens weekly Gradle update PRs (`.github/dependabot.yml`). They fail CI until the
metadata is regenerated: check out the PR branch, run the command above, and push the result.

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
