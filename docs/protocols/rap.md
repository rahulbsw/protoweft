# RAP

`protoweft-rap` speaks the **Reactive Agent Protocol (RAP)** of the
[Hydro project](https://github.com/hydro-project/infinity)'s Infinity agent runtime (AWS and UC
Berkeley, Apache-2.0), in both roles:

- **Client (the runtime role):** `RapClientConnector` uses the tools of RAP tool servers through the
  core `ToolConnector` API.
- **Server (the tool role):** `RapToolServer` serves any `ToolSource` to RAP runtimes such as
  Infinity agents.

RAP has no Java SDK and no versioned specification, so both roles are implemented from the
specification pages and checked against Hydro's reference code (the Rust `rap-protocol` and
`rap-client` crates, the JS `rap-js` library, and the `get-time` and `sandbox-core` example servers),
all at Infinity commit `e1a3e4b`.

!!! note "Which RAP"
    This is the Hydro/AWS Infinity protocol. Other projects use the same acronym; for example, an
    unrelated "RAP" turns event sources into AI directives. Protoweft implements only the Infinity
    protocol.

!!! warning "Maturity"
    - The RAP specification is **unversioned**: messages carry no version field and nothing is
      negotiated. Protoweft records the specification it follows as the wire revision
      `infinity@e1a3e4b` (the Infinity commit).
    - There is no official conformance suite.
    - Open upstream issue [hydro-project/infinity#101](https://github.com/hydro-project/infinity/issues/101)
      proposes renaming `group_id` to `thread_id`, a breaking wire change. Protoweft follows the
      current specification (`group_id`) and will track the rename.
    - **Interoperability is tested both ways** against Hydro's own code at `e1a3e4b`
      (`scripts/interop/rap.sh`): the Protoweft client against five Hydro tool servers (JS
      `get-time`, the Rust stub servers, `sandbox-local`, the steering server and the GitHub
      poller) covering text, image and error results, `view_update`, user choice (answered and
      dismissed), subscriptions with `final`, cancellation and thread closure; and Hydro's Rust
      runtime code (toolset loader, invocation, callback bridge, notifier) against `RapToolServer`,
      covering all of those plus `oauth`. No Hydro tool server sends `oauth` at this commit, so the
      client's OAuth path is tested only against Protoweft's own server. Differences found on
      Hydro's side (for example an event after a cancel, and 404s on the lifecycle routes) are
      listed in the module's `SPEC.md`, section *Interoperability*.

## What RAP is, and isn't

RAP is plain **HTTP + JSON** with an **asynchronous callback** model:

1. The runtime reads a tool server's toolset from `GET {base}/.well-known/rap-toolset`: a name, an
   invocation `endpoint`, and tools with JSON Schema `inputSchema`s.
2. It invokes a tool with `POST {endpoint}`, sending the operation, the arguments, a tool call `id`,
   the conversation thread (`group_id`) and a **`callback_url`** of its own.
3. The tool server acknowledges with **200 at once** and does the work in the background.
4. Later, the tool server `POST`s **callbacks** to the `callback_url`: a `tool_result`, then possibly
   `subscription_event`s, `oauth` prompts or `user_choice` prompts.

Optional routes let the runtime cancel a call (`/cancel_tool_call`) and tell tools a thread closed
(`/close_thread`). The protocol mandates no timeouts, delivers callbacks at least once, processes
them serially per thread, and reports errors as result text starting `"Error: "`, without error
codes. Authentication is left to the deployment.

So, unlike MCP, a RAP client must be **reachable over HTTP** by the tool servers it calls: it hosts
an endpoint for their callbacks. Protoweft never calls a model: RAP's runtime-side features that
feed events to an LLM (the built-in `cancel_subscription` tool, injected `receive_event` calls) are
left to the agent framework; `ToolSubscription` and `ToolEvent` are their equivalents.

## Client

Callbacks need an HTTP endpoint. Mount `RapCallbackEndpoint` on the application's Protoweft server
(the default choice), or start a built-in `RapCallbackListener` when the application has no server.

### Callbacks on the application's server

```java
RapCallbackEndpoint callbacks = RapCallbackEndpoint.create("https://agent.example.com");
ProtoweftServer host = ProtoweftServer.builder().port(8080)
        .endpoints(callbacks)                                     // POST /rap/callback/<token>
        .authenticator(RapCallbackEndpoint.exemptCallbacks(authenticator))
        .start();

try (RapClientConnector tools = RapClientConnector.builder("https://tools.example.com")
        .callbacks(callbacks)
        .credentials((protocol, target) -> List.of(new Credential.BearerToken(token)))
        .build()) {
    tools.start();                                                // GET /.well-known/rap-toolset
    ToolOutcome out = tools.invoke(ToolInvocation.of("get_time", Map.of("timezone", "UTC")));
    System.out.println(out.error() ? "failed: " + out.text() : out.text());
}
```

- `create(publicBaseUrl)` takes the URL tool servers reach the application at. Use
  `create(Supplier<String>)` when it's known only later, for example after binding port 0
  (`() -> host.baseUrl()`).
- Each call gets a fresh 256-bit random token, and its callback URL is
  `<publicBaseUrl>/rap/callback/<token>`. The token authenticates the tool server and selects the
  call, so callback requests carry no credentials. **`exemptCallbacks(authenticator)`** wraps the
  host's authenticator so those paths pass and every other request still goes through it.
- One endpoint serves any number of connectors.

### Built-in listener

```java
try (RapCallbackListener callbacks = RapCallbackListener.builder()
             .host("0.0.0.0").port(9000)
             .publicBaseUrl("https://agent.example.com:9000")   // when tool servers see another address
             .start();
     RapClientConnector tools = RapClientConnector.builder("https://tools.example.com")
             .callbacks(callbacks).build()) {
    tools.start();
    System.out.println(tools.invoke(ToolInvocation.of("get_time", Map.of())).text());
}
```

The listener uses the JDK's `com.sun.net.httpserver` (no extra dependency). It binds
`127.0.0.1:0` by default, so only local tool servers can reach it; `port()` and `baseUrl()` report
what it bound. Close it when done.

### Builder options

| Method | Default | Meaning |
|---|---|---|
| `builder(baseUrl)` | — | The tool server; its toolset is read from `<baseUrl>/.well-known/rap-toolset`. |
| `addServer(baseUrl)` | none | Another tool server whose toolset the same connector loads. Tool names must be unique across them. |
| `callbacks(endpoint)` / `callbacks(listener)` | — (required) | Where callbacks arrive. Without one, `build()` fails with `CONFIGURATION`. |
| `timeout(duration)` | 10 min | How long `invoke` waits for the result. |
| `requestTimeout(duration)` | 30 s | Timeout of each HTTP request to a tool server. |
| `credentials(provider)` | none | Asked with the server's base URL as target for that origin, else with the request URL. `BearerToken` → `Authorization: Bearer`; `NamedSecret` → a header of that name. |
| `authorizer(httpAuthorizer)` | none | Consulted on every request (it overrides `Authorization`) and once more on 401/403. |
| `promptHandler(handler)` | none | Shows tools' authorization and choice [prompts](#prompts). |
| `store(store)` | `InMemoryRapStore` | Where pending calls are kept; see [Durable state](#durable-state-and-recovery). |
| `storeKey(key)` | the first base URL | This connector's key in a shared store. Keep it stable across restarts. |
| `threadId(id)` | a random `pw-…` id | The default thread (`group_id`). |
| `retry(policy)` | 3 attempts, 200 ms doubling to 5 s | Retries of invocations and choice answers on 5xx or connection failure (`RapRetryPolicy`). |
| `dedupRetention(duration)` | 1 h | How long processed callbacks and finished calls are remembered for de-duplication. |
| `executor(executor)` | a private daemon pool | Runs acknowledgements, prompts and notifications. |
| `httpClient(client)` | a JDK client, HTTP/1.1 | It should not follow redirects. |
| `onRecoveredResult(listener)` | none | See [Durable state](#durable-state-and-recovery). |

`build()` creates the connector (and reattaches stored calls); `start()` loads every toolset.
Toolsets are validated whole: an invalid one fails `start()` with `PROTOCOL`, and a tool defined by
two servers is made available from neither (`rejectedTools()` says why). Tool `annotations` become
`ToolSpec` metadata under `RapClientConnector.META_ANNOTATIONS`; `displayScript` is ignored.

### invoke and invokeAsync

| Method | Behavior |
|---|---|
| `invoke(invocation[, caller])` | Sends the invocation and waits up to `timeout` (default 10 minutes). Then it throws `TIMEOUT` and asks the tool to cancel (best effort). A non-anonymous caller's name is sent as `user_id`. |
| `invokeAsync(invocation[, caller])` | Returns a `CompletableFuture<ToolOutcome>` **without a timeout**. Cancelling the future asks the tool to cancel. |
| `cancelToolCall(threadId, toolCallId)` | Sends `/cancel_tool_call` to every configured server. |

```java
CompletableFuture<ToolOutcome> build = tools.invokeAsync(ToolInvocation.of("build", Map.of("target", "all")));
build.thenAccept(out -> System.out.println(out.text()));
// ...
build.cancel(true);   // POST /cancel_tool_call, best effort
```

How a call ends:

| Situation | Result |
|---|---|
| `tool_result` with `text` or `content` | an outcome: text items → `Content.Text`, images → `Content.Data`, unknown item types → `Content.Unmapped`; `content` supersedes `text` |
| result text starting `"Error: "` (RAP's error convention) | an outcome with `error() == true` |
| invocation answered with any status but 200 or 401/403 (5xx only once the retries are used up) | an error outcome: `Error: the tool server did not accept the invocation (HTTP n)` |
| invocation answered 401/403 | `AUTHENTICATION` (after the `HttpAuthorizer`, if any, had one more try) |
| connection failure or request timeout after the retries | `CONNECTION` or `TIMEOUT` |
| no result within `timeout` (`invoke` only) | `TIMEOUT`, and cancellation is requested |
| unknown tool, or one rejected for a collision | `TOOL_NOT_FOUND` |
| an `oauth` prompt without a prompt handler | `RapAuthorizationRequiredException` (`AUTHENTICATION`) |
| the connector closed while waiting | `CONNECTION` |

`display_as` (UI-only display segments) is dropped. Arguments aren't validated against the tool's
`inputSchema` (the core has no JSON Schema validator); RAP requires tools to validate them.

Callbacks are schema-checked, matched against the call's `group_id` and `id`, de-duplicated (RAP
delivers at least once), and processed one at a time per thread. The callback endpoint answers 200
(processed or duplicate), 400 (malformed), 404 (unknown token or mismatched ids) or 413 (over
32 MiB).

### Subscriptions

A tool can answer with `subscription: true` and keep sending `subscription_event`s afterwards. The
connector turns that into the core's
[`ToolOutcome.subscription()`](../concepts/tools.md#subscriptions): a `ToolSubscription` whose
`events()` is a `Flow.Publisher<ToolEvent>`.

```java
ToolOutcome out = tools.invoke(ToolInvocation.of("watch_repo", Map.of("repo", "acme/app")));
System.out.println(out.text());                           // the first result
out.subscription().ifPresent(subscription -> subscription.events().subscribe(new Flow.Subscriber<ToolEvent>() {
    @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
    @Override public void onNext(ToolEvent event) { System.out.println("event: " + event.text()); }
    @Override public void onError(Throwable error) { System.err.println("lost: " + error); }
    @Override public void onComplete() { System.out.println("done"); }
}));
```

- Each `subscription_event` becomes a `ToolEvent` with one text part and the metadata `associative`
  (the tool asks for the event inline in the subscribing thread rather than in a child thread) and
  `final`. A `final` event is the last one (`ToolEvent.last()`), and the stream completes after it.
- Events are buffered until the one subscriber subscribes.
- `subscription.cancel()`, or cancelling the `Flow.Subscription`, sends `/cancel_tool_call`.
- `subscription.id()` is the tool call id.
- Closing a thread doesn't end local subscriptions; cancel them yourself.

### Prompts

Tools can ask the user to authorize access (`oauth`) or to choose (`user_choice`). A
`RapPromptHandler` shows them. It runs on the connector's executor, never on a callback thread, so
it may block while the user decides.

```java
RapPromptHandler prompts = new RapPromptHandler() {
    @Override
    public void onAuthorization(String authUrl, RapCall call) {
        System.out.println(call.operation() + " needs your permission: " + authUrl);
    }

    @Override
    public int onChoice(String prompt, List<String> choices, int defaultIndex, RapCall call) {
        System.out.println(prompt + " " + choices + " -> " + choices.get(defaultIndex));
        return defaultIndex;
    }
};
RapClientConnector tools = RapClientConnector.builder("https://tools.example.com")
        .callbacks(callbacks).promptHandler(prompts).build();
```

| Prompt | With a handler | Without one |
|---|---|---|
| `oauth` | `onAuthorization` shows the URL; the call stays pending until the tool, having completed its own OAuth flow, sends the result. | The call fails with `RapAuthorizationRequiredException` (`AUTHENTICATION`), carrying `authUrl()` and `call()`. If the user authorizes anyway, the late result goes to `onRecoveredResult`. |
| `user_choice` | `onChoice` returns the chosen index; the answer is posted to the tool's `response_url`. | The tool's default is sent, as if the user dismissed the prompt. |

An exception or an out-of-range index from `onChoice` also counts as dismissal. Authorization URLs
that aren't `https` are refused. Don't store authorization URLs beyond showing them.

### Threads

RAP groups calls into conversation threads (`group_id`). Calls on the connector itself use its
default thread; `onThread` returns a `RapThread`, a cheap view whose calls carry another thread id
(and `thread_ancestors` for child threads). A `RapThread` is itself a `ToolSource`.

```java
RapThread conversation = tools.onThread("conversation-42");
conversation.invoke(ToolInvocation.of("get_time", Map.of()));
RapThread child = tools.onThread("conversation-42-research", List.of("conversation-42"));
conversation.close();          // POST /close_thread to every server; the future never fails
```

`closeThread(id)` and `RapThread.close()` tell every configured server, concurrently and once each.
Tool servers then end that thread's subscriptions.

### Durable state and recovery

Pending calls, processed-callback keys and (on the server) active subscriptions live in a
`RapStore`. The default `InMemoryRapStore` loses them on restart; `FileRapStore` keeps them in one
JSON file (`rap-store.json`), replaced atomically under a file lock, owner-readable only on POSIX
because callback URLs are secrets. Several connectors and servers may share one store.

```java
RapStore store = new FileRapStore(Path.of("/var/lib/agent/rap"));
RapClientConnector tools = RapClientConnector.builder("https://tools.example.com")
        .callbacks(callbacks)
        .store(store).storeKey("tools")
        .onRecoveredResult(r -> System.out.println(r.call().operation() + " finished: " + r.outcome().text()))
        .build();
for (RapRecoveredSubscription recovered : tools.recoveredSubscriptions()) {
    recovered.subscription().events().subscribe(subscriber);
}
```

After a restart:

- Stored calls are reattached when the connector is **built**, so their callbacks are accepted
  even before `start()`. The callback host must come back at the **same public URL**.
- Results whose caller is gone (sent before the restart, or failed locally for authorization) go
  to `onRecoveredResult`, held until a listener is set.
- Subscriptions confirmed before the restart come back as `recoveredSubscriptions()`.
- Prompts in flight (OAuth, user choice) are not resumed.

Closing a connector fails waiting callers with `CONNECTION` but keeps the stored calls for the next
instance. There is no JDBC store yet.

## Server

`RapToolServer` publishes any `ToolSource`. It is a `ServerEndpointProvider`, so any Protoweft host
mounts it behind the host's `InboundAuthenticator`.

```java
RapToolServer rap = RapToolServer.builder(tools)
        .name("weather")
        .description("Weather tools")
        .basePath("/rap")
        .store(new FileRapStore(Path.of("/var/lib/weather/rap")))
        .build();
ProtoweftServer.builder().port(8080).endpoints(rap).authenticator(authenticator).start();
```

| Route (under the base path) | Meaning |
|---|---|
| `GET /.well-known/rap-toolset` | The toolset: `name`, `description`, `endpoint` (`<base>/invoke`) and the tools. |
| `POST /invoke` | An invocation: answered 200 at once, then run on the executor; the result goes to `callback_url`. |
| `POST /close_thread` | Ends the thread's subscriptions, each with an empty `final` event. |
| `POST /cancel_tool_call` | Cancels a running call or a subscription (no further events). |
| `POST /user_choice_response` | The runtime's answer to a `user_choice` prompt. |

All routes share one `InboundAuthFilter`, so the authenticator that protects invocations also
protects `/close_thread` and `/cancel_tool_call`, as RAP requires. The caller reaches tools as
`CallerIdentity`; an invocation's `user_id` is added as attribute `rap.userId`
(`RapToolServer.USER_ID_ATTRIBUTE`). It is the runtime's claim, not an authentication.

| Builder method | Default | Meaning |
|---|---|---|
| `name(name)` | `protoweft` | The toolset name, 1 to 128 characters. |
| `description(text)` | none | The toolset description. |
| `basePath(path)` | the root | Mount every route under this path, for example `/rap`. |
| `baseUrl(url)` / `baseUrl(Supplier<String>)` | derived from each request | The public base URL (where `/.well-known/rap-toolset` lives), used for the `endpoint` and choice `response_url`s. |
| `store(store)`, `storeKey(key)` | in memory; the toolset name | Where active subscriptions are kept. |
| `executor(executor)` | a private daemon pool | Runs tool calls. |
| `callbackRetry(policy)` | 5 attempts, 500 ms doubling to 30 s | Retries of callbacks on 5xx, connection failures and timeouts; 4xx isn't retried. |
| `requestTimeout(duration)` | 30 s | Timeout of each callback request. |
| `callbackPolicy(policy)` | `RapCallbackPolicy.DEFAULT` | Which callback URLs are accepted; see [below](#callback-url-policy). |
| `callbackAuthorizer(authorizer)` | none | An `Authorization` header for callbacks, for runtimes that want one. |
| `choiceTimeout(duration)` | 10 min | How long `askUser` waits before using the default. |
| `httpClient(client)` | a JDK client, HTTP/1.1 | It should not follow redirects. |

Results map back to RAP as `text`, or as `content` when there is an image; other parts become text.
A failing tool, a bad argument or an unknown operation becomes a `tool_result` whose text starts
`"Error: "`; unexpected exceptions are reported by class name only. Invocations without a usable
`id`, `group_id` or `callback_url` get 400, because no result could be delivered. Tool names are
published with every character outside `[A-Za-z0-9_-]` replaced by `_` (registry `local.echo` →
`local_echo`), and neutral `ToolSpec` metadata as `x-protoweft-<key>` annotations.

`close()` stops the server's executors; stored subscriptions remain for the next instance.

### Tools that use RAP: RapCallContext

A tool reaches RAP features through `RapCallContext`, a per-call [tool context](../concepts/tools.md#per-call-tool-contexts).
An `@AgentTool` method declares it as a parameter; it isn't part of the tool's schema.

```java
public class Repositories {

    @AgentTool(description = "Watches a repository and reports new commits")
    public String watch(@ToolParam(description = "owner/name") String repo, RapCallContext rap) {
        if (!rap.available()) {
            return "Watching needs RAP";                 // served over MCP, UTCP or called directly
        }
        RapSubscription commits = rap.subscribe();       // the result goes out with subscription: true
        Thread poller = new Thread(() -> {
            commits.emit("New commit on " + repo);
            commits.emit("Build passed on " + repo, true);   // associative
            commits.close("Stopped watching " + repo, false);
        });
        poller.start();
        commits.onCancel(poller::interrupt);
        return "Watching " + repo;
    }

    @AgentTool(description = "Deploys to an environment after the user confirms")
    public String deploy(@ToolParam(description = "Environment") String environment, RapCallContext rap) {
        int choice = rap.askUser("Deploy to " + environment + "?", List.of("Deploy", "Cancel"), 1);
        return choice == 0 ? "Deployed to " + environment : "Cancelled";
    }
}
```

| Method | Meaning |
|---|---|
| `subscribe()` | Makes the call a subscription and returns its `RapSubscription` (the same one if called again). The subscription is stored before the result is delivered. |
| `requestAuthorization(httpsUrl)` | Sends an `oauth` message. The tool completes the OAuth flow itself (its own redirect endpoint) and then returns its result. |
| `askUser(prompt, choices, default[, timeout])` | Sends a `user_choice` and waits for the answer; the default after the timeout (default `choiceTimeout`). An out-of-range answer, an undeliverable prompt or a cancellation throws, which becomes an `"Error: "` result. One prompt at a time per call. |
| `isCancelled()`, `onCancel(action)` | The runtime asked to cancel this call. |
| `operation()`, `threadId()`, `toolCallId()`, `userId()`, `threadAncestors()` | The invocation's facts. |

`RapSubscription` sends events: `emit(text)`, `emit(text, associative)`, `emit(content, associative)`
(non-text parts become text placeholders), `close([text, associative])` for the `final` event,
`isActive()` and `onCancel(action)`. `emit` returns `false` once the subscription has ended.
Events emitted before the result is delivered are held and sent right after it, in order.

A programmatic tool gets the context from its `ToolContexts`:

```java
LocalTools countdown = LocalTools.builder()
        .tool("countdown", "Counts down from three", Map.of("type", "object"),
              (arguments, caller, progress, contexts) -> {
                  RapCallContext rap = RapCallContext.from(contexts);
                  RapSubscription ticks = rap.subscribe();
                  ticks.emit("2");
                  ticks.emit("1");
                  ticks.close("liftoff", false);
                  return ToolOutcome.text("3");
              })
        .build();
```

Called over another protocol, `RapCallContext.from(contexts)` (and an injected parameter) is a
stand-in whose `available()` is `false` and whose other methods throw `UNSUPPORTED`.

A source outcome that already carries a `ToolSubscription`, for example from a `RapClientConnector`
served again, is bridged: its events become `subscription_event`s, and a runtime cancel cancels it.

### Resuming subscriptions after a restart

With a durable store, `recoveredSubscriptions()` returns a `RapSubscription` for each subscription
stored before the restart. Resume them by emitting again:

```java
for (RapSubscription subscription : rap.recoveredSubscriptions()) {
    subscription.emit("Resumed watching (" + subscription.operation() + ")");
}
```

A subscription that is never resumed still ends on `/close_thread` (with a `final` event) or
`/cancel_tool_call`. Prompts in flight and running non-subscription calls are not resumed.

### Callback-URL policy

The RAP specification says nothing about which callback URLs a tool server should post to. An
authenticated caller could otherwise make the server send requests to internal addresses (cloud
metadata at `169.254.169.254`, private ranges): a server-side request forgery. Protoweft therefore
checks every `callback_url` and rejects a refused one with 400.

`RapCallbackPolicy.DEFAULT` allows:

- `https` to any host, except literal link-local, wildcard and multicast addresses;
- `http` only to `localhost` or a literal loopback address.

Host names aren't resolved, so a name that resolves to an internal address isn't detected. Extend
the policy for known hosts, or replace it with any lambda:

```java
RapToolServer rap = RapToolServer.builder(tools)
        .callbackPolicy(RapCallbackPolicy.DEFAULT.allowingHosts("runtime.internal"))
        .build();
```

## Spring Boot

The starter declares RAP connectors under `protoweft.rap.servers.<name>`, mounts their callback
endpoint on the application's servlet container (or starts the built-in listener), provides the
`RapStore`, uses a `RapPromptHandler` bean, and serves tools with `protoweft.server.rap.enabled`.
See [Spring Boot: RAP](../integrations/spring-boot.md#rap).

## Errors

| Situation | Result |
|---|---|
| Tool reports a failure (`"Error: "` text), or the invocation is answered with another status (5xx after the retries) | failure outcome |
| Toolset or invocation answered 401/403 | `AUTHENTICATION` |
| Tool requests authorization and no prompt handler is set | `RapAuthorizationRequiredException` (`AUTHENTICATION`) |
| Invalid toolset, other discovery status | `PROTOCOL` |
| No callback hosting, not started, already started, closed | `CONFIGURATION` |
| Unknown or rejected tool | `TOOL_NOT_FOUND` |
| Connection failure (after the retries, for invocations), connector closed while waiting | `CONNECTION` |
| No result within `timeout`, request timeout (after the retries, for invocations) | `TIMEOUT` |
| A `RapCallContext` method called over another protocol | `UNSUPPORTED` |

## Coverage

Client and server report the same feature ids, in `RapFeatures`. `compliance()` has `sdkName`
`none`, `sdkVersion` the module version, and `wireRevisions` `[infinity@e1a3e4b]`; after `start()`
the client's negotiated revision is that same value, since RAP negotiates nothing.

| Supported | Not implemented |
|---|---|
| `toolset/discovery`, `tool/invocation` | `view_update` (acknowledged and ignored) |
| `tool_result/text`, `tool_result/content` | `migration` (`/migrate`, `needsMigration`) |
| `subscription_event` | `tool_result/display_as` (UI-only, dropped) |
| `oauth`, `user_choice` | `toolset/display_script` (frontend-only, ignored) |
| `tool_cancellation`, `thread_closure` | |

Deliberate choices where the specification is silent or a SHOULD isn't followed:

- Connection failures are exceptions, not error results, as everywhere in Protoweft (the
  specification says runtimes SHOULD record them as tool results; that is left to the agent
  framework).
- Arguments aren't validated against `inputSchema` on the client.
- Tool names outside `[A-Za-z0-9_-]` are rejected on load.
- `subscription_event`s carry no id in RAP, so they are delivered as received (at least once).
- The callback-URL policy above.
