#!/usr/bin/env bash
# Rewrites the lockfiles of build-logic, the contract, the app and the server after a version change: each build's
# settings and plugin classpaths, and every configuration each project's gradle.lockfile names, through
# resolve-locked.init.gradle.kts. The app's Yarn lockfiles in kotlin-js-store follow. Run it on macOS: on Linux the
# Apple configurations resolve but keep their old lock state. Dependency verification is lenient here; run
# backend/scripts/update-verification-metadata.sh afterwards for the server's and the contract's checksums.
# GRADLEW overrides the Gradle command, for example GRADLEW="$HOME/.claude/bin/gradle-queue -C . --".
set -euo pipefail
cd "$(dirname "$0")/.."

read -ra gradle <<< "${GRADLEW:-./gradlew}"
flags=(--write-locks --dependency-verification lenient --no-configuration-cache --init-script "$PWD/gradle/resolve-locked.init.gradle.kts")

for build in build-logic contract app backend; do
  tasks=(resolveLockedConfigurations)
  [[ $build == app ]] && tasks+=(kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock)
  echo "update-locks: $build" >&2
  "${gradle[@]}" -p "$build" "${flags[@]}" "${tasks[@]}"
done

# Gradle names the build it ran from in each lockfile's header comment; keep the old header when nothing else changed.
git diff --name-only -- '*.lockfile' | while read -r lockfile; do
  if ! git diff -U0 -- "$lockfile" | grep -q '^[-+][^-+#]'; then
    git checkout -- "$lockfile"
  fi
done

git status --short -- '*.lockfile' '*/kotlin-js-store/'
