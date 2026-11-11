#!/usr/bin/env bash
# Interoperability check: protoweft-agui against the official AG-UI SDKs, in both directions.
# See protoweft-agui/SPEC.md, section "Interoperability".
#
#   scripts/interop/agui.sh
#
# Direction A (official TypeScript client -> Protoweft server): starts conformance/agui-sut and
# runs interop/agui/ts/client.mjs, which drives it with @ag-ui/client (whose enforcement stage
# validates every stream): every event family, a frontend tool round trip, interrupt and resume,
# and the same run over the HTTP + Protobuf binding.
# Direction B (Protoweft client -> official Python SDK): starts interop/agui/python/server.py (an
# agent on ag-ui-protocol, streaming chunk events) and runs the env-gated JUnit test
# OfficialPythonAgentInteropTest against it: AgUiThread, AgUiClientConnector, the AgUiConformance
# checker, and a client asking for Protobuf getting SSE (the Python SDK has no binary binding).
# Direction B, binary (Protoweft client -> official TypeScript encoder): starts
# interop/agui/ts/encoder-agent.mjs (wire output by @ag-ui/encoder and @ag-ui/proto) and runs
# OfficialTypeScriptEncoderInteropTest: the client and checker reading the official frames.
#
# Needs a JDK, node (>= 20) with npm, and uv; network for npm and PyPI on the first run. Versions
# are pinned with integrity hashes (interop/agui/ts/package-lock.json, interop/agui/python/requirements.txt).
#
# Env (all optional):
#   AGUI_INTEROP_PORT_BASE   first of three consecutive local ports (default 9996)
#   AGUI_INTEROP_PYTHON      Python version for uv (default 3.12)
#   AGUI_INTEROP_GRADLE_ARGS extra Gradle args (default --no-daemon)
# Results: build/interop/agui/ (summary.txt and logs). Exit code 0 only if every step passed.
# Every process this script starts is stopped on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/interop/agui"
PORT_BASE="${AGUI_INTEROP_PORT_BASE:-9996}"
SUT_PORT=$PORT_BASE
PY_PORT=$((PORT_BASE + 1))
TS_PORT=$((PORT_BASE + 2))
GRADLE_ARGS=(${AGUI_INTEROP_GRADLE_ARGS:---no-daemon})
PIDS=()
rm -rf "$OUT"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"

log() { echo "[interop:agui] $*" >&2; }

cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

wait_for() {
  local url="$1" pid="$2"
  for _ in $(seq 1 120); do
    kill -0 "$pid" 2>/dev/null || { log "process for ${url} exited early"; return 1; }
    curl -s -o /dev/null --max-time 2 -X OPTIONS "$url" && return 0
    sleep 0.5
  done
  log "timed out waiting for ${url}"
  return 1
}

for port in "$SUT_PORT" "$PY_PORT" "$TS_PORT"; do
  if curl -s -o /dev/null --max-time 2 "http://localhost:${port}/"; then
    log "port ${port} is in use; set AGUI_INTEROP_PORT_BASE"
    exit 2
  fi
done

status=0
step() {
  local name="$1"
  shift
  if "$@" >"$OUT/$name.log" 2>&1; then
    echo "PASS $name" | tee -a "$SUMMARY"
  else
    echo "FAIL $name (see $OUT/$name.log)" | tee -a "$SUMMARY"
    tail -40 "$OUT/$name.log" >&2
    status=1
  fi
}

log "building conformance/agui-sut"
(cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" --quiet :conformance:agui-sut:installDist)
AGUI_SUT_PORT=$SUT_PORT "$ROOT/conformance/agui-sut/build/install/agui-sut/bin/agui-sut" >"$OUT/sut.log" 2>&1 &
PIDS+=($!)

log "installing the official Python SDK (pinned by hash)"
uv venv --quiet --python "${AGUI_INTEROP_PYTHON:-3.12}" "$OUT/venv"
VIRTUAL_ENV="$OUT/venv" uv pip install --quiet --require-hashes -r "$ROOT/interop/agui/python/requirements.txt"
"$OUT/venv/bin/python" "$ROOT/interop/agui/python/server.py" "$PY_PORT" >"$OUT/python-agent.log" 2>&1 &
PIDS+=($!)

log "installing the official TypeScript client (pinned by package-lock.json)"
(cd "$ROOT/interop/agui/ts" && npm ci --ignore-scripts --no-audit --no-fund --silent)
node "$ROOT/interop/agui/ts/encoder-agent.mjs" "$TS_PORT" >"$OUT/ts-encoder-agent.log" 2>&1 &
PIDS+=($!)

wait_for "http://localhost:${SUT_PORT}/agui" "${PIDS[0]}"
wait_for "http://localhost:${PY_PORT}/agent" "${PIDS[1]}"
wait_for "http://localhost:${TS_PORT}/agent" "${PIDS[2]}"

step direction-a-typescript-client env AGUI_URL="http://localhost:${SUT_PORT}/agui" \
  node "$ROOT/interop/agui/ts/client.mjs"
step direction-b-protoweft-client env AGUI_INTEROP_PYTHON_URL="http://localhost:${PY_PORT}/agent" \
  bash -c "cd '$ROOT' && ./gradlew ${GRADLE_ARGS[*]} :protoweft-agui:test --tests '*OfficialPythonAgentInteropTest' --rerun"
step direction-b-protoweft-client-protobuf env AGUI_INTEROP_TS_ENCODER_URL="http://127.0.0.1:${TS_PORT}/agent" \
  bash -c "cd '$ROOT' && ./gradlew ${GRADLE_ARGS[*]} :protoweft-agui:test --tests '*OfficialTypeScriptEncoderInteropTest' --rerun"

log "summary:"
cat "$SUMMARY" >&2
exit $status
