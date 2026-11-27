#!/bin/sh
# Builds the server's distribution in server/build/install/server: lib/ holds the server, the contract and every
# library as jars, and bin/server starts them. The Kotlin Toolchain packages the server as one executable JAR with its
# libraries inside; unpacked, the third-party jars keep a layer of their own in the image, and the scripts and the
# app's checks have a program to start.
set -eu

backend=$(cd "$(dirname "$0")/.." && pwd -P)
root=$(dirname "$backend")
dist="$backend/server/build/install/server"
# Where `kotlin package` writes; it prints the same path.
jar="$root/build/tasks/_server_executableJarJvm/server-jvm-executable.jar"

(cd "$root" && ./kotlin package -m server -f executable-jar)
[ -f "$jar" ] || { echo "The Kotlin Toolchain wrote no $jar" >&2; exit 1; }

rm -rf "$dist"
mkdir -p "$dist/bin" "$dist/lib"
unzip -q -j "$jar" 'BOOT-INF/lib/*' -d "$dist/lib"
cp "$backend/scripts/launcher.sh" "$dist/bin/server"
chmod 755 "$dist/bin/server"
echo "Distribution written to $dist"
