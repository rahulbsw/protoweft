// AG-UI from the browser with the official client: streamed text, the agent's own tool calls,
// a frontend tool the page runs itself (set_theme), and shared state.
import { HttpAgent } from "@ag-ui/client";

const agent = new HttpAgent({ url: "/agui" });   // same origin; a page elsewhere needs CORS on the server

// The page's own tool: the agent calls it, the page runs it and answers on the next run.
const setTheme = {
  name: "set_theme",
  description: "Change the page's accent colour",
  parameters: {
    type: "object",
    properties: { color: { type: "string", description: "A CSS colour, for example 'teal' or '#336699'" } },
    required: ["color"],
  },
};

const log = document.getElementById("log");
const form = document.getElementById("ask");
const input = document.getElementById("message");
const stateView = document.getElementById("state");

function bubble(kind, text) {
  const div = document.createElement("div");
  div.className = `bubble ${kind}`;
  div.textContent = text;
  log.append(div);
  log.scrollTop = log.scrollHeight;
  return div;
}

// Shows streamed text and tool calls as they arrive.
function subscriber() {
  const texts = new Map();
  const calls = new Map();
  return {
    onTextMessageStartEvent: ({ event }) => { texts.set(event.messageId, bubble("assistant", "")); },
    onTextMessageContentEvent: ({ event }) => { texts.get(event.messageId).textContent += event.delta; },
    onToolCallStartEvent: ({ event }) => {
      calls.set(event.toolCallId, bubble("tool", `⚙ ${event.toolCallName}(…)`));
    },
    onToolCallResultEvent: ({ event }) => {
      const card = calls.get(event.toolCallId);
      if (card) card.textContent += ` → ${typeof event.content === "string" ? event.content : "[parts]"}`;
    },
    onStateChanged: ({ state }) => { stateView.textContent = JSON.stringify(state, null, 2); },
    onRunErrorEvent: ({ event }) => { bubble("error", `The agent failed: ${event.message}`); },
    onRunFailed: ({ error }) => { bubble("error", `The run failed: ${error.message}`); },
  };
}

// Runs the agent, answers its calls to set_theme, and runs again until nothing is pending.
async function runUntilDone() {
  for (let round = 0; round < 5; round++) {
    await agent.runAgent({ tools: [setTheme] }, subscriber());
    const answered = new Set(agent.messages.filter((m) => m.role === "tool").map((m) => m.toolCallId));
    const pending = agent.messages.flatMap((m) => m.toolCalls ?? [])
      .filter((c) => c.function.name === setTheme.name && !answered.has(c.id));
    if (pending.length === 0) return;
    for (const call of pending) {
      let result;
      try {
        const { color } = JSON.parse(call.function.arguments);
        document.documentElement.style.setProperty("--accent", color);
        result = `The page is now ${color}.`;
      } catch (e) {
        result = `Could not change the colour: ${e.message}`;
      }
      bubble("tool", `🎨 set_theme → ${result}`);
      agent.addMessage({ id: crypto.randomUUID(), role: "tool", toolCallId: call.id, content: result });
    }
  }
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  bubble("user", text);
  agent.addMessage({ id: crypto.randomUUID(), role: "user", content: text });
  form.querySelector("button").disabled = true;
  try {
    await runUntilDone();
  } catch (err) {
    bubble("error", err.message);
  } finally {
    form.querySelector("button").disabled = false;
    input.focus();
  }
});

document.getElementById("loading").remove();
