# Interactive tour

See the five protocols in action without installing anything. The **Protoweft Tour** walks
through MCP, UTCP, A2A, RAP and AG-UI chapter by chapter. Every step shows the Java code that runs
it, and next to the code, the real HTTP traffic it produced: JSON-RPC calls, Server-Sent Events,
RAP callbacks.

[Open the tour in its own tab ↗](https://github.datasierra.com/protoweft-examples/){ .md-button .md-button--primary target="_blank" }

<iframe src="https://github.datasierra.com/protoweft-examples/" title="Protoweft Tour"
        style="width: 100%; height: 760px; border: 1px solid var(--md-default-fg-color--lightest); border-radius: 8px;"
        loading="lazy"></iframe>

| Chapter | What you see |
|---|---|
| Why Protoweft | One app answering on all five endpoints |
| MCP | `initialize` and version negotiation, `tools/list`, `tools/call` |
| UTCP | A manual with HTTP call templates, and a direct HTTP tool call |
| A2A | An agent card, a task answered by an agent that uses a tool, a follow-up in the same context |
| RAP | A result delivered by callback, and a subscription that keeps streaming events |
| AG-UI | A chat in the page with the official `@ag-ui/client`, including a frontend tool the agent calls |
| One registry | All four tool protocols in one `ToolRegistry`, and every connector's compliance report |

This page plays recordings of real traffic, made by the tour app's tests. To run every step live
on your machine, with a scripted model or your own OpenAI-compatible LLM:

```bash
git clone https://github.com/rahulbsw/protoweft-examples
cd protoweft-examples
./tour/run.sh          # then open http://localhost:8080
```

The tour's source and how it works: [`tour/` in protoweft-examples](https://github.com/rahulbsw/protoweft-examples/tree/main/tour).
