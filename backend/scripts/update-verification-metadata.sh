#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml, and the contract build's, from a clean resolve. --refresh-dependencies and --rerun-tasks make
# Gradle fetch and record every artifact, including the BOM .module files a warm cache never asks for.
# GRADLEW overrides the Gradle command, for example GRADLEW="$HOME/.claude/bin/gradle-queue -C . --".
set -euo pipefail
cd "$(dirname "$0")/.."

read -ra gradle <<< "${GRADLEW:-./gradlew}"
"${gradle[@]}" --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks --no-configuration-cache \
  test :server:installDist

# CI's Gradle setup resolves kotlinx-coroutines-bom 1.8.0 for the build classpath; no local build asks for it.
if ! grep -q 'name="kotlinx-coroutines-bom" version="1.8.0"' gradle/verification-metadata.xml; then
  echo "update-verification-metadata: the kotlinx-coroutines-bom 1.8.0 entry is gone; restore it from git" >&2
  exit 1
fi
(
  cd ../contract
  "${gradle[@]}" --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks --no-configuration-cache \
    assemble jvmTest
)
git diff --stat gradle/verification-metadata.xml ../contract/gradle/verification-metadata.xml
