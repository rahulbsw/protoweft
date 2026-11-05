#!/usr/bin/env bash
# Shared helpers for the conformance scripts (sourced, not run).
# Pinned versions live here so local runs and CI use the same suites.

MCP_CONFORMANCE_VERSION="${MCP_CONFORMANCE_VERSION:-0.2.0-alpha.12}"
MCP_CONFORMANCE_PACKAGE="@modelcontextprotocol/conformance@${MCP_CONFORMANCE_VERSION}"
MCP_SPEC_VERSION="${MCP_SPEC_VERSION:-2025-11-25}"
A2A_TCK_VERSION="${A2A_TCK_VERSION:-1.0.0.alpha3}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS="$ROOT/build/conformance"

# The PID of the app this script started; only that process is ever stopped.
APP_PID=""

log() {
  echo "[conformance] $*" >&2
}

# Fails if something already answers on the port, so a suite never tests a stranger's server.
require_free_port() {
  local port="$1"
  if curl -s -o /dev/null --max-time 2 "http://localhost:${port}/"; then
    log "port ${port} is already in use; set the port variable to a free port"
    exit 2
  fi
}

# start_app <log-file> <command...>: runs the command in the background and records its PID.
start_app() {
  local logfile="$1"
  shift
  "$@" >"$logfile" 2>&1 &
  APP_PID=$!
  log "started $1 (pid ${APP_PID}), log: ${logfile}"
}

# Stops the app started by start_app, if it is still running.
stop_app() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    log "stopping pid ${APP_PID}"
    kill "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
  APP_PID=""
}

# wait_for_url <url> <timeout-seconds> [expected-status]: waits until the URL answers
# (with the given HTTP status, if one is given) while the app is still running.
wait_for_url() {
  local url="$1" timeout="$2" expected="${3:-}" start status
  start=$(date +%s)
  while true; do
    if [[ -n "$APP_PID" ]] && ! kill -0 "$APP_PID" 2>/dev/null; then
      log "the app exited before it became ready"
      return 1
    fi
    status=$(curl -s -o /dev/null --max-time 2 -w '%{http_code}' "$url" || true)
    if [[ "$status" != "000" && ( -z "$expected" || "$status" == "$expected" ) ]]; then
      log "ready: ${url} answered ${status}"
      return 0
    fi
    if (( $(date +%s) - start >= timeout )); then
      log "timed out after ${timeout}s waiting for ${url}"
      return 1
    fi
    sleep 0.5
  done
}

gradle_install() {
  (cd "$ROOT" && ./gradlew --quiet "$@")
}
