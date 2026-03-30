#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────
# GigaGit plugin build script
# Usage:
#   ./build.sh                  — snapshot build
#   ./build.sh --release        — release build
#   ./build.sh --publish        — snapshot build + publish to Maven
#   ./build.sh --release --publish — release build + publish to Maven
#
# Required env vars (for Maven repos & OSC token):
#   GRADLE_WRAPPER_USER       — Maven credentials user
#   GRADLE_WRAPPER_PASSWORD   — Maven credentials password
#   GRADLE_WRAPPER_OSC_TOKEN  — OSC token
# ──────────────────────────────────────────────

RELEASE=false
PUBLISH=false

for arg in "$@"; do
  case $arg in
    --release) RELEASE=true ;;
    --publish) PUBLISH=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

JVM_ARGS=(
  "-Dgradle.wrapperUser=${GRADLE_WRAPPER_USER:-}"
  "-Dgradle.wrapperPassword=${GRADLE_WRAPPER_PASSWORD:-}"
  "-Dgradle.wrapperOscToken=${GRADLE_WRAPPER_OSC_TOKEN:-}"
  "-Drelease=${RELEASE}"
)

TASKS=("buildPlugin")
if [[ "${PUBLISH}" == "true" ]]; then
  TASKS+=("publishMavenPublicationToMavenRepository")
fi

PLUGIN_NAME=$(grep '^pluginName' gradle.properties | cut -d= -f2)
echo "▶ Building ${PLUGIN_NAME} plugin (release=${RELEASE}, publish=${PUBLISH})"
./gradlew "${TASKS[@]}" "${JVM_ARGS[@]}"

# Print artifact path
VERSION=$(grep '^pluginVersion' gradle.properties | cut -d= -f2)
[[ "${RELEASE}" == "false" ]] && VERSION="${VERSION}-SNAPSHOT"
ARTIFACT="build/distributions/${PLUGIN_NAME}-${VERSION}.zip"

if [[ -f "${ARTIFACT}" ]]; then
  echo ""
  echo "✓ Artifact: ${ARTIFACT}"
else
  echo ""
  echo "⚠ Expected artifact not found: ${ARTIFACT}"
fi
