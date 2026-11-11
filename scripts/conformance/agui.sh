#!/usr/bin/env bash
# Runs Protoweft's AG-UI 1.0 conformance checker (AgUiConformance in protoweft-agui) against
# conformance/agui-sut, or against another agent with AGUI_TARGET_URL.
#
#   scripts/conformance/agui.sh
#
# Env: AGUI_SUT_PORT (default 9998), AGUI_TARGET_URL (check this agent instead of starting the
# SUT), AGUI_BASELINE (expected failures; default conformance/agui-baseline.yml, "none" to exit
# with the checker's own code). Results: build/conformance/agui/. Needs Java 17+ and Python 3.
set -euo pipefail
source "$(dirname "$0")/common.sh"

PORT="${AGUI_SUT_PORT:-9998}"
OUT="$RESULTS/agui"
rm -rf "$OUT"
mkdir -p "$OUT"

log "building conformance/agui-sut"
gradle_install :conformance:agui-sut:installDist
SUT_HOME="$ROOT/conformance/agui-sut/build/install/agui-sut"

URL="${AGUI_TARGET_URL:-}"
if [[ -z "$URL" ]]; then
  require_free_port "$PORT"
  trap stop_app EXIT
  AGUI_SUT_PORT="$PORT" start_app "$OUT/sut.log" "$SUT_HOME/bin/agui-sut"
  URL="http://localhost:${PORT}/agui"
  wait_for_url "http://localhost:${PORT}/agui/capabilities" 60 200
fi

set +e
java -cp "$SUT_HOME/lib/*" io.github.rahulbsw.protoweft.agui.AgUiConformance "$URL" --json >"$OUT/report.json"
code=$?
set -e
java -cp "$SUT_HOME/lib/*" io.github.rahulbsw.protoweft.agui.AgUiConformance "$URL" >"$OUT/report.txt" || true
cat "$OUT/report.txt" >&2
log "checker exit code: ${code}"

BASELINE="${AGUI_BASELINE:-$ROOT/conformance/agui-baseline.yml}"
if [[ "$BASELINE" == "none" ]]; then
  exit "$code"
fi
python3 - "$OUT/report.json" "$BASELINE" <<'PY'
import json, sys
report, baseline_file = sys.argv[1], sys.argv[2]
with open(report) as f:
    results = json.load(f)["results"]
failed = {r["id"] for r in results if r["status"] == "FAIL"}
expected = set()
with open(baseline_file) as f:
    for line in f:
        line = line.strip()
        if line.startswith("- "):
            expected.add(line[2:].split("#")[0].strip())
unexpected, stale = sorted(failed - expected), sorted(expected - failed)
for name in sorted(failed & expected):
    print(f"  ~ expected failure: {name}")
for name in unexpected:
    print(f"  x unexpected failure: {name}")
for name in stale:
    print(f"  ! stale baseline entry (now passes): {name}")
ok = bool(results) and not unexpected and not stale
print(f"[conformance] baseline check {'passed' if ok else 'FAILED'}: {len(results)} checks, "
      f"{len(failed)} failed ({len(failed & expected)} expected)")
sys.exit(0 if ok else 1)
PY
