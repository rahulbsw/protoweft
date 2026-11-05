#!/usr/bin/env bash
# Runs the official MCP conformance suite's client scenarios against conformance/mcp-client.
# The suite starts its own mock servers and launches the client once per scenario.
#
#   scripts/conformance/mcp-client.sh [scenario...]
#
# Default scenarios: the non-auth client scenarios required for 2025-11-25. The auth/* scenarios
# are not run yet (agentweave-mcp has no MCP authorization flow).
# Env: MCP_CONFORMANCE_VERSION (default 0.2.0-alpha.12), MCP_SPEC_VERSION (default 2025-11-25).
# Results: build/conformance/mcp-client/. Exits non-zero if any scenario's suite run fails.
set -euo pipefail
source "$(dirname "$0")/common.sh"

DEFAULT_SCENARIOS=(initialize tools_call elicitation-sep1034-client-defaults sse-retry)
if (( $# > 0 )); then SCENARIOS=("$@"); else SCENARIOS=("${DEFAULT_SCENARIOS[@]}"); fi

OUT="$RESULTS/mcp-client"
mkdir -p "$OUT"

log "building conformance/mcp-client"
gradle_install :conformance:mcp-client:installDist
CLIENT="$ROOT/conformance/mcp-client/build/install/mcp-client/bin/mcp-client"

status=0
for scenario in "${SCENARIOS[@]}"; do
  dir="$OUT/${scenario//\//-}"
  rm -rf "$dir"
  mkdir -p "$dir"
  log "running ${MCP_CONFORMANCE_PACKAGE} client --scenario ${scenario}"
  set +e
  npx --yes "$MCP_CONFORMANCE_PACKAGE" client \
    --command "$CLIENT" \
    --scenario "$scenario" \
    --spec-version "$MCP_SPEC_VERSION" \
    --expected-failures "$ROOT/conformance/mcp-client-baseline.yml" \
    --output-dir "$dir" 2>&1 | tee "$dir/suite.log"
  code=${PIPESTATUS[0]}
  set -e
  log "scenario ${scenario}: suite exit code ${code}"
  if (( code != 0 )); then status=$code; fi
done

exit "$status"
