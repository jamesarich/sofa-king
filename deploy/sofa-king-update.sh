#!/usr/bin/env bash
# Pulls the published image and swaps it in when it differs from the running one. The database is backed up first;
# when the new container is not healthy in time, the backup and the previous image are put back and that image is
# not tried again. Run it from a timer, see docs/deploy.md#update-automatically.
set -euo pipefail
# Backups hold provider session tokens, so they are readable by root alone.
umask 077

dir=${SOFA_UPDATE_DIR:?set SOFA_UPDATE_DIR to the directory with compose.yaml}
image=${SOFA_UPDATE_IMAGE:-ghcr.io/jamesarich/sofa-king-backend:main}
container=${SOFA_UPDATE_CONTAINER:-sofa-king-backend}
local_tag=${SOFA_UPDATE_LOCAL_TAG:-sofa-king-backend:local}
previous_tag=${SOFA_UPDATE_PREVIOUS_TAG:-sofa-king-backend:previous}
keep=${SOFA_UPDATE_KEEP_BACKUPS:-5}
wait_seconds=${SOFA_UPDATE_HEALTH_SECONDS:-180}
state="$dir/backups"

log() { echo "sofa-king-update: $*"; }
revision() { docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$1" 2>/dev/null || echo unknown; }
compose() { (cd "$dir" && docker compose "$@"); }
healthy() {
  local waited=0
  while [ "$waited" -lt "$wait_seconds" ]; do
    [ "$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null)" = healthy ] && return 0
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

exec 9>/run/lock/sofa-king-update.lock
flock -n 9 || { log "another update is running"; exit 0; }
mkdir -p "$state"

docker pull --quiet "$image" >/dev/null
candidate=$(docker image inspect --format '{{.Id}}' "$image")
running=$(docker inspect --format '{{.Image}}' "$container" 2>/dev/null || echo none)
[ "$candidate" = "$running" ] && exit 0
if [ -f "$state/failed-image" ] && [ "$(cat "$state/failed-image")" = "$candidate" ]; then
  exit 0
fi

from=$(revision "$running")
to=$(revision "$candidate")
log "updating $from to $to"

data=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$container")
backup="$state/backend-$(date -u +%Y%m%dT%H%M%SZ)-$from.sqlite"
python3 - "$data/backend.sqlite" "$backup" <<'EOF'
import sqlite3, sys
source = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
target = sqlite3.connect(sys.argv[2])
source.backup(target)
target.close()
EOF
# Backup names start with a UTC timestamp, so name order is age order.
find "$state" -maxdepth 1 -name 'backend-*.sqlite' -print0 | sort -zr | tail -zn +"$((keep + 1))" | xargs -0r rm --

docker tag "$running" "$previous_tag"
docker tag "$candidate" "$local_tag"
compose up -d --no-build --force-recreate
if healthy; then
  rm -f "$state/failed-image"
  docker image prune --force >/dev/null
  log "running $to"
  exit 0
fi

# Migrations only move forward, so the previous image needs the database from before this one started.
log "$to was not healthy after ${wait_seconds}s; restoring $from and its database"
compose stop
owner=$(stat -c '%u:%g' "$data/backend.sqlite")
rm -f "$data/backend.sqlite-wal" "$data/backend.sqlite-shm"
cp "$backup" "$data/backend.sqlite"
chown "$owner" "$data/backend.sqlite"
echo "$candidate" > "$state/failed-image"
docker tag "$previous_tag" "$local_tag"
compose up -d --no-build --force-recreate
if healthy; then
  log "running $from again"
else
  log "$from is not healthy either; check docker compose logs"
fi
exit 1
