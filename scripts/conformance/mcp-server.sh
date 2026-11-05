#!/usr/bin/env bash
# Runs the official MCP conformance suite's server scenarios against conformance/mcp-server.
#
#   scripts/conformance/mcp-server.sh [extra suite args...]
#
# Env: MCP_SERVER_PORT (default 8080), MCP_CONFORMANCE_VERSION (default 0.2.0-alpha.12),
# MCP_SPEC_VERSION (default 2025-11-25). Results: build/conformance/mcp-server/.
# Exits with the suite's code: 0 when only baselined failures remain.
set -euo pipefail
source "$(dirname "$0")/common.sh"

PORT="${MCP_SERVER_PORT:-8080}"
URL="http://localhost:${PORT}/mcp"
OUT="$RESULTS/mcp-server"
rm -rf "$OUT"
mkdir -p "$OUT"

log "building conformance/mcp-server"
gradle_install :conformance:mcp-server:installDist

require_free_port "$PORT"
trap stop_app EXIT
start_app "$OUT/server.log" "$ROOT/conformance/mcp-server/build/install/mcp-server/bin/mcp-server" "$PORT"
wait_for_url "$URL" 60

log "running ${MCP_CONFORMANCE_PACKAGE} server against ${URL}"
set +e
npx --yes "$MCP_CONFORMANCE_PACKAGE" server \
  --url "$URL" \
  --suite active \
  --spec-version "$MCP_SPEC_VERSION" \
  --expected-failures "$ROOT/conformance/mcp-server-baseline.yml" \
  --output-dir "$OUT" \
  "$@" 2>&1 | tee "$OUT/suite.log"
code=${PIPESTATUS[0]}
set -e

log "suite exit code: ${code}"
exit "$code"
