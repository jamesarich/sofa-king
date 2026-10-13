#!/usr/bin/env bash
# Pulls the published image and swaps it in when it differs from the running one. The database is backed up first;
# when the new container is not healthy in time, the backup and the previous image are put back and that image is
# not tried again. Run it from a timer, see docs/deploy.md#update-automatically.
set -euo pipefail
# Backups hold provider session tokens, so each is readable by its owner alone: the container's user or root.
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
# Copies the database to backups/$1 in the config folder with the container's own sqlite3, written as the container's
# user and readable by it alone. When the exec fails, the host's python3 copies it to backups/ in the Compose directory
# instead, which also holds failed-image.
backup_database() {
  local name=$1
  # shellcheck disable=SC2016 # $1 and $2 expand in the container's shell.
  if docker exec "$container" sh -c 'umask 077 && mkdir -p "$1/backups" && sqlite3 "$1/backend.sqlite" ".backup '\''$1/backups/$2'\''"' \
    sh "$data_dir" "$name" >/dev/null 2>&1 && [ -s "$data/backups/$name" ]; then
    backup="$data/backups/$name"
    return 0
  fi
  log "the container could not back up the database; backing it up with the host's python3"
  rm -f "$data/backups/$name"
  backup="$state/$name"
  python3 - "$data/backend.sqlite" "$backup" <<'EOF'
import sqlite3, sys
source = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
target = sqlite3.connect(sys.argv[2])
source.backup(target)
target.close()
EOF
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

# The database is in the container's SOFA_DATA_DIR; its host path is the source of the deepest mount holding that directory.
data_dir=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" | sed -n 's/^SOFA_DATA_DIR=//p' | tail -n 1)
[ -n "$data_dir" ] || { log "$container sets no SOFA_DATA_DIR"; exit 1; }
data=""
deepest=""
while IFS=$'\t' read -r destination source; do
  [ -n "$destination" ] || continue
  case "$data_dir/" in
    "${destination%/}/"*)
      if [ -z "$deepest" ] || [ "${#destination}" -gt "${#deepest}" ]; then
        deepest=$destination
        data=$source${data_dir#"${destination%/}"}
      fi
      ;;
  esac
done < <(docker inspect --format '{{range .Mounts}}{{.Destination}}{{"\t"}}{{.Source}}{{"\n"}}{{end}}' "$container")
[ -n "$data" ] || { log "no mount of $container holds $data_dir"; exit 1; }
backup_database "backend-$(date -u +%Y%m%dT%H%M%SZ)-${from//[^A-Za-z0-9._-]/_}.sqlite"
# Backup names start with a UTC timestamp, so name order is age order.
for folder in "$state" "$data/backups"; do
  [ -d "$folder" ] || continue
  find "$folder" -maxdepth 1 -name 'backend-*.sqlite' -print0 | sort -zr | tail -zn +"$((keep + 1))" | xargs -0r rm --
done

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
