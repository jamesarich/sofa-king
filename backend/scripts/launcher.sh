#!/bin/sh
# bin/server of the distribution scripts/dist.sh builds: starts the server from the lib/ directory beside bin/, on the
# java in JAVA_HOME or else on the PATH. JAVA_OPTS and SERVER_OPTS hold JVM options separated by spaces; the image sets
# SERVER_OPTS, so JAVA_OPTS stays the operator's.
set -eu

self=$0
while [ -h "$self" ]; do
    target=$(readlink "$self")
    case $target in
        /*) self=$target ;;
        *) self=$(dirname "$self")/$target ;;
    esac
done
home=$(cd "$(dirname "$self")/.." && pwd -P)

if [ -n "${JAVA_HOME:-}" ]; then
    java=$JAVA_HOME/bin/java
else
    java=java
fi

# shellcheck disable=SC2086 # The options are split on spaces on purpose.
exec "$java" ${JAVA_OPTS:-} ${SERVER_OPTS:-} -cp "$home/lib/*" dev.jamesrich.sofaking.backend.MainKt "$@"
