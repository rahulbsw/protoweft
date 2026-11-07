//! Direction B of the RAP interop check: Hydro Infinity's own runtime code (at e1a3e4b) driving
//! Protoweft's `RapToolServer`.
//!
//! Everything on the wire goes through Hydro's code paths, not hand-written JSON:
//! - discovery: `rap_client::toolset_loader::ToolsetLoader` (`{base}/.well-known/rap-toolset`);
//! - invocation: `infinity_agent_core::tools::rap_tool::RapTool::execute` (= `invoke_rap_tool`,
//!   the one implementation every Infinity surface uses), with `user_id` and `thread_ancestors`;
//! - callbacks: `infinity_rap_bridge::RapCallbackBridge` (their `callback_server` +
//!   `prepare_callback`/`convert_callback`), so every callback our server sends must parse as a
//!   `rap_protocol::RapCallback` and convert into the runtime's `InputMessage`;
//! - user choice answers: `{"id","selected"}` POSTed to `response_url`, exactly as
//!   `infinity-daemon/src/client_handler.rs` does;
//! - lifecycle: `rap_client::notifier::RapNotifier` (`/cancel_tool_call`, `/close_thread`).
//!
//! Usage: `rap-interop-client --server http://127.0.0.1:PORT [--report results.json]`.
//! Prints `RESULT|B|hydro-rust-runtime|<scenario>|PASS or FAIL|<evidence>` lines; exits 1 if any
//! scenario failed.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use infinity_agent_core::message::{
    InputMessage, InputMessageContent, SyntheticKind, TaggedSyntheticKind,
};
use infinity_agent_core::tools::rap_tool::{RapTool, RapToolDescriptor};
use infinity_agent_core::tools::{Tool, ToolContext};
use infinity_agent_core::traits::InputSender;
use infinity_provider_protocol::message::{ImageMediaType, ImageSource, ToolResultContent, UserContent};
use infinity_rap_bridge::RapCallbackBridge;
use rap_client::http::{HttpClient, InMemoryToolsetCache, SimpleHttpClient};
use rap_client::notifier::RapNotifier;
use rap_client::toolset_loader::ToolsetLoader;
use rap_protocol::{ThreadId, ToolCallId};
use serde_json::{Value, json};
use tokio::sync::Notify;

type BoxError = Box<dyn std::error::Error + Send + Sync>;

const WAIT: Duration = Duration::from_secs(15);

// ── An InputSender that records what Hydro's callback bridge hands to the runtime ──

#[derive(Debug)]
struct Never;
impl std::fmt::Display for Never {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("never")
    }
}
impl std::error::Error for Never {}

/// Messages in arrival order: (arrival sequence number, message, deduplication id).
#[derive(Clone, Default)]
struct Collector {
    messages: Arc<Mutex<Vec<(u64, InputMessage, String)>>>,
    seq: Arc<std::sync::atomic::AtomicU64>,
    notify: Arc<Notify>,
}

#[async_trait]
impl InputSender for Collector {
    type Error = Never;

    async fn send_to_input_queue(&self, message: InputMessage, dedup_id: &str) -> Result<(), Never> {
        let n = self.seq.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        self.messages
            .lock()
            .expect("collector lock")
            .push((n, message, dedup_id.to_owned()));
        self.notify.notify_waiters();
        Ok(())
    }
}

impl Collector {
    /// Removes and returns the first message matching `pred`, waiting up to `timeout`.
    async fn take<F: Fn(&InputMessage) -> bool>(
        &self,
        pred: F,
        timeout: Duration,
    ) -> Option<(InputMessage, String)> {
        self.take_seq(pred, timeout).await.map(|(_, m, d)| (m, d))
    }

    async fn take_seq<F: Fn(&InputMessage) -> bool>(
        &self,
        pred: F,
        timeout: Duration,
    ) -> Option<(u64, InputMessage, String)> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            let notified = self.notify.notified();
            {
                let mut list = self.messages.lock().expect("collector lock");
                if let Some(pos) = list.iter().position(|(_, m, _)| pred(m)) {
                    return Some(list.remove(pos));
                }
            }
            if tokio::time::timeout_at(deadline, notified).await.is_err() {
                return None;
            }
        }
    }

    fn count<F: Fn(&InputMessage) -> bool>(&self, pred: F) -> usize {
        self.count_after(pred, None)
    }

    /// Messages matching `pred` that arrived after arrival number `after` (all if `None`).
    fn count_after<F: Fn(&InputMessage) -> bool>(&self, pred: F, after: Option<u64>) -> usize {
        self.messages
            .lock()
            .expect("collector lock")
            .iter()
            .filter(|(n, m, _)| after.is_none_or(|a| *n > a) && pred(m))
            .count()
    }
}

fn is_result(m: &InputMessage, id: &str) -> bool {
    m.synthetic.is_none()
        && matches!(&m.content, InputMessageContent::User(UserContent::ToolResult(tr)) if tr.id == id)
}

fn sub_event(m: &InputMessage) -> Option<(&str, bool, bool)> {
    match &m.synthetic {
        Some(SyntheticKind::Tagged(TaggedSyntheticKind::SubscriptionEvent {
            tool_call_id,
            associative,
            r#final,
        })) => Some((tool_call_id.as_str(), *associative, *r#final)),
        _ => None,
    }
}

fn is_event(m: &InputMessage, id: &str) -> bool {
    sub_event(m).is_some_and(|(t, _, _)| t == id)
}

fn texts(m: &InputMessage) -> Vec<String> {
    match &m.content {
        InputMessageContent::User(UserContent::ToolResult(tr)) => tr
            .content
            .iter()
            .map(|c| match c {
                ToolResultContent::Text(t) => t.text.clone(),
                ToolResultContent::Image(_) => "<image>".to_owned(),
            })
            .collect(),
        _ => Vec::new(),
    }
}

fn text(m: &InputMessage) -> String {
    texts(m).join("")
}

// ── The harness ──

struct Harness {
    server: String,
    tools: HashMap<String, RapTool<SimpleHttpClient>>,
    endpoint: String,
    callback_url: String,
    collector: Collector,
    notifier: RapNotifier<SimpleHttpClient>,
    http: SimpleHttpClient,
    results: Vec<(String, bool, String)>,
    seq: u32,
}

impl Harness {
    fn record(&mut self, scenario: &str, ok: bool, evidence: impl Into<String>) {
        let evidence = evidence.into().replace('\n', "\\n");
        println!(
            "RESULT|B|hydro-rust-runtime|{scenario}|{}|{evidence}",
            if ok { "PASS" } else { "FAIL" }
        );
        self.results.push((scenario.to_owned(), ok, evidence));
    }

    fn next_id(&mut self, op: &str) -> String {
        self.seq += 1;
        format!("tooluse_hydro_{op}_{}", self.seq)
    }

    /// Invokes through Hydro's `RapTool::execute` (their `invoke_rap_tool`).
    async fn invoke(
        &mut self,
        op: &str,
        args: Value,
        thread_stack: &[&str],
        call_id: Option<&str>,
    ) -> Result<String, BoxError> {
        let id = self.next_id(op);
        let tool = match self.tools.get(op) {
            Some(t) => t.clone(),
            // Not in the manifest: build the same RapTool by hand (to test unknown operations).
            None => RapTool {
                descriptor: RapToolDescriptor {
                    name: op.to_owned(),
                    description: String::new(),
                    parameters: json!({"type": "object"}),
                    display_script: None,
                },
                endpoint: self.endpoint.clone(),
                http_client: self.http.clone(),
                callback_url: Some(self.callback_url.clone()),
            },
        };
        let context = ToolContext {
            message_sender: self.collector.clone(),
            group_id: thread_stack
                .last()
                .copied()
                .expect("thread stack")
                .to_owned()
                .into(),
            callback_url: self.callback_url.clone(),
            user_id: Some("alice".to_owned()),
            thread_stack: thread_stack.iter().map(|t| (*t).to_owned().into()).collect(),
            safe_spawn_point: None,
        };
        Tool::<Collector>::execute(
            &tool,
            args,
            id.clone().into(),
            call_id.map(|c| c.to_owned().into()),
            &context,
        )
        .await?;
        Ok(id)
    }

    async fn result(&self, id: &str) -> Option<(InputMessage, String)> {
        self.collector.take(|m| is_result(m, id), WAIT).await
    }
}

#[tokio::main]
async fn main() -> Result<(), BoxError> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "warn,rap_client=warn".into()),
        )
        .with_writer(std::io::stderr)
        .init();

    let args: Vec<String> = std::env::args().collect();
    let arg = |name: &str| {
        args.iter()
            .position(|a| a == name)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    let server = arg("--server").ok_or("--server <base url> is required")?;
    let report = arg("--report");

    // Callback reception exactly as an Infinity embedding does it.
    let bridge = RapCallbackBridge::bind().await?;
    let callback_url = bridge.callback_url().to_owned();
    let collector = Collector::default();
    let (_views, _task) = bridge.serve_into(collector.clone());

    let http = SimpleHttpClient::new();
    let mut h = Harness {
        server: server.clone(),
        tools: HashMap::new(),
        endpoint: String::new(),
        callback_url,
        collector,
        notifier: RapNotifier::new(vec![server.clone()], SimpleHttpClient::new()),
        http: http.clone(),
        results: Vec::new(),
        seq: 0,
    };

    // 1. Discovery with their ToolsetLoader.
    let loader = ToolsetLoader::new(http.clone(), InMemoryToolsetCache::new());
    match loader
        .load_toolsets(std::slice::from_ref(&server), ThreadId::from_ref("hydro-session"))
        .await
    {
        Ok(mut sets) if sets.len() == 1 => {
            let manifest = sets.remove(0).manifest;
            h.endpoint = manifest.endpoint.clone();
            let names: Vec<String> = manifest.tools.iter().map(|t| t.name.clone()).collect();
            let annotated = manifest
                .tools
                .iter()
                .find(|t| t.name == "echo")
                .and_then(|t| t.annotations.clone());
            for def in manifest.tools {
                h.tools.insert(
                    def.name.clone(),
                    RapTool {
                        descriptor: def.into(),
                        endpoint: manifest.endpoint.clone(),
                        http_client: http.clone(),
                        callback_url: Some(h.callback_url.clone()),
                    },
                );
            }
            let expected = [
                "echo", "image", "fail", "whoami", "ticker", "confirm", "login", "watch", "slow",
            ];
            let missing: Vec<&str> = expected
                .iter()
                .copied()
                .filter(|e| !names.iter().any(|n| n == e))
                .collect();
            h.record(
                "toolset/discovery",
                missing.is_empty() && manifest.endpoint.ends_with("/invoke"),
                format!(
                    "ToolsetManifest '{}' endpoint={} tools={names:?} missing={missing:?} echo.annotations={}",
                    manifest.name,
                    manifest.endpoint,
                    annotated.map(|a| a.to_string()).unwrap_or_else(|| "none".into())
                ),
            );
        }
        Ok(sets) => {
            h.record("toolset/discovery", false, format!("{} toolsets", sets.len()));
            return finish(h, report);
        }
        Err(e) => {
            h.record("toolset/discovery", false, format!("load failed: {e}"));
            return finish(h, report);
        }
    }

    const T1: &str = "hydro-thread-1";

    // 2. Text result, call_id echo, group_id echo, dedup id.
    let id = h
        .invoke("echo", json!({"text": "hello from hydro"}), &[T1], Some("prov-1"))
        .await?;
    match h.result(&id).await {
        Some((m, dedup)) => {
            let call_id = match &m.content {
                InputMessageContent::User(UserContent::ToolResult(tr)) => tr.call_id.clone(),
                _ => None,
            };
            let ok = text(&m) == "hello from hydro"
                && call_id.as_deref() == Some("prov-1")
                && m.group_id.as_str() == T1
                && !m.subscription
                && dedup == format!("rap-tool-result-{id}");
            h.record(
                "tool_result/text",
                ok,
                format!(
                    "text={:?} call_id={call_id:?} group_id={} subscription={} dedup={dedup}",
                    text(&m),
                    m.group_id.as_str(),
                    m.subscription
                ),
            );
        }
        None => h.record("tool_result/text", false, "no tool_result within 15s"),
    }

    // 3. Multimodal content (text + image).
    let id = h.invoke("image", json!({}), &[T1], None).await?;
    match h.result(&id).await {
        Some((m, _)) => {
            let mut desc = Vec::new();
            let mut ok = false;
            if let InputMessageContent::User(UserContent::ToolResult(tr)) = &m.content {
                for c in &tr.content {
                    match c {
                        ToolResultContent::Text(t) => desc.push(format!("text:{:?}", t.text)),
                        ToolResultContent::Image(img) => {
                            let b64 = matches!(&img.data, ImageSource::Base64(d) if d.starts_with("iVBOR"));
                            desc.push(format!("image:{:?} base64={b64}", img.media_type));
                        }
                    }
                }
                ok = tr.content.len() == 2
                    && matches!(&tr.content[0], ToolResultContent::Text(t) if t.text == "A red pixel:")
                    && matches!(&tr.content[1], ToolResultContent::Image(img)
                        if img.media_type == Some(ImageMediaType::PNG)
                        && matches!(&img.data, ImageSource::Base64(d) if d.starts_with("iVBOR")));
            }
            h.record("tool_result/content(image)", ok, desc.join(", "));
        }
        None => h.record("tool_result/content(image)", false, "no tool_result within 15s"),
    }

    // 4. Tool failure → "Error: " text.
    let id = h.invoke("fail", json!({}), &[T1], None).await?;
    match h.result(&id).await {
        Some((m, _)) => {
            let t = text(&m);
            h.record("tool_result/error", t.starts_with("Error: "), format!("text={t:?}"));
        }
        None => h.record("tool_result/error", false, "no tool_result within 15s"),
    }

    // 5. Unknown operation → acknowledged, then an "Error: " result.
    let id = h.invoke("no_such_tool", json!({}), &[T1], None).await?;
    match h.result(&id).await {
        Some((m, _)) => {
            let t = text(&m);
            h.record("tool_result/unknown_operation", t.starts_with("Error: "), format!("text={t:?}"));
        }
        None => h.record("tool_result/unknown_operation", false, "no tool_result within 15s"),
    }

    // 6. user_id and thread_ancestors from Hydro's thread stack.
    let id = h
        .invoke("whoami", json!({}), &[T1, "hydro-thread-1-child"], None)
        .await?;
    match h.result(&id).await {
        Some((m, _)) => {
            let t = text(&m);
            h.record(
                "invocation/user_id+thread_ancestors",
                t == "alice|hydro-thread-1",
                format!("text={t:?} (expected \"alice|hydro-thread-1\")"),
            );
        }
        None => h.record("invocation/user_id+thread_ancestors", false, "no tool_result within 15s"),
    }

    // 7. Subscription: confirmed result, 3 events, then a final event.
    let id = h.invoke("ticker", json!({"count": 3}), &[T1], None).await?;
    match h.result(&id).await {
        Some((m, _)) => {
            let confirmed = m.subscription;
            let mut events = Vec::new();
            for _ in 0..4 {
                match h.collector.take(|m| is_event(m, &id), WAIT).await {
                    Some((e, dedup)) => {
                        let (_, assoc, fin) = sub_event(&e).expect("event");
                        events.push((text(&e), assoc, fin, dedup));
                    }
                    None => break,
                }
            }
            let got: Vec<(String, bool, bool)> =
                events.iter().map(|(t, a, f, _)| (t.clone(), *a, *f)).collect();
            let want = vec![
                ("tick 1".to_owned(), false, false),
                ("tick 2".to_owned(), true, false),
                ("tick 3".to_owned(), false, false),
                ("done".to_owned(), false, true),
            ];
            h.record(
                "tool_result/subscription:true",
                confirmed && text(&m) == "ticking",
                format!("text={:?} InputMessage.subscription={confirmed}", text(&m)),
            );
            h.record(
                "subscription_event(+final)",
                got == want,
                format!("events (text, associative, final) = {got:?}"),
            );
        }
        None => h.record("tool_result/subscription:true", false, "no tool_result within 15s"),
    }

    // 8. User choice: the runtime answers at response_url, then the tool's result arrives.
    let id = h.invoke("confirm", json!({}), &[T1], Some("prov-2")).await?;
    match h
        .collector
        .take(|m| matches!(&m.content, InputMessageContent::UserChoice(c) if c.id.as_str() == id), WAIT)
        .await
    {
        Some((m, dedup)) => {
            let InputMessageContent::UserChoice(c) = &m.content else { unreachable!() };
            let shape_ok = c.prompt == "Deploy to production?"
                && c.choices == ["yes", "no"]
                && c.default == 1
                && c.response_url.ends_with("/user_choice_response")
                && c.call_id.as_ref().map(|x| x.as_str()) == Some("prov-2");
            let evidence = format!(
                "user_choice prompt={:?} choices={:?} default={} response_url={} call_id={:?} dedup={dedup}",
                c.prompt, c.choices, c.default, c.response_url, c.call_id
            );
            // As infinity-daemon/src/client_handler.rs answers a choice.
            let status = reqwest::Client::new()
                .post(&c.response_url)
                .json(&json!({ "id": c.id, "selected": 0 }))
                .send()
                .await
                .map(|r| r.status().as_u16())
                .unwrap_or(0);
            h.record("user_choice", shape_ok, evidence);
            match h.result(&id).await {
                Some((r, _)) => {
                    let t = text(&r);
                    h.record(
                        "user_choice/response_url answer",
                        status == 200 && t == "chose yes",
                        format!("POST {{id, selected: 0}} -> HTTP {status}; tool result {t:?}"),
                    );
                }
                None => h.record(
                    "user_choice/response_url answer",
                    false,
                    format!("answer HTTP {status}; no tool_result within 15s"),
                ),
            }
        }
        None => h.record("user_choice", false, "no user_choice within 15s"),
    }

    // 9. OAuth prompt, then the result once the tool finishes.
    let id = h.invoke("login", json!({}), &[T1], None).await?;
    match h
        .collector
        .take(|m| matches!(&m.content, InputMessageContent::OAuth(o) if o.id.as_str() == id), WAIT)
        .await
    {
        Some((m, dedup)) => {
            let InputMessageContent::OAuth(o) = &m.content else { unreachable!() };
            let url_ok = o.auth_url == "https://auth.example/authorize?state=interop";
            let after = h.result(&id).await.map(|(r, _)| text(&r));
            h.record(
                "oauth",
                url_ok && after.as_deref() == Some("authorized"),
                format!("auth_url={} dedup={dedup}; then tool result {after:?}", o.auth_url),
            );
        }
        None => h.record("oauth", false, "no oauth within 15s"),
    }

    // 10. cancel_tool_call on an active subscription: heartbeats stop.
    const T2: &str = "hydro-thread-2";
    let id = h.invoke("watch", json!({}), &[T2], None).await?;
    match h.result(&id).await {
        Some((m, _)) if m.subscription => {
            let first = h.collector.take(|m| is_event(m, &id), WAIT).await.is_some();
            h.notifier
                .notify_tool_cancelled(ThreadId::from_ref(T2), ToolCallId::from_ref(&id))
                .await;
            tokio::time::sleep(Duration::from_millis(800)).await;
            let before = h.collector.count(|m| is_event(m, &id));
            tokio::time::sleep(Duration::from_millis(1500)).await;
            let after = h.collector.count(|m| is_event(m, &id));
            let finals = h
                .collector
                .count(|m| is_event(m, &id) && sub_event(m).is_some_and(|(_, _, f)| f));
            h.record(
                "cancel_tool_call(subscription)",
                first && before == after && finals == 0,
                format!(
                    "heartbeat seen before cancel={first}; events in the 1.5 s after cancel+0.8 s grace: {} (no final event sent: {})",
                    after - before,
                    finals == 0
                ),
            );
        }
        other => h.record(
            "cancel_tool_call(subscription)",
            false,
            format!("no subscription confirmation: {:?}", other.map(|(m, _)| text(&m))),
        ),
    }

    // 11. cancel_tool_call on a running call: the tool sees the cancellation.
    let id = h.invoke("slow", json!({}), &[T2], None).await?;
    tokio::time::sleep(Duration::from_millis(500)).await;
    h.notifier
                .notify_tool_cancelled(ThreadId::from_ref(T2), ToolCallId::from_ref(&id))
                .await;
    match h.result(&id).await {
        Some((m, _)) => {
            let t = text(&m);
            h.record(
                "cancel_tool_call(running)",
                t.contains("stopped"),
                format!("result after cancellation: {t:?} (tolerated per tool-cancellation.md:53)"),
            );
        }
        None => h.record(
            "cancel_tool_call(running)",
            true,
            "no result after cancellation (allowed)",
        ),
    }

    // 12. close_thread: the thread's subscription ends with a final event.
    const T3: &str = "hydro-thread-3";
    let id = h.invoke("watch", json!({}), &[T3], None).await?;
    match h.result(&id).await {
        Some((m, _)) if m.subscription => {
            let _ = h.collector.take(|m| is_event(m, &id), WAIT).await;
            h.notifier.notify_thread_closed(ThreadId::from_ref(T3)).await;
            let fin = h
                .collector
                .take_seq(
                    |m| is_event(m, &id) && sub_event(m).is_some_and(|(_, _, f)| f),
                    WAIT,
                )
                .await;
            tokio::time::sleep(Duration::from_millis(1000)).await;
            let fin_text = fin.as_ref().map(|(_, m, _)| text(m));
            let after_final = fin
                .as_ref()
                .map(|(n, _, _)| h.collector.count_after(|m| is_event(m, &id), Some(*n)))
                .unwrap_or(0);
            h.record(
                "close_thread",
                fin.is_some() && after_final == 0,
                format!(
                    "final subscription_event after close_thread: text={fin_text:?}; events after the final: {after_final}"
                ),
            );
        }
        other => h.record(
            "close_thread",
            false,
            format!("no subscription confirmation: {:?}", other.map(|(m, _)| text(&m))),
        ),
    }

    // 13. Lifecycle endpoints answer 200 for unknown ids (their HttpClient, raw).
    let cancel = h
        .http
        .post(
            &format!("{}/cancel_tool_call", h.server),
            &json!({"thread_id": "nope", "tool_call_id": "nope"}).to_string(),
        )
        .await
        .map_err(|e| e.to_string());
    let close = h
        .http
        .post(&format!("{}/close_thread", h.server), &json!({"thread_id": "nope"}).to_string())
        .await
        .map_err(|e| e.to_string());
    h.record(
        "lifecycle/unknown ids → 200",
        cancel == Ok(200) && close == Ok(200),
        format!("/cancel_tool_call -> {cancel:?}, /close_thread -> {close:?}"),
    );

    finish(h, report)
}

fn finish(h: Harness, report: Option<String>) -> Result<(), BoxError> {
    let failed = h.results.iter().filter(|(_, ok, _)| !ok).count();
    if let Some(path) = report {
        let items: Vec<Value> = h
            .results
            .iter()
            .map(|(s, ok, e)| json!({"scenario": s, "pass": ok, "evidence": e}))
            .collect();
        std::fs::write(&path, serde_json::to_string_pretty(&json!({ "results": items }))?)?;
    }
    println!(
        "SUMMARY|B|{} passed, {} failed",
        h.results.len() - failed,
        failed
    );
    if failed > 0 {
        std::process::exit(1);
    }
    Ok(())
}
