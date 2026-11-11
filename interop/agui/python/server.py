"""An AG-UI agent built on the official Python SDK (ag-ui-protocol), for interop with Protoweft's
client and conformance checker. Run by scripts/interop/agui.sh.

    python server.py [port]        # POST http://localhost:<port>/agent (default 9997)

Behaviour, by the run input:
  * resume entries        -> "resumed: <payload>"
  * last message is tool  -> "confirmed: <content>"
  * user "interrupt"      -> RUN_FINISHED with an interrupt outcome (id "approve-1")
  * any other user text   -> a reasoning span, a step, state snapshot + delta, the reply streamed
                             with TEXT_MESSAGE_CHUNK, and a call to the "confirm" tool when offered
"""
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ag_ui.core import (
    Interrupt, ReasoningEndEvent, ReasoningMessageContentEvent, ReasoningMessageEndEvent,
    ReasoningMessageStartEvent, ReasoningStartEvent, RunAgentInput, RunFinishedEvent,
    RunFinishedInterruptOutcome, RunStartedEvent, StateDeltaEvent, StateSnapshotEvent, StepFinishedEvent,
    StepStartedEvent, TextMessageChunkEvent, ToolCallArgsEvent, ToolCallEndEvent, ToolCallStartEvent,
)
from ag_ui.encoder import EventEncoder
from pydantic import ValidationError

PROTOCOL_VERSION = "1.0"


def text_of(content):
    if isinstance(content, str):
        return content
    return "".join(getattr(part, "text", "") for part in content or [])


def events_for(run: RunAgentInput):
    yield RunStartedEvent(thread_id=run.thread_id, run_id=run.run_id, protocol_version=PROTOCOL_VERSION)
    last = run.messages[-1] if run.messages else None
    if run.resume:
        yield from reply(f"resumed: {run.resume[0].payload}")
    elif last is not None and last.role == "tool":
        yield from reply(f"confirmed: {text_of(last.content)}")
    elif last is not None and last.role == "user" and text_of(last.content) == "interrupt":
        yield RunFinishedEvent(thread_id=run.thread_id, run_id=run.run_id, outcome=RunFinishedInterruptOutcome(
            interrupts=[Interrupt(id="approve-1", reason="approval", message="Proceed?")]))
        return
    else:
        said = text_of(last.content) if last is not None else ""
        yield StepStartedEvent(step_name="answer")
        span, thought = str(uuid.uuid4()), str(uuid.uuid4())
        yield ReasoningStartEvent(message_id=span)
        yield ReasoningMessageStartEvent(message_id=thought, role="reasoning")
        yield ReasoningMessageContentEvent(message_id=thought, delta="Echo it back.")
        yield ReasoningMessageEndEvent(message_id=thought)
        yield ReasoningEndEvent(message_id=span)
        yield StateSnapshotEvent(snapshot={"turns": 1})
        yield StateDeltaEvent(delta=[{"op": "replace", "path": "/turns", "value": 2}])
        yield from reply(f"you said: {said}")
        yield StepFinishedEvent(step_name="answer")
        if any(tool.name == "confirm" for tool in run.tools or []):
            call = str(uuid.uuid4())
            yield ToolCallStartEvent(tool_call_id=call, tool_call_name="confirm")
            yield ToolCallArgsEvent(tool_call_id=call, delta='{"question":"Shall I go ahead?"}')
            yield ToolCallEndEvent(tool_call_id=call)
    yield RunFinishedEvent(thread_id=run.thread_id, run_id=run.run_id)


def reply(text):
    message = str(uuid.uuid4())
    words = text.split(" ")
    for i, word in enumerate(words):
        piece = word if i == len(words) - 1 else word + " "
        # The chunked spelling: the first chunk names the message, later ones continue it.
        yield TextMessageChunkEvent(message_id=message if i == 0 else None, delta=piece)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        try:
            run = RunAgentInput.model_validate_json(body)
        except ValidationError as e:
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":"invalid run input"}')
            return
        encoder = EventEncoder(accept=self.headers.get("Accept"))
        self.send_response(200)
        self.send_header("Content-Type", encoder.get_content_type())
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for event in events_for(run):
            self.wfile.write(encoder.encode(event).encode("utf-8"))
            self.wfile.flush()

    def log_message(self, format, *args):
        sys.stderr.write("[python agent] " + format % args + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9997
    print(f"AG-UI Python agent listening on http://localhost:{port}/agent", flush=True)
    ThreadingHTTPServer(("localhost", port), Handler).serve_forever()
