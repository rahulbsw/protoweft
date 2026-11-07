---
id: protoweft-rap
type: module-design
status: draft
title: protoweft-rap — Reactive Agent Protocol client, tool server and durable store (from the spec)
parent: protoweft-architecture
---

# protoweft-rap

## Responsibility

Speaks the Reactive Agent Protocol (RAP) of the Hydro project's Infinity agent runtime (AWS and UC
Berkeley, Apache-2.0) in both roles. RAP is plain HTTP + JSON: a runtime discovers a toolset,
POSTs an invocation, the tool acknowledges with 200 and later POSTs callbacks (result,
subscription events, OAuth and user-choice prompts) to a URL the runtime chose.

- **Client (runtime role):** `RapClientConnector` exposes RAP tool servers through the core
  `ToolConnector` API, hosting callbacks on a mounted `RapCallbackEndpoint` or a built-in
  `RapCallbackListener`.
- **Server (tool role):** `RapToolServer` serves any core `ToolSource` to RAP runtimes; tools use
  the injectable `RapCallContext` for subscriptions, prompts and cancellation.
- **Durable state:** the `RapStore` SPI (`InMemoryRapStore`, `FileRapStore`) keeps pending calls,
  processed-callback keys and active subscriptions across restarts.

There is no Java SDK and no versioned spec, so both roles are implemented from the spec pages and
checked against the reference code (Rust `rap-protocol`/`rap-client`, JS `rap-js`, example
servers `get-time` and `sandbox-core`), all at Infinity commit `e1a3e4b`.

## Boundary

- Public API: `RapClientConnector` (+ `Builder`), `RapThread`, `RapCallbackEndpoint`,
  `RapCallbackListener` (+ `Builder`), `RapPromptHandler`, `RapCall`, `RapRecoveredResult`,
  `RapRecoveredSubscription`, `RapAuthorizationRequiredException`, `RapRetryPolicy`,
  `RapToolServer` (+ `Builder`), `RapCallContext`, `RapSubscription`, `RapCallbackPolicy`,
  `RapStore` (+ `ClientCall`, `ServerSubscription`), `InMemoryRapStore`, `FileRapStore`,
  `RapFeatures`. Everything else is package-private; no Jackson or servlet type in a public
  signature.
- Depends on `protoweft-core`, `protoweft-server` (`ServerEndpointProvider`, `InboundAuthFilter`),
  Jackson 3 (`implementation`), servlet API (`compileOnly`). HTTP is `java.net.http`; the built-in
  listener is the JDK's `com.sun.net.httpserver`. No other dependency.
- Uses two core additions made for RAP (next section). They are protocol-neutral; RAP is their
  first user.

## Core additions (protoweft-core)

RAP needed two concepts the core lacked. Subscriptions were made a core concept by user decision,
an explicit exception to architecture principle 9 (no protocol concept in core until two
protocols need it); the context SPI keeps every RAP type out of core.

- **Subscriptions.** `ToolEvent(content, last, metadata)` and `ToolSubscription` (`id()`,
  `events()` — a `Flow.Publisher<ToolEvent>` with one subscriber, buffered until subscribed,
  completing after a `last` event or `cancel()`; cancelling the `Flow.Subscription` cancels the
  subscription — `cancel()`, `isActive()`). `BufferedToolSubscription` is the producer side
  (`emit`, `complete`, `fail`), delivering in order on the emitting or requesting thread.
  `ToolOutcome` gained the record component `subscriptionHandle`, read through
  `Optional<ToolSubscription> subscription()` and set with `withSubscription(..)`; the
  three-argument constructor and the factories are unchanged. `ToolRegistry`, `LocalTools` and
  discovery's `CapabilityCatalog.invoke` return the source's outcome object, so the subscription
  passes through (core test `ToolSubscriptionTest`).
- **Per-call tool contexts.** `ToolContext` is a marker interface for protocol-specific per-call
  contexts; `ToolContexts` is the immutable per-call set, keyed by interface type;
  `ToolSource.invoke(invocation, caller, progress, contexts)` is a new default method (it drops
  the contexts and calls the three-argument form), overridden by `LocalTools` and `ToolRegistry`
  to pass them on. `LocalTools` injects any `@AgentTool` parameter whose type extends
  `ToolContext` (it must be an interface) and leaves it out of the schema, like `CallerIdentity`
  and `ToolProgress`; programmatic tools use `ContextualToolHandler` (args, caller, progress,
  contexts). A context the serving protocol does not provide resolves to a stand-in proxy whose
  `available()` is `false` and whose other methods throw `UNSUPPORTED`. `LocalTool`'s handler
  component is now a `ContextualToolHandler`; its `ToolHandler` and `ProgressAwareToolHandler`
  constructors remain.

## Decisions

### Client

- **One connector, one default thread.** `builder(baseUrl)` loads `<baseUrl>/.well-known/rap-toolset`
  at `start()`; `addServer(..)` adds more toolsets to the same connector (the spec's runtime
  is configured with a list of servers, `basic/toolsets.md:162`). Calls run in the connector's
  thread id (`group_id`; random `pw-…` unless `threadId(..)` is set); `onThread(id[, ancestors])`
  returns a `RapThread` view sending that `group_id` and `thread_ancestors`. A non-anonymous
  caller's name is sent as `user_id`. Tool call ids are `pw_<uuid>`; `call_id` is sent as `null`.
- **Callbacks.** Each call gets a fresh 256-bit random token; its callback URL is
  `<publicBaseUrl>/rap/callback/<token>` (the spec allows tokens in the path,
  `basic/transport.md:83`). The token selects the call; then the message is schema-checked, its
  `group_id` and `id` (`tool_call_id` for events) must match the call, it is de-duplicated, and it
  is processed under a per-`group_id` lock shared by all connectors in the JVM. Answers: 200
  processed or duplicate, 400 malformed, 404 unknown token or mismatched ids, 413 over 32 MiB.
  A token whose call finished, was cancelled or timed out is remembered for `dedupRetention`
  (default 1 h) and answered 200 without effect. `RapCallbackEndpoint` is a
  `ServerEndpointProvider` (`/rap/callback/*`, one per application, any number of connectors);
  since the host's authenticator also runs before it, `RapCallbackEndpoint.exemptCallbacks(auth)`
  lets those paths through. `RapCallbackListener` binds `127.0.0.1:0` by default, with an optional
  public base URL.
- **De-duplication.** Keys are recorded in the `RapStore`: `tool_result` by token + type + id;
  `oauth` and `user_choice` by token + type + id + a digest of the message, so an identical
  retry is dropped but a second, different prompt in the same call is not. A duplicate
  `tool_result` of a confirmed subscription is ignored. `subscription_event` has no id in the
  spec, so events are delivered as received (at least once).
- **Acknowledgement.** 200 → wait for the callback. 5xx and connection failures → retried per
  `RapRetryPolicy` (default 3 attempts, 200 ms doubling to 5 s). 401/403 → the `HttpAuthorizer`
  may obtain new credentials once, else `AUTHENTICATION`. Any other status (including other 2xx)
  → an error outcome `"Error: the tool server did not accept the invocation (HTTP n)"`. A
  connection or timeout failure after the retries is a `ProtoweftException` (`CONNECTION`/`TIMEOUT`),
  following Protoweft's rule that a call that could not be carried out is an exception, not an
  outcome.
- **Results.** `content` supersedes `text`; text items → `Content.Text`, image items →
  `Content.Data(mediaType, data)`, unknown item types → `Content.Unmapped`. A result whose text
  starts with `"Error: "` is an error outcome. `display_as` is dropped (UI-only) and declared
  unsupported. `subscription: true` → `ToolOutcome.subscription()`; each `subscription_event`
  becomes a `ToolEvent` with one text part and metadata `associative` and `final`; `final`
  completes the stream and ends tracking.
- **Timeouts and cancellation.** `invoke` waits `timeout` (default 10 min), then throws `TIMEOUT`
  and sends `/cancel_tool_call`; `invokeAsync` has no timeout and cancelling its future sends the
  cancellation. `ToolSubscription.cancel()` sends it too. Cancellation and `closeThread(id)` go to
  every configured server (plus the call's own), concurrently and once each; their futures never
  fail. Closing a thread does not end local subscriptions (`server/subscription-events.md:163`);
  the application cancels them.
- **Prompts.** `RapPromptHandler` runs on the connector's executor, never on a callback thread.
  `oauth`: with a handler the URL is shown and the call stays pending; without one the call fails
  with `RapAuthorizationRequiredException` (`AUTHENTICATION`, carrying `authUrl()`), and if the
  tool later delivers a result it goes to `onRecoveredResult`. Non-https `auth_url`s are rejected
  (400). `user_choice`: the handler's index, or the default when there is no handler, it throws,
  or it returns an out-of-range index (dismissal); the answer `{"id","selected"}` is POSTed to
  `response_url` with the invocation retry policy.
- **Credentials.** `CredentialProvider` is asked with the server's base URL as target for requests
  to that origin (discovery, invocation, notifications, a same-origin `response_url`), else with
  the request URL, so credentials never follow a manifest or prompt to another origin by
  accident. `BearerToken` → `Authorization: Bearer`, `NamedSecret` → that header;
  `HttpAuthorizer.authorization(uri)` overrides `Authorization`.
- **Arguments are not validated against `inputSchema`** (a SHOULD, `basic/tool-invocation.md:71`):
  the core has no JSON Schema validator and the module adds no dependency; tools must validate
  (a MUST for them, `basic/tool-invocation.md:85`).

### Server

- **Routes**, under an optional `basePath`: `GET /.well-known/rap-toolset`, `POST /invoke` (the
  manifest `endpoint`, as `sandbox-core`), `/close_thread`, `/cancel_tool_call`,
  `/user_choice_response`; one `ServletEndpoint` named `rap` (or `rap:<basePath>`). The base URL
  is configured or derived from the request.
- **Authentication.** All routes share the endpoint's `InboundAuthFilter`, so the authenticator
  that protects invocations also protects `/close_thread` and `/cancel_tool_call`, which the spec
  requires to be authenticated (`basic/thread-closure.md:84`, `basic/tool-cancellation.md:89`)
  "by the same mechanism used for tool invocations". With `InboundAuthenticator.ALLOW_ALL` they
  are as open as the invocations are. The caller reaches tools as `CallerIdentity`, with the
  invocation's `user_id` added as attribute `rap.userId` (`RapToolServer.USER_ID_ATTRIBUTE`; also `RapCallContext.userId()`); the
  `user_id` is the runtime's claim, not an authentication.
- **Invocation.** Missing or invalid `id`, `group_id` or `callback_url`, or a refused callback URL
  → 400 (no error result can be routed). Otherwise 200, then the call runs on the executor; a
  bad `operation`, `arguments`, `user_id` or `thread_ancestors`, an unknown operation, a tool
  failure or a `ProtoweftException` all become a `tool_result` whose text starts `"Error: "`.
  Unexpected exceptions are reported by class name only (no stack traces,
  `basic/tool-result.md:227`). A re-sent invocation with the same (`group_id`, `id`) while the
  first still runs is acknowledged and ignored.
- **Result mapping.** Text parts → `text`; when there is an image, `content` with text and image
  items; other parts become text (a text `Resource` as URI then body; binary bodies as markers);
  structured content without parts → its JSON as `text`; errors always as `text`. `call_id` is
  echoed when sent.
- **Callback delivery.** Per call, messages are delivered one after another in order (result
  first, then held events), on a delivery pool separate from the tool executor. 5xx, connection
  errors and timeouts are retried (default 5 attempts, 500 ms doubling to 30 s); 4xx is not.
  `callbackAuthorizer(..)` adds an `Authorization` header for runtimes that want one.
- **Callback URL policy (spec silent; Protoweft decision).** `RapCallbackPolicy.DEFAULT`: `https`
  to any host except literal link-local, wildcard or multicast addresses, or `http` to
  `localhost`/a literal loopback address; no DNS resolution. `allowingHosts(..)` extends it;
  any `Predicate`-style lambda replaces it. Reason: an authenticated caller must not be able to
  make the server POST to internal addresses (cloud metadata at 169.254.169.254, private ranges).
  Host names that resolve to internal addresses are not detected (documented limit).
- **Names.** Published names replace `[^A-Za-z0-9_-]` with `_` (registry `local.echo` →
  `local_echo`); collisions or names over 128 characters fail the manifest (500) and invocations;
  invocations map back through the same table. Neutral `ToolSpec` metadata is published as
  namespaced annotations `x-protoweft-<key>` (`basic/toolsets.md:86`), merged with any
  `rapAnnotations` metadata; the client maps them back.
- **`RapCallContext`.** `subscribe()` (stores the subscription, then the result carries
  `subscription: true`; events emitted before the result is delivered are held), `RapSubscription`
  (`emit(text|content, associative)`, `close([text, associative])` sends `final: true`,
  `onCancel`), `requestAuthorization(httpsUrl)` (an `oauth` message; the tool finishes the OAuth
  flow itself and then returns), `askUser(prompt, choices, default[, timeout])` (a `user_choice`
  with `response_url = <base>/user_choice_response`; timeout → default, default timeout 10 min;
  an out-of-range answer, an undeliverable prompt or a cancellation throw, which becomes an
  `"Error: "` result), `isCancelled()`/`onCancel()`. Choices are keyed by tool call id, as
  `sandbox-core` does; a second concurrent `askUser` in one call is refused.
- **Re-served subscriptions.** A source outcome that carries a `ToolSubscription` (for example a
  `RapClientConnector` served again) is bridged: its events become `subscription_event`s and a
  runtime cancel cancels it.
- **Cancellation and thread closure.** `/cancel_tool_call`: a running call's `isCancelled()` turns
  true, its `onCancel` hooks run and a pending `askUser` fails; an active subscription stops
  without sending anything more (`server/subscription-events.md:173`) and is removed from the
  store. `/close_thread`: every subscription of that thread ends with an empty `final` event (so
  runtimes that still track it stop), its hooks run, and it is removed. Both answer 200 at once
  (unknown ids included) and work in the background; malformed bodies get 400.

### Durable store

- `RapStore` holds client `ClientCall`s (owner/store key, token, server URL, thread, tool call id,
  operation, created, subscription flag), processed-callback keys with timestamps, and server
  `ServerSubscription`s (server key, thread, tool call id, operation, callback URL, caller, user
  id, created). Store keys default to the first base URL (client) and the toolset name (server).
- `FileRapStore` keeps one `rap-store.json`; every change re-reads it, applies the change and
  replaces it atomically (temp file, fsync, `ATOMIC_MOVE`), under an exclusive `FileLock` on
  `rap-store.lock` plus a JVM-wide lock per directory. Files are owner-only on POSIX because
  callback URLs are secrets (`basic/lifecycle.md:190`). No JDBC store yet.
- **Client restart.** Stored calls are reattached when the connector is *built* (so callbacks are
  accepted even before `start()`): results go to `onRecoveredResult` (held until a listener is
  set), confirmed subscriptions to `recoveredSubscriptions()`. The callback host must come back at
  the same public URL. Closing a connector fails waiting callers with `CONNECTION` but keeps the
  stored calls.
- **Server restart.** `RapToolServer.recoveredSubscriptions()` returns `RapSubscription` handles
  for stored subscriptions; the application resumes emitting. One never resumed still ends on
  `/close_thread` (final event) or `/cancel_tool_call` (no event).
- Prompts in flight (OAuth, user choice) and running non-subscription calls are not resumed after
  a server restart.

## Spec rules implemented

Paths are under `docs/docs/rap/spec/` at Infinity `e1a3e4b`.

- Encoding and transport: JSON, UTF-8, `Content-Type: application/json` on every POST
  (`basic/transport.md:11,26,62`; `basic/tool-invocation.md:12`; `basic/tool-result.md:12`).
- Toolsets: fields and limits (`basic/toolsets.md:27,29,30,56-58,64`), annotations opaque and
  never a load failure (`:84`), display script cosmetic (`:107`, ignored), discovery only from
  `{base}/.well-known/rap-toolset` (`:111,142,162`), whole-toolset validation with no partial
  loading (`:170-172`), cross-toolset collisions make neither tool available (`:138`), cached for
  the connector's lifetime (`:166`).
- Invocation: fields (`basic/tool-invocation.md:36-45`), runtime checks the operation is known
  and generates a unique id, valid callback URL, `group_id`, `thread_ancestors` (`:71`); tool
  acks 200 without blocking and processes asynchronously (`:49,55,61-62`;
  `basic/lifecycle.md:52-55,73`), validation failures are acked and reported as results
  (`basic/tool-invocation.md:75`; `basic/lifecycle.md:75`), non-200 is recorded as an error
  result (`basic/tool-invocation.md:79`), runtime retries 5xx and never 4xx
  (`basic/transport.md:26`).
- Results: fields, `text` or `content`, `content` supersedes (`basic/tool-result.md:34-41`),
  content items (`:72-101`), `display_as` presentation-only (`:109`), errors as `"Error: "`
  text (`:210,227`), unmatched results discarded and never injected (`:236,240`;
  `basic/lifecycle.md:182`), idempotent processing (`basic/tool-result.md:242`;
  `basic/lifecycle.md:184`), every invocation produces a result (`basic/lifecycle.md:89`;
  `basic/tool-invocation.md:65`), `group_id` and `id` echoed (`basic/lifecycle.md:91`),
  `call_id` echoed (`basic/tool-result.md:37`).
- Callbacks: schema validation and pending-call check (`basic/transport.md:87`), token in the
  callback URL (`:83`), tool retries 5xx/timeouts with backoff and not 4xx (`:64`;
  `basic/lifecycle.md:91`), callback URL treated as opaque (`basic/transport.md:68`), runtime
  serial per `group_id` (`basic/lifecycle.md:129`).
- Subscriptions: message and fields (`server/subscription-events.md:41-80`), `final` ends
  tracking and no events follow it (`:82`), tool stores durably then confirms with
  `subscription: true` (`:127,129`; `basic/lifecycle.md:97-103`), runtime records it (`:135`),
  cancellation via `/cancel_tool_call` (`:142`), unknown or cancelled subscriptions' events
  discarded (`:171`), none sent after cancellation (`:173`), resume after restart
  (`basic/lifecycle.md:116`).
- OAuth: message (`server/oauth.md:12,31-35`), runtime surfaces the URL and keeps the call
  pending without retrying (`:69,71`), `auth_url` must be https (`:102`), runtime doesn't store it
  (`:104`; it is only in the exception and handler call).
- User choice: message, non-empty choices, valid default (`server/user-choice.md:32-41`),
  response `{id, selected}` to `response_url` (`:45-62`), dismissal sends the default (`:97`),
  failure becomes an error result (`:111`).
- Thread closure: POST `{"thread_id"}` to `/close_thread` relative to the base URL
  (`basic/thread-closure.md:14,25,31`), always 200 and empty (`:35,41,86`), runtime single shot,
  concurrent, to every server, non-blocking (`:47,78,80`), idempotent (`:50`), cleanup in the
  background (`:63`), authenticated (`:84`), `thread_id` validated (`:88`).
- Tool cancellation: POST `{"thread_id","tool_call_id"}` to `/cancel_tool_call`
  (`basic/tool-cancellation.md:14,26,32-33`), always 200 (`:37,43`), single shot (`:49`),
  results after cancellation tolerated (`:53,65`), to every server, concurrently (`:83,85`),
  authenticated (`:89`), ids validated (`:93`).
- Timeouts: the protocol has none; the client's application-level timeout is documented here
  (`basic/lifecycle.md:174-176`).

## Deviations and gaps

- **Not applicable to a library:** the runtime's built-in `cancel_subscription` tool and the
  synthetic `receive_event__injected` calls (`server/subscription-events.md:96-113,139-145`) are
  LLM-runtime concerns. Protoweft never calls a model; `ToolSubscription.cancel()` and
  `ToolEvent` are the equivalents, and the agent framework decides how events reach a model.
- **Unsupported, declared in the report:** `view_update` (acknowledged and ignored;
  `server/view-updates.md`), `/migrate` and `needsMigration` (`basic/migration.md`),
  `display_as`, `displayScript`.
- Tool names outside `[A-Za-z0-9_-]` are rejected on load: the spec forbids "spaces or special
  characters" (`basic/toolsets.md:64`) without defining the latter, so the SHOULD set is enforced.
- Connection failures are exceptions, not error results (see Acknowledgement) — the spec's
  "SHOULD record as tool results" (`basic/lifecycle.md:117`) is left to the agent framework.
- Open upstream issue hydro-project/infinity#101 proposes renaming `group_id` to `thread_id`;
  this module follows the current spec and will track the rename.
- Serial processing per `group_id` uses 256 striped locks (a thread id always maps to the same
  lock, so ordering holds; unrelated threads may occasionally wait on each other).

## Coverage

Feature ids live in `RapFeatures`; tests assert them. Supported: `toolset/discovery`,
`tool/invocation`, `tool_result/text`, `tool_result/content`, `subscription_event`, `oauth`,
`user_choice`, `tool_cancellation`, `thread_closure`. Unsupported: `view_update`, `migration`,
`tool_result/display_as`, `toolset/display_script`. `sdkName` `none`, `sdkVersion` this module's
version (generated from the build), `wireRevisions` `["infinity@e1a3e4b"]` (RAP has no version
field); the client's negotiated revision is that same value after `start()`.

## Verification

`./gradlew :protoweft-rap:test` (in-process, real HTTP):

- `ClientDiscoveryTest` (6): annotations as metadata, invalid manifests rejected whole,
  cross-server collisions, discovery 401/404 and credentials, compliance report, configuration
  errors.
- `ClientInvocationTest` (11) against a scripted JDK-HTTP tool server and the built-in listener:
  invocation body, text/content/image results, `"Error: "`, non-200 acks, 5xx retries, timeout +
  cancellation, async cancel, duplicate callbacks, forged/unknown/mismatched/malformed callbacks,
  serial processing per thread, thread views and notifications.
- `ClientPromptTest` (5): OAuth with and without a handler (recovered late result), user choice
  with a handler, without one (default), and with an invalid answer.
- `ServerTest` (15) on Jetty port 0 against a scripted runtime: manifest and names, results,
  errors, images, 400 rules and the callback policy (10.x, 169.254.169.254, link-local IPv6,
  plain http), callback retries, subscriptions, cancel, thread closure, authentication of every
  route, user choice (answer, timeout, invalid), OAuth, caller and `user_id`, context unavailable
  elsewhere, compliance.
- `RoundTripTest` (8): our client against our server on one Jetty with a mounted
  `RapCallbackEndpoint` behind a bearer authenticator: results, auth failure, subscriptions with
  associative/final, client cancel stops the server, OAuth and user choice with and without
  handlers, thread closure, and a subscription bridged through a re-served connector.
- `DurabilityTest` (4) with `FileRapStore`: client restart receives a late result through
  `onRecoveredResult`; both sides restart and a resumed server subscription reaches the client's
  recovered subscription, whose cancel reaches the server; store persistence and concurrent writers.
- Core: `ToolSubscriptionTest` (5), `ToolContextsTest` (4).

Interop with Hydro's reference implementations (their `get-time` `local.mjs` against our client,
their Rust `rap-client` against our server) is not part of this module's tests.

## Sources

Hydro Infinity @ `e1a3e4b` (https://github.com/hydro-project/infinity): spec pages
`docs/docs/rap/spec/**`; `crates/rap-protocol/src/lib.rs` (wire types); `crates/rap-client/src/`
(`callback_server.rs`, `toolset_loader.rs`, `notifier.rs`, `http.rs`);
`agent/lib/infinity-agents/rap-js/index.mjs`; `agent/lib/toolsets/get-time/get-time-tool/{tool,local}.mjs`;
`crates/sandbox-core/src/server.rs` (routes `/invoke`, `/close_thread`, `/cancel_tool_call`,
`/user_choice_response`).
