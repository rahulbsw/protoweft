#!/usr/bin/env bash
# Runs the official A2A TCK (a2aproject/a2a-tck, pinned tag) at the MUST level against
# conformance/a2a-sut.
#
#   scripts/conformance/a2a-tck.sh [extra run_tck.py args...]
#
# Needs git, Python 3.11+ and uv. Env: A2A_SUT_PORT (default 9999), A2A_TCK_VERSION (default
# 1.0.0.alpha3), A2A_TCK_LEVEL (default must), A2A_TCK_DIR (TCK checkout; default
# build/conformance/a2a-tck-src), A2A_TCK_PYTHON (default 3.12), TCK_STREAMING_TIMEOUT (seconds;
# read by both the TCK and the SUT).
# A2A_TCK_BASELINE: expected failures (default conformance/a2a-tck-baseline.yml; "none" to exit
# with the TCK's raw code). Results: build/conformance/a2a-tck/. Exits 0 when only baselined
# failures remain (the TCK's own exit code is logged).
set -euo pipefail
source "$(dirname "$0")/common.sh"

PORT="${A2A_SUT_PORT:-9999}"
LEVEL="${A2A_TCK_LEVEL:-must}"
SUT_URL="http://localhost:${PORT}"
TCK_DIR="${A2A_TCK_DIR:-$RESULTS/a2a-tck-src}"
OUT="$RESULTS/a2a-tck"
rm -rf "$OUT"
mkdir -p "$OUT"

if [[ ! -d "$TCK_DIR/.git" ]]; then
  log "cloning a2aproject/a2a-tck ${A2A_TCK_VERSION} into ${TCK_DIR}"
  git clone --quiet --depth 1 --branch "$A2A_TCK_VERSION" https://github.com/a2aproject/a2a-tck.git "$TCK_DIR"
fi
actual=$(git -C "$TCK_DIR" describe --tags --exact-match 2>/dev/null || echo unknown)
if [[ "$actual" != "$A2A_TCK_VERSION" ]]; then
  log "${TCK_DIR} is at '${actual}', expected tag ${A2A_TCK_VERSION}; remove it or set A2A_TCK_DIR"
  exit 2
fi
export UV_PYTHON="${A2A_TCK_PYTHON:-3.12}"
log "installing TCK dependencies with uv (Python ${UV_PYTHON})"
(cd "$TCK_DIR" && uv sync --quiet)

log "building conformance/a2a-sut"
gradle_install :conformance:a2a-sut:installDist

require_free_port "$PORT"
trap stop_app EXIT
start_app "$OUT/sut.log" "$ROOT/conformance/a2a-sut/build/install/a2a-sut/bin/a2a-sut" "$PORT"
wait_for_url "${SUT_URL}/.well-known/agent-card.json" 60 200

log "running a2a-tck ${A2A_TCK_VERSION} --level ${LEVEL} against ${SUT_URL}"
rm -rf "$TCK_DIR/reports"
set +e
(cd "$TCK_DIR" && uv run ./run_tck.py --sut-host "$SUT_URL" --level "$LEVEL" -v "$@") 2>&1 | tee "$OUT/tck.log"
code=${PIPESTATUS[0]}
set -e
cp -R "$TCK_DIR/reports/." "$OUT/" 2>/dev/null || log "the TCK wrote no reports"
log "TCK exit code: ${code}"

# The TCK has no expected-failures option: compare its JUnit failures with the baseline.
BASELINE="${A2A_TCK_BASELINE:-$ROOT/conformance/a2a-tck-baseline.yml}"
if [[ "$BASELINE" == "none" ]]; then
  exit "$code"
fi
(cd "$TCK_DIR" && uv run python - "$OUT/junitreport.xml" "$BASELINE") <<'PY'
import sys
import xml.etree.ElementTree as ET

junit, baseline_file = sys.argv[1], sys.argv[2]
try:
    cases = list(ET.parse(junit).iter("testcase"))
except (OSError, ET.ParseError) as e:
    print(f"[conformance] no usable JUnit report: {e}")
    sys.exit(1)
if not cases:
    print("[conformance] the TCK ran no tests")
    sys.exit(1)
failed = {f"{c.get('classname')}::{c.get('name')}" for c in cases
          if c.find("failure") is not None or c.find("error") is not None}
expected = set()
with open(baseline_file) as f:
    for line in f:
        line = line.strip()
        if line.startswith("- "):
            expected.add(line[2:].strip())
unexpected, stale = sorted(failed - expected), sorted(expected - failed)
for name in sorted(failed & expected):
    print(f"  ~ expected failure: {name}")
for name in unexpected:
    print(f"  x unexpected failure: {name}")
for name in stale:
    print(f"  ! stale baseline entry (now passes or did not run): {name}")
ok = not unexpected and not stale
print(f"[conformance] baseline check {'passed' if ok else 'FAILED'}: {len(cases)} cases, "
      f"{len(failed)} failed ({len(failed & expected)} expected)")
sys.exit(0 if ok else 1)
PY
