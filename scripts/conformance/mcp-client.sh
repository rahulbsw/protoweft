#!/usr/bin/env bash
# Runs the official MCP conformance suite's client scenarios against conformance/mcp-client.
# The suite starts its own mock servers and launches the client once per scenario.
#
#   scripts/conformance/mcp-client.sh [scenario...]
#
# Default scenarios: every client scenario required for 2025-11-25 (`conformance list --client
# --requirements 2025-11-25`): the 4 non-auth ones and the 14 auth/* ones (the suite's `auth` suite),
# which run McpClientConnector with protoweft-oauth's OAuthClient as authorizer.
# Env: MCP_CONFORMANCE_VERSION (default 0.2.0-alpha.12), MCP_SPEC_VERSION (default 2025-11-25).
# Results: build/conformance/mcp-client/. Exits non-zero if any scenario's suite run fails.
set -euo pipefail
source "$(dirname "$0")/common.sh"

CORE_SCENARIOS=(initialize tools_call elicitation-sep1034-client-defaults sse-retry)
AUTH_SCENARIOS=(
  auth/metadata-default auth/metadata-var1 auth/metadata-var2 auth/metadata-var3
  auth/basic-cimd
  auth/scope-from-www-authenticate auth/scope-from-scopes-supported auth/scope-omitted-when-undefined
  auth/scope-step-up auth/scope-retry-limit
  auth/token-endpoint-auth-basic auth/token-endpoint-auth-post auth/token-endpoint-auth-none
  auth/pre-registration
)
DEFAULT_SCENARIOS=("${CORE_SCENARIOS[@]}" "${AUTH_SCENARIOS[@]}")
if (( $# > 0 )); then SCENARIOS=("$@"); else SCENARIOS=("${DEFAULT_SCENARIOS[@]}"); fi

OUT="$RESULTS/mcp-client"
mkdir -p "$OUT"

log "building conformance/mcp-client"
gradle_install :conformance:mcp-client:installDist
CLIENT="$ROOT/conformance/mcp-client/build/install/mcp-client/bin/mcp-client"

status=0
failed=()
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
  if (( code != 0 )); then status=$code; failed+=("$scenario"); fi
done

if (( ${#failed[@]} > 0 )); then
  log "scenarios with unexpected results (not matching the baseline): ${failed[*]}"
else
  log "all ${#SCENARIOS[@]} scenario(s) match the baseline"
fi
exit "$status"
