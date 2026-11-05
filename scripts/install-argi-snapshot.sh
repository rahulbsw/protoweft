#!/usr/bin/env bash
# Builds ARGI's Spring-free neutral API at a pinned commit and installs it into the local Maven
# repository, so `./gradlew -PargiSnapshot :agentweave-argi-neutral:build` can resolve it.
#
# ARGI publishes only 2.1.0-RC1 (Spring-based) to Maven Central; the neutral API
# (argi-api, argi-agent-api, argi-agent-core) exists only on ARGI main as 2.1.0-RC2-SNAPSHOT.
# See agentweave-argi-neutral/SPEC.md.
#
# Idempotent: a marker file in the local repository records the installed commit, and the run is
# skipped when it matches ARGI_SHA and every artifact is present.
#
# Environment overrides:
#   ARGI_SRC_DIR       where the ARGI clone lives (default: ~/.cache/agentweave/argi-src)
#   MAVEN_REPO_LOCAL   local Maven repository (default: ~/.m2/repository)
#   ARGI_FORCE=1       rebuild even when the marker matches
set -euo pipefail

# Pinned ARGI main commit (2026-11-05, "Merge pull request #355"). Bump together with
# agentweave-argi-neutral/SPEC.md.
ARGI_SHA="a869538fcee17230bc8f20f6ed76c0d15926220e"
ARGI_REPO="https://github.com/agentic-ai-java/argi.git"
ARGI_VERSION="2.1.0-RC2-SNAPSHOT"

# Only Spring-free modules. argi-runtime-core is needed by argi-agent-core (ReActAgent); none of
# them depends on Spring (only Jackson 2/3 and networknt json-schema-validator).
MODULES="argi-api/argi-api-base,argi-runtime/argi-runtime-api,argi-runtime/argi-runtime-core,argi-agent/argi-agent-api,argi-agent/argi-agent-core"
ARTIFACTS="argi-api argi-runtime-api argi-runtime-core argi-agent-api argi-agent-core"

SRC_DIR="${ARGI_SRC_DIR:-$HOME/.cache/agentweave/argi-src}"
REPO_LOCAL="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
GROUP_DIR="$REPO_LOCAL/io/github/agentic-ai-java"
MARKER="$GROUP_DIR/.agentweave-argi-sha"

installed() {
    [[ -f "$MARKER" && "$(cat "$MARKER")" == "$ARGI_SHA" ]] || return 1
    local artifact
    for artifact in $ARTIFACTS; do
        [[ -f "$GROUP_DIR/$artifact/$ARGI_VERSION/$artifact-$ARGI_VERSION.jar" ]] || return 1
    done
}

if [[ "${ARGI_FORCE:-0}" != "1" ]] && installed; then
    echo "ARGI $ARGI_VERSION @ $ARGI_SHA already installed in $REPO_LOCAL; skipping."
    exit 0
fi

if [[ ! -d "$SRC_DIR/.git" ]]; then
    echo "Cloning $ARGI_REPO into $SRC_DIR"
    mkdir -p "$(dirname "$SRC_DIR")"
    git clone --quiet --filter=blob:none "$ARGI_REPO" "$SRC_DIR"
fi

if ! git -C "$SRC_DIR" cat-file -e "$ARGI_SHA^{commit}" 2>/dev/null; then
    git -C "$SRC_DIR" fetch --quiet origin
fi
# The clone is private to this script: force discards edits the build itself makes
# (ARGI's sorter plugin rewrites pom.xml files).
git -C "$SRC_DIR" checkout --quiet --force --detach "$ARGI_SHA"

echo "Installing ARGI $ARGI_VERSION @ $ARGI_SHA ($MODULES)"
(
    cd "$SRC_DIR"
    ./mvnw -B -ntp -DskipTests -Dmaven.javadoc.skip=true -Dmaven.repo.local="$REPO_LOCAL" \
        -pl "$MODULES" -am install
)
git -C "$SRC_DIR" checkout --quiet --force --detach "$ARGI_SHA"

mkdir -p "$GROUP_DIR"
echo "$ARGI_SHA" > "$MARKER"
installed || { echo "ARGI artifacts missing after install" >&2; exit 1; }
echo "ARGI $ARGI_VERSION @ $ARGI_SHA installed in $REPO_LOCAL"
