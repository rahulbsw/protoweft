// An AG-UI agent whose wire output is produced by the official TypeScript encoder (@ag-ui/encoder,
// which encodes the HTTP + Protobuf binding with @ag-ui/proto). It negotiates the binding from
// the request's Accept header exactly as the encoder does, so a client asking for Protobuf gets
// frames and any other client gets SSE. Run by scripts/interop/agui.sh; Protoweft's client and
// conformance checker read it (OfficialTypeScriptEncoderInteropTest).
//
//   node encoder-agent.mjs <port>     serves POST /agent
import { EventEncoder } from "@ag-ui/encoder";
import { createServer } from "node:http";

const port = Number(process.argv[2] ?? 9998);

function reject(res, status, message) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ error: message }));
}

const server = createServer(async (req, res) => {
  if (req.method !== "POST" || req.url !== "/agent") {
    return reject(res, req.method === "OPTIONS" ? 204 : 404, "POST /agent");
  }
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  let input;
  try {
    input = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    return reject(res, 400, "the run input is not JSON");
  }
  if (typeof input?.threadId !== "string" || typeof input?.runId !== "string" || !Array.isArray(input?.messages)) {
    return reject(res, 400, "threadId, runId and messages are required");
  }
  const encoder = new EventEncoder({ accept: req.headers.accept });
  res.writeHead(200, { "Content-Type": encoder.getContentType(), "Cache-Control": "no-cache" });
  const send = (event) => res.write(encoder.encodeBinary(event));

  const text = [...input.messages].reverse().find((m) => m.role === "user")?.content ?? "";
  const { threadId, runId } = input;
  send({ type: "RUN_STARTED", threadId, runId, protocolVersion: "1.0" });
  send({ type: "TEXT_MESSAGE_START", messageId: "m1", role: "assistant" });
  send({ type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: "you said: " });
  send({ type: "TEXT_MESSAGE_CONTENT", messageId: "m1", delta: String(text) });
  send({ type: "TEXT_MESSAGE_END", messageId: "m1" });
  send({ type: "TOOL_CALL_START", toolCallId: "c1", toolCallName: "lookup", parentMessageId: "m1" });
  send({ type: "TOOL_CALL_ARGS", toolCallId: "c1", delta: "{\"q\":\"oslo\"}" });
  send({ type: "TOOL_CALL_END", toolCallId: "c1" });
  send({ type: "TOOL_CALL_RESULT", messageId: "m2", toolCallId: "c1", content: "21.5" });
  send({ type: "STATE_SNAPSHOT", snapshot: { city: "Oslo", temps: [1, 2.5], ok: true, none: null } });
  send({ type: "STATE_DELTA", delta: [{ op: "add", path: "/temp", value: 21.5 }] });
  send({ type: "RUN_FINISHED", threadId, runId });
  res.end();
});

server.listen(port, "127.0.0.1", () => console.log(`encoder agent on http://127.0.0.1:${port}/agent`));
