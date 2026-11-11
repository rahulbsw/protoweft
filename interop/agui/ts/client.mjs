// The official TypeScript AG-UI client (@ag-ui/client, which validates every stream with its own
// enforcement stage) against Protoweft's AG-UI server (conformance/agui-sut). Exits non-zero on
// the first failed expectation. Run by scripts/interop/agui.sh with AGUI_URL set.
import { HttpAgent } from "@ag-ui/client";
import assert from "node:assert/strict";

const url = process.env.AGUI_URL;
if (!url) {
  console.error("AGUI_URL is not set");
  process.exit(2);
}

const confirm = {
  name: "confirm",
  description: "Ask the user to confirm",
  parameters: { type: "object", properties: { question: { type: "string" } } },
};

function recorder() {
  const seen = { types: [], outcome: undefined, failure: undefined };
  return {
    seen,
    subscriber: {
      onEvent: ({ event }) => { seen.types.push(event.type); },
      onRunFinishedEvent: ({ event }) => { seen.outcome = event.outcome; },
      onRunFailed: ({ error }) => { seen.failure = error; },
    },
  };
}

const lastAssistantText = (agent) =>
  [...agent.messages].reverse().find((m) => m.role === "assistant" && m.content)?.content;

async function run(agent, parameters) {
  const { seen, subscriber } = recorder();
  await agent.runAgent(parameters, subscriber);
  assert.equal(seen.failure, undefined, `run failed: ${seen.failure}`);
  return seen;
}

// 1. Every event family, then a frontend tool round trip.
{
  const agent = new HttpAgent({ url, threadId: `ts-${Date.now()}` });
  agent.addMessage({ id: "u1", role: "user", content: "hello" });
  const seen = await run(agent, { tools: [confirm] });
  for (const type of ["RUN_STARTED", "STEP_STARTED", "REASONING_MESSAGE_CONTENT", "TOOL_CALL_RESULT",
    "STATE_DELTA", "ACTIVITY_DELTA", "SUBAGENT_STARTED", "TEXT_MESSAGE_CONTENT", "CUSTOM", "RUN_FINISHED"]) {
    assert.ok(seen.types.includes(type), `expected a ${type} event, got ${seen.types.join(",")}`);
  }
  assert.ok(agent.messages.some((m) => m.role === "assistant" && m.content === "you said: hello"), "streamed reply");
  assert.deepEqual(agent.state, { turns: 2 }, "state snapshot + delta applied");
  const call = agent.messages.flatMap((m) => m.toolCalls ?? []).find((c) => c.function.name === "confirm");
  assert.ok(call, "the agent called the frontend tool");
  agent.addMessage({ id: "t1", role: "tool", toolCallId: call.id, content: "yes" });
  await run(agent, { tools: [confirm] });
  assert.equal(lastAssistantText(agent), "confirmed: yes", "the tool result reached the agent");
  console.log("ok - every event family and a frontend tool round trip");
}

// 2. Interrupt and resume.
{
  const agent = new HttpAgent({ url, threadId: `ts-interrupt-${Date.now()}` });
  agent.addMessage({ id: "u1", role: "user", content: "interrupt" });
  const seen = await run(agent, {});
  assert.equal(seen.outcome?.type, "interrupt", `outcome ${JSON.stringify(seen.outcome)}`);
  assert.equal(seen.outcome.interrupts[0].id, "approve-1");
  await run(agent, { resume: [{ interruptId: "approve-1", status: "resolved", payload: "go" }] });
  assert.equal(lastAssistantText(agent), "resumed: go", "the resume entry reached the agent");
  console.log("ok - interrupt and resume");
}
