# Conformance testing

Protoweft runs two official conformance suites, plus its own AG-UI checker, against small apps built
only on its public API:

| Suite | Version | Tests | App under test |
|---|---|---|---|
| [MCP conformance suite](https://github.com/modelcontextprotocol/conformance) | `@modelcontextprotocol/conformance@0.2.0-alpha.12` (the version the MCP Java SDK pins), spec `2025-11-25` | server scenarios (`--suite active`) and the 18 client scenarios required for 2025-11-25 (4 non-auth, 14 `auth/*`) | `conformance/mcp-server` (`McpToolServer.http` on `ProtoweftServer`), `conformance/mcp-client` (`McpClientConnector`, with protoweft-oauth's `OAuthClient` for `auth/*`) |
| [A2A TCK](https://github.com/a2aproject/a2a-tck) | tag `1.0.0.alpha3` (A2A spec 1.0, as pinned by a2a-java 1.5.0.Final), `--level must` | the TCK's MUST requirements for the transports the card declares | `conformance/a2a-sut` (`A2aAgentServer` on `ProtoweftServer`) |
| AG-UI checker (`AgUiConformance` in `protoweft-agui`; AG-UI has no official suite) | AG-UI spec `1.0` | 12 producer checks: the SSE binding, event shapes, run lifecycle and identity, version declaration and negotiation, input handling | `conformance/agui-sut` (`AgUiAgentServer` with a scripted agent using every event family) |

AG-UI is also checked against the official SDKs in both directions (`scripts/interop/agui.sh`):
the TypeScript `@ag-ui/client` 1.0.4 drives `conformance/agui-sut`, and Protoweft's client and the
checker run against an agent built on the Python `ag-ui-protocol` 1.0.0. The official Python agent
passes all 12 checks, which also cross-checks the checker against an independent implementation.

The apps in `conformance/` are never published. Every failure that remains is listed in a
baseline with its reason, and a run fails on any failure that isn't listed and on any listed
entry that now passes.

## What is not tested yet

- The MCP client `auth/*` scenarios outside the 2025-11-25 requirement set: the back-compat
  (`2025-03-26`), extension (client credentials, DPoP, enterprise-managed, workload identity) and
  draft (`2026-07-28`) scenarios.
- The A2A gRPC and HTTP+JSON bindings. Protoweft serves only JSON-RPC, and the card declares
  only that, so the TCK skips those transports.
- The TCK's SHOULD and MAY levels.

## Running locally

You need JDK 17 or newer, Node.js 20 or newer (for `npx`), and, for the TCK, git, Python 3.11 or
newer, and [uv](https://github.com/astral-sh/uv).

```bash
scripts/conformance/mcp-server.sh     # MCP server scenarios
scripts/conformance/mcp-client.sh     # MCP client scenarios (or name scenarios as arguments)
scripts/conformance/a2a-tck.sh        # A2A TCK, MUST level
scripts/conformance/agui.sh           # AG-UI checker against conformance/agui-sut
scripts/interop/agui.sh               # AG-UI interop with the official TypeScript and Python SDKs
```

Each script builds its app with Gradle (`installDist`), starts it in the background, waits until
it's ready, runs the pinned suite, and then stops the app it started. The script exits 0 only
when every failure is in the baseline. Results go to `build/conformance/<suite>/`: the suite's
per-scenario `checks.json` files, or the TCK's `compatibility.json`/`.html`, `junitreport.xml`
and `tck_report.html`. The app log and the console output are saved there too.

| Variable | Default | Used by |
|---|---|---|
| `MCP_SERVER_PORT` | `8080` | `mcp-server.sh` |
| `MCP_CONFORMANCE_VERSION`, `MCP_SPEC_VERSION` | `0.2.0-alpha.12`, `2025-11-25` | MCP scripts |
| `A2A_SUT_PORT` | `9999` | `a2a-tck.sh` |
| `A2A_TCK_VERSION`, `A2A_TCK_LEVEL`, `A2A_TCK_PYTHON` | `1.0.0.alpha3`, `must`, `3.12` | `a2a-tck.sh` |
| `A2A_TCK_DIR` | `build/conformance/a2a-tck-src` (cloned at the tag) | `a2a-tck.sh` |
| `A2A_TCK_BASELINE` | `conformance/a2a-tck-baseline.yml`; `none` exits with the TCK's raw code | `a2a-tck.sh` |
| `AGUI_SUT_PORT`, `AGUI_TARGET_URL`, `AGUI_BASELINE` | `9998`, — (start the SUT), `conformance/agui-baseline.yml` | `agui.sh` |
| `AGUI_INTEROP_PORT_BASE`, `AGUI_INTEROP_PYTHON` | `9996`, `3.12` | `scripts/interop/agui.sh` |
| `TCK_STREAMING_TIMEOUT` | `2.0` seconds (the resubscribe task runs for twice this) | TCK and SUT |

A script refuses to start if its port already answers, so it never tests another server.

In CI, `.github/workflows/conformance.yml` runs five jobs on every pull request and push to
`main`:

- `mcp-server`;
- `mcp-client`, with one matrix entry per scenario;
- `a2a-tck`;
- `agui-conformance` (the checker against `conformance/agui-sut`);
- `agui-interop` (both directions against the official SDKs, pinned by hash in
  `interop/agui/ts/package-lock.json` and `interop/agui/python/requirements.txt`).

The jobs use Temurin 17, Node 22, Python 3.12 and uv, and upload `build/conformance/<suite>/` as
artifacts.

## Current results

These come from local runs on 2026-11-05. The MCP server suite and the A2A TCK were re-run on
`main` after commit `d62edad`, which fixed the two library gaps those suites found (embedded
resources in MCP tool results, and inline file names in A2A). All 18 required MCP client
scenarios were re-run on `main` after commit `0961934`, which let the OAuth client accept
authorization servers on `http://localhost`. The apps were built with the JDK 17 toolchain and
run on JDK 21.0.10, with Node 26.3.1, Python 3.12.0 and uv 0.5.1. All three scripts exited 0.

### MCP server: 31 scenarios, 65 checks

61 checks passed. 4 failed, all in the baseline (sampling and elicitation, which Protoweft does
not support), each in a different scenario. 27 scenarios passed completely.

| Scenarios | Result |
|---|---|
| `server-initialize`, `server-session-lifecycle`, `logging-set-level`, `ping`, `completion-complete` | pass |
| `tools-list`, `tools-call-simple-text`, `tools-call-image`, `tools-call-audio`, `tools-call-with-logging`, `tools-call-error`, `tools-call-with-progress` | pass |
| `resources-list`, `resources-read-text`, `resources-read-binary`, `resources-templates-read`, `resources-subscribe`, `resources-unsubscribe` | pass |
| `prompts-list`, `prompts-get-simple`, `prompts-get-with-args`, `prompts-get-embedded-resource`, `prompts-get-with-image` | pass |
| `tools-call-embedded-resource`, `tools-call-mixed-content` | pass (failed before `d62edad`) |
| `server-sse-multiple-streams`, `dns-rebinding-protection` | pass |
| `tools-call-sampling`, `tools-call-elicitation`, `elicitation-sep1034-defaults`, `elicitation-sep1330-enums` | 1 baselined check each (unsupported) |

In every scenario with a failure, the `wire-schema-valid` check still passed.

### MCP client: 18 scenarios

Counts are the suite's own `Passed:` totals per scenario. Every `auth/*` scenario passed every
check (227 of 227); the only failures are the baselined elicitation and SSE-retry checks.
`mcp-client.sh` exited 0.

| Scenario | Passed | Failed (baselined) |
|---|---|---|
| `initialize` | 1/1 | 0 |
| `tools_call` | 2/2 | 0 |
| `elicitation-sep1034-client-defaults` | 0/6 | 6 (unsupported) |
| `sse-retry` | 1/2 | 1 failure and 1 warning, both baselined (upstream SDK bug) |
| `auth/metadata-default`, `auth/metadata-var1`, `auth/metadata-var2`, `auth/metadata-var3` | 18/18 each | 0 |
| `auth/basic-cimd` | 13/13 | 0 |
| `auth/scope-from-www-authenticate`, `auth/scope-from-scopes-supported`, `auth/scope-omitted-when-undefined` | 14/14 each | 0 |
| `auth/scope-step-up` | 20/20 | 0 |
| `auth/scope-retry-limit` | 10/10 (the client stops with 403 after its retry limit, as the scenario requires) | 0 |
| `auth/token-endpoint-auth-basic`, `-post`, `-none` | 19/19 each | 0 |
| `auth/pre-registration` | 13/13 | 0 |

Before `0961934`, the OAuth client refused the suite's authorization server: plain `http` was
allowed only for the literal addresses `127.0.0.0/8` and `::1`, not the name `localhost`, so every
`auth/*` flow stopped after fetching the protected resource metadata. It now also accepts the exact
name `localhost` (see `protoweft-oauth`); the redirect receiver still binds `127.0.0.1`.

### A2A TCK, MUST level: 235 test cases

88 passed and none failed, so the baseline is empty. 147 were skipped, and 30 non-MUST tests were
deselected.

- **Skipped:** almost all for the gRPC and HTTP+JSON transports, which the card doesn't declare.
  On JSON-RPC, a few were skipped because of capabilities. The server supports push and
  streaming, so it can't produce the "not supported" errors. It declares no extended card and no
  required extension.
- **Requirement view** (`compatibility.json`): 76 MUST requirements pass, none fail, and 16 are
  skipped (transport- or capability-gated). Another 22 are "not tested", because this TCK version
  has no test for them: card signatures, auth, versioning, and binding equivalence. The TCK
  reports `must_compatibility` as 77.6%, which counts "not tested" as not passed. `DM-ART-001`
  (an inline file part must keep its file name) failed before `d62edad`.

## Baselined items

**MCP server** (`conformance/mcp-server-baseline.yml`):

| Entry | Reason |
|---|---|
| `tools-call-sampling:tools-call-sampling` | `McpFeatures.SAMPLING` is unsupported. A tool can't send `sampling/createMessage`, so `test_sampling` isn't published. |
| `tools-call-elicitation:tools-call-elicitation`, `elicitation-sep1034-defaults:elicitation-sep1034-general`, `elicitation-sep1330-enums:elicitation-sep1330-general` | `McpFeatures.ELICITATION` is unsupported. A tool can't send `elicitation/create`, so the elicitation tools aren't published. |

**MCP client** (`conformance/mcp-client-baseline.yml`):

| Entry | Reason |
|---|---|
| `elicitation-sep1034-client-defaults:*`: the 6 checks `general`, `string-`, `integer-`, `number-`, `enum-` and `boolean-default` | `McpFeatures.ELICITATION` is unsupported. The connector declares no elicitation capability, so `elicitation/create` is answered with `-32601`. |
| `sse-retry:client-sse-retry-timing`, `sse-retry:client-sse-last-event-id` | Upstream bug in MCP Java SDK 2.1.0 (see below). The MCP Java SDK's own baseline lists `sse-retry` too. |

The SDK bug in detail:

- `HttpClientStreamableHttpTransport` consumes the SSE stream of a POST without a resumable
  stream, and never reads the SSE `retry` field.
- So when the server closes that stream early, the client doesn't wait `retry` milliseconds and
  doesn't reconnect with `Last-Event-ID`.
- The `tools/call` then times out.

**A2A TCK** (`conformance/a2a-tck-baseline.yml`): empty.

The TCK has no baseline option. `a2a-tck.sh` compares the failed and errored cases in its JUnit
report with this list, using the same rules as the MCP suite.

## Notes on the apps

- `conformance/mcp-client` runs the `auth/*` scenarios with one client configuration, nothing
  tailored per scenario: `McpClientConnector.http(..).authorizer(oauthClient)` with the
  authorization code grant, the suite's fixed Client ID Metadata Document URL
  (`https://conformance-test.local/client-metadata.json`, used only when the server advertises
  `client_id_metadata_document_supported`), else Dynamic Client Registration;
  `auth/pre-registration` uses the `client_id`/`client_secret` from `MCP_CONFORMANCE_CONTEXT`.
  Each scenario lists the tools and calls `test-tool`. The suite's authorization endpoint approves
  at once with a redirect, so the `BrowserLauncher` is a headless user agent: it GETs the
  authorization URL, then the redirect `Location` (the OAuthClient's loopback receiver). Each
  challenge and the OAuthClient's decision, with its reason when it gives up, go to the
  scenario's `stderr.txt`.

- `conformance/mcp-server` publishes the suite's fixture names and texts exactly. It binds
  `localhost` and allows only `http://localhost:*`, `http://127.0.0.1:*` and `http://[::1]:*` as
  browser origins; any other `Origin` gets 403, which is what `dns-rebinding-protection` checks.
  Tools Protoweft can't implement (sampling, elicitation) aren't faked.
  The embedded-resource tools return `Content.Resource`.
- `conformance/a2a-sut` follows the TCK's Gherkin scenarios (`scenarios/*.feature`), selecting a
  behavior by `messageId` prefix. Its JSON-RPC endpoint is at `/`, as in a2a-java's SUT. The
  TCK's HTTP client posts to `<interface URL>/`, so an interface at `/a2a` would be called as
  `/a2a/` and miss the exact servlet mapping.
- `AgentTaskContext` has no artifact append or last-chunk call. So the `tck-stream-artifact-chunked`
  scenario sends both chunks in one artifact. No test in TCK `1.0.0.alpha3` sends that prefix.
