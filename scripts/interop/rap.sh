#!/usr/bin/env bash
# Interoperability check: protoweft-rap against Hydro Infinity's reference RAP implementations,
# in both directions. See protoweft-rap/SPEC.md, section "Interoperability".
#
#   scripts/interop/rap.sh
#
# Direction A (Protoweft client -> Hydro tool servers): starts Hydro's JS get-time server
# (local.mjs), their Rust stub servers (rap-test-servers), sandbox-local (sandbox-core),
# rap-steering-server and rap-github-event-poller, then runs the env-gated JUnit tests
# protoweft-rap/src/test/.../Hydro*InteropTest against them.
# Direction B (Hydro Rust runtime -> Protoweft tool server): runs HydroRuntimeServeInteropTest
# (RapToolServer on Jetty) and drives it with interop/rap/rust-client, which uses Hydro's
# rap-client / rap-protocol / infinity-rap-bridge / infinity-agent-core at the pinned commit.
# Also runs Hydro's own RAP-related cargo tests as a toolchain sanity check.
#
# Needs: git, curl, node (>= 18), cargo (edition 2024: Rust >= 1.85), a JDK for Gradle; network
# on the first run (git clone, crates.io). Nothing needs AWS.
#
# Env (all optional):
#   RAP_INTEROP_INFINITY_DIR  Infinity checkout at the pinned commit (default /tmp/rap-infinity;
#                             cloned there if missing)
#   RAP_INTEROP_WORK          scratch dir: cargo targets, scratch repo, server logs (default /tmp/rap-interop)
#   RAP_INTEROP_PORT_BASE     first of six consecutive local ports (default 3101; 8080 is never used)
#   RAP_INTEROP_SANDBOX_EXEC  true|false: sandbox-local's macOS sandbox-exec / Linux bwrap (default true)
#   RAP_INTEROP_SKIP_HYDRO_TESTS=1  skip Hydro's own cargo tests
#   RAP_INTEROP_GRADLE_ARGS   extra Gradle args (default --no-daemon, so no daemon outlives the run)
# Results: build/interop/rap/ (summary.txt, per-direction logs and reports, JUnit XML).
# Exit code 0 only if every step passed. Every process this script starts is stopped on exit.
set -euo pipefail

INFINITY_REV="e1a3e4b36e5f6143e03a59a7ad86e1abacfd22e8"
INFINITY_URL="https://github.com/hydro-project/infinity"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INFINITY_DIR="${RAP_INTEROP_INFINITY_DIR:-/tmp/rap-infinity}"
WORK="${RAP_INTEROP_WORK:-/tmp/rap-interop}"
OUT="$ROOT/build/interop/rap"
PORT_BASE="${RAP_INTEROP_PORT_BASE:-3101}"
GET_TIME_PORT=$PORT_BASE
SANDBOX_PORT=$((PORT_BASE + 1))
STEERING_PORT=$((PORT_BASE + 2))
POLLER_PORT=$((PORT_BASE + 3))
SERVE_PORT=$((PORT_BASE + 10))
SANDBOX_EXEC="${RAP_INTEROP_SANDBOX_EXEC:-true}"
read -r -a GRADLE_ARGS <<<"${RAP_INTEROP_GRADLE_ARGS:---no-daemon}"

PIDS=()
STEPS=()
FAILED=0

log() { echo "[rap-interop] $*" >&2; }

step() { # step <name> <PASS|FAIL|SKIP> <detail>
  STEPS+=("$(printf '%-6s %-58s %s' "$2" "$1" "$3")")
  [[ "$2" == "FAIL" ]] && FAILED=1
  log "$2: $1 $3"
}

cleanup() {
  local code=$?
  [[ -n "${STOP_FILE:-}" ]] && touch "$STOP_FILE" 2>/dev/null || true
  for pid in "${PIDS[@]:-}"; do
    [[ -z "$pid" ]] && continue
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
      kill -9 "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
  done
  exit "$code"
}
trap cleanup EXIT INT TERM

require_free_port() {
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$1/"; then
    log "port $1 is already in use; set RAP_INTEROP_PORT_BASE to a free range"
    exit 2
  fi
}

# start <name> <dir> <command...>: background process in <dir>, output to $OUT/logs/<name>.log
start() {
  local name="$1" dir="$2"
  shift 2
  (cd "$dir" && exec "$@") >"$OUT/logs/$name.log" 2>&1 &
  PIDS+=("$!")
  LAST_PID=$!
  log "started $name (pid $!), log: $OUT/logs/$name.log"
}

wait_for_url() { # wait_for_url <url> <pid> <timeout-s>
  local start
  start=$(date +%s)
  until curl -s -o /dev/null --max-time 1 "$1"; do
    kill -0 "$2" 2>/dev/null || { log "process $2 exited before $1 answered"; return 1; }
    (($(date +%s) - start >= $3)) && { log "timed out waiting for $1"; return 1; }
    sleep 0.3
  done
}

gradle() { (cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" "$@"); }

rm -rf "$OUT"
mkdir -p "$OUT/logs" "$OUT/reports" "$WORK"

for tool in git curl node cargo; do
  command -v "$tool" >/dev/null || { log "$tool is required"; exit 2; }
done

# ── Hydro Infinity at the pinned commit ──
if [[ ! -d "$INFINITY_DIR/.git" ]]; then
  log "cloning $INFINITY_URL into $INFINITY_DIR"
  git clone --quiet "$INFINITY_URL" "$INFINITY_DIR"
  git -C "$INFINITY_DIR" checkout --quiet "$INFINITY_REV"
fi
HEAD_REV="$(git -C "$INFINITY_DIR" rev-parse HEAD)"
if [[ "$HEAD_REV" != "$INFINITY_REV" ]]; then
  log "$INFINITY_DIR is at $HEAD_REV, expected $INFINITY_REV (check it out or set RAP_INTEROP_INFINITY_DIR)"
  exit 2
fi
log "Infinity $INFINITY_REV at $INFINITY_DIR; $(node --version), $(cargo --version)"

# ── Builds ──
log "building Hydro servers (sandbox-local, rap-steering-server, rap-github-event-poller)"
(cd "$INFINITY_DIR" && CARGO_TARGET_DIR="$WORK/target-infinity" cargo build --locked --quiet \
  -p sandbox-local -p rap-steering-server -p rap-github-event-poller) >"$OUT/logs/cargo-build-infinity.log" 2>&1 \
  || { log "cargo build failed, see $OUT/logs/cargo-build-infinity.log"; exit 1; }
HYDRO_BIN="$WORK/target-infinity/debug"

log "building interop/rap/rust-client (Hydro crates pinned to $INFINITY_REV)"
(cd "$ROOT/interop/rap/rust-client" && CARGO_TARGET_DIR="$WORK/target-harness" cargo build --locked --quiet) \
  >"$OUT/logs/cargo-build-harness.log" 2>&1 \
  || { log "harness build failed, see $OUT/logs/cargo-build-harness.log"; exit 1; }
HARNESS_BIN="$WORK/target-harness/debug"

log "compiling protoweft-rap tests"
gradle --quiet :protoweft-rap:testClasses >"$OUT/logs/gradle-build.log" 2>&1 \
  || { log "gradle build failed, see $OUT/logs/gradle-build.log"; exit 1; }

# ── Hydro's own RAP tests (toolchain/version sanity) ──
# rap-protocol and rap-client have no unit tests at this commit; infinity-rap-bridge's tests cover
# callback conversion and infinity-agent-core's rap_tool tests cover invocation.
if [[ "${RAP_INTEROP_SKIP_HYDRO_TESTS:-}" == "1" ]]; then
  step "hydro: cargo test (rap-protocol, rap-client, rap-bridge, rap_tool)" SKIP "RAP_INTEROP_SKIP_HYDRO_TESTS=1"
elif (cd "$INFINITY_DIR" && export CARGO_TARGET_DIR="$WORK/target-infinity" \
    && cargo test --locked -p rap-protocol -p rap-client -p infinity-rap-bridge \
    && cargo test --locked -p infinity-agent-core --lib rap_tool) >"$OUT/logs/hydro-cargo-test.log" 2>&1; then
  step "hydro: cargo test (rap-protocol, rap-client, rap-bridge, rap_tool)" PASS \
    "$(grep -E '^test result:' "$OUT/logs/hydro-cargo-test.log" | awk '{p+=$4; f+=$6} END {print p " passed, " f " failed"}')"
else
  step "hydro: cargo test (rap-protocol, rap-client, rap-bridge, rap_tool)" FAIL "see logs/hydro-cargo-test.log"
fi

# ── Scratch repository for sandbox-local and the steering server ──
REPO="$WORK/repo"
rm -rf "$REPO" "$WORK/extra-write" "$WORK/sandbox-meta" "$WORK/sandbox-cwd" "$WORK/steering-cwd" "$WORK/poller-cwd"
mkdir -p "$REPO" "$WORK/sandbox-cwd" "$WORK/steering-cwd" "$WORK/poller-cwd"
printf 'hello interop\nline two\n' >"$REPO/README.md"
printf '# Protoweft interop steering file\nBe nice to RAP.\n' >"$REPO/AGENTS.md"
# rap-test-servers' STUB_PNG_BASE64 as a real file, for read_file's image content.
echo 'iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAIAAABqVuVZAAAAaUlEQVR42u3QQQkAAAgEsOsq2N8GNvDtY7AES/VwiAJBggQJEiRIkCAECRIkSJAgQYIQJEiQIEGCBAlCkCBBggQJEiRIEIIECRIkSJAgQQgSJEiQIEGCBCFIkCBBggQJEiQIQYIECfpnAbKIcmjVgp/0AAAAAElFTkSuQmCC' \
  | base64 --decode >"$REPO/pixel.png"
(cd "$REPO" && git init --quiet -b main && git add . \
  && git -c user.name=interop -c user.email=interop@example.invalid commit --quiet -m "interop fixture")

# ── Hydro tool servers ──
for p in "$GET_TIME_PORT" "$SANDBOX_PORT" "$STEERING_PORT" "$POLLER_PORT" "$SERVE_PORT"; do require_free_port "$p"; done

start get-time "$INFINITY_DIR/agent/lib/toolsets/get-time/get-time-tool" node local.mjs --port "$GET_TIME_PORT"
wait_for_url "http://127.0.0.1:$GET_TIME_PORT/.well-known/rap-toolset" "$LAST_PID" 20

start rap-stub-servers "$WORK" "$HARNESS_BIN/rap-stub-servers"
STUB_PID=$LAST_PID
for _ in $(seq 1 50); do grep -q image_port "$OUT/logs/rap-stub-servers.log" 2>/dev/null && break; sleep 0.2; done
IMAGE_PORT=$(sed -n 's/.*"image_port":\([0-9]*\).*/\1/p' "$OUT/logs/rap-stub-servers.log")
DIFF_PORT=$(sed -n 's/.*"diff_port":\([0-9]*\).*/\1/p' "$OUT/logs/rap-stub-servers.log")
[[ -n "$IMAGE_PORT" && -n "$DIFF_PORT" ]] || { log "rap-stub-servers did not report its ports"; exit 1; }
wait_for_url "http://127.0.0.1:$IMAGE_PORT/.well-known/rap-toolset" "$STUB_PID" 10

start sandbox-local "$WORK/sandbox-cwd" env RUST_LOG=info "$HYDRO_BIN/sandbox-local" --port "$SANDBOX_PORT" \
  --enable-sandbox "$SANDBOX_EXEC" --metadata-dir "$WORK/sandbox-meta"
wait_for_url "http://127.0.0.1:$SANDBOX_PORT/.well-known/rap-toolset" "$LAST_PID" 20

start rap-steering-server "$WORK/steering-cwd" "$HYDRO_BIN/rap-steering-server" --port "$STEERING_PORT"
wait_for_url "http://127.0.0.1:$STEERING_PORT/.well-known/rap-toolset" "$LAST_PID" 20

start rap-github-event-poller "$WORK/poller-cwd" "$HYDRO_BIN/rap-github-event-poller" --port "$POLLER_PORT"
wait_for_url "http://127.0.0.1:$POLLER_PORT/.well-known/rap-toolset" "$LAST_PID" 20

# ── Direction A: Protoweft client -> Hydro tool servers ──
log "direction A: protoweft-rap client against Hydro's servers"
set +e
RAP_INTEROP_REPORT_DIR="$OUT/reports" \
RAP_INTEROP_GET_TIME_URL="http://127.0.0.1:$GET_TIME_PORT" \
RAP_INTEROP_STUB_IMAGE_URL="http://127.0.0.1:$IMAGE_PORT" \
RAP_INTEROP_STUB_DIFF_URL="http://127.0.0.1:$DIFF_PORT" \
RAP_INTEROP_SANDBOX_URL="http://127.0.0.1:$SANDBOX_PORT" \
RAP_INTEROP_SANDBOX_REPO="$REPO" \
RAP_INTEROP_SANDBOX_LOG="$WORK/sandbox-cwd/rap-sandbox.log" \
RAP_INTEROP_STEERING_URL="http://127.0.0.1:$STEERING_PORT" \
RAP_INTEROP_STEERING_ROOT="$REPO" \
RAP_INTEROP_GITHUB_POLLER_URL="http://127.0.0.1:$POLLER_PORT" \
  gradle :protoweft-rap:test --tests '*.Hydro*InteropTest' --rerun >"$OUT/logs/direction-a-gradle.log" 2>&1
A_CODE=$?
set -e
mkdir -p "$OUT/junit-a"
cp "$ROOT"/protoweft-rap/build/test-results/test/TEST-*Hydro*.xml "$OUT/junit-a/" 2>/dev/null || true
A_TESTS=$(grep -hoE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$OUT"/junit-a/*.xml 2>/dev/null \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6+$8} END {print t-s-f " passed, " f " failed, " s " skipped"}')
if [[ $A_CODE -eq 0 ]]; then
  step "A: protoweft client -> Hydro servers (JUnit)" PASS "$A_TESTS"
else
  step "A: protoweft client -> Hydro servers (JUnit)" FAIL "${A_TESTS:-no results}; see logs/direction-a-gradle.log"
fi

# ── Direction B: Hydro Rust runtime -> Protoweft tool server ──
log "direction B: Hydro's Rust runtime code against protoweft-rap's RapToolServer"
READY_FILE="$WORK/serve.ready"
STOP_FILE="$WORK/serve.stop"
rm -f "$READY_FILE" "$STOP_FILE"
(cd "$ROOT" && exec env RAP_INTEROP_REPORT_DIR="$OUT/reports" RAP_INTEROP_SERVE_PORT="$SERVE_PORT" \
  RAP_INTEROP_READY_FILE="$READY_FILE" RAP_INTEROP_STOP_FILE="$STOP_FILE" RAP_INTEROP_SERVE_SECONDS=300 \
  ./gradlew "${GRADLE_ARGS[@]}" :protoweft-rap:test --tests '*.HydroRuntimeServeInteropTest' --rerun) \
  >"$OUT/logs/direction-b-gradle.log" 2>&1 &
SERVE_PID=$!
PIDS+=("$SERVE_PID")
start_wait=$(date +%s)
while [[ ! -s "$READY_FILE" ]]; do
  kill -0 "$SERVE_PID" 2>/dev/null || break
  (($(date +%s) - start_wait >= 300)) && break
  sleep 0.5
done
if [[ -s "$READY_FILE" ]]; then
  SERVER_URL="$(cat "$READY_FILE")"
  log "protoweft RapToolServer at $SERVER_URL"
  set +e
  "$HARNESS_BIN/rap-interop-client" --server "$SERVER_URL" --report "$OUT/reports/direction-b-harness.json" \
    >"$OUT/logs/direction-b-harness.log" 2>"$OUT/logs/direction-b-harness.stderr.log"
  B_CODE=$?
  set -e
  grep '^RESULT|' "$OUT/logs/direction-b-harness.log" >>"$OUT/reports/B.txt" || true
  touch "$STOP_FILE"
  set +e
  wait "$SERVE_PID"
  B_SERVER_CODE=$?
  set -e
  B_SUMMARY=$(sed -n 's/^SUMMARY|B|//p' "$OUT/logs/direction-b-harness.log")
  if [[ $B_CODE -eq 0 ]]; then
    step "B: Hydro Rust runtime -> protoweft server (harness)" PASS "$B_SUMMARY"
  else
    step "B: Hydro Rust runtime -> protoweft server (harness)" FAIL "${B_SUMMARY:-exit $B_CODE}; see logs/direction-b-harness*.log"
  fi
  if [[ $B_SERVER_CODE -eq 0 ]]; then
    step "B: server-side assertions (JUnit)" PASS "HydroRuntimeServeInteropTest"
  else
    step "B: server-side assertions (JUnit)" FAIL "see logs/direction-b-gradle.log"
  fi
else
  step "B: Hydro Rust runtime -> protoweft server" FAIL "RapToolServer never became ready; see logs/direction-b-gradle.log"
fi
mkdir -p "$OUT/junit-b"
cp "$ROOT"/protoweft-rap/build/test-results/test/TEST-*HydroRuntimeServe*.xml "$OUT/junit-b/" 2>/dev/null || true

# ── Summary ──
{
  echo "RAP interop: protoweft-rap ($(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo '?')) vs Hydro Infinity ${INFINITY_REV:0:7}"
  echo "node $(node --version), $(cargo --version)"
  echo
  echo "Steps:"
  printf '  %s\n' "${STEPS[@]}"
  echo
  echo "Checks (direction | server | check | result | evidence):"
  cat "$OUT"/reports/A.txt "$OUT"/reports/B.txt 2>/dev/null | awk -F'|' '{
    ev = $6; for (i = 7; i <= NF; i++) ev = ev "|" $i
    if (length(ev) > 260) ev = substr(ev, 1, 257) "..."
    printf "  %s | %-23s | %-34s | %s | %s\n", $2, $3, $4, $5, ev }'
  if grep -h ') FAILED$' "$OUT"/logs/direction-*-gradle.log >/dev/null 2>&1; then
    echo
    echo "Failed JUnit tests:"
    grep -h ') FAILED$' "$OUT"/logs/direction-*-gradle.log | sed 's/^/  /'
  fi
} | tee "$OUT/summary.txt"

if [[ $FAILED -ne 0 ]]; then
  log "FAILED (details in $OUT)"
  exit 1
fi
log "all passed (details in $OUT)"
