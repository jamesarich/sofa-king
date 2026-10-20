#!/usr/bin/env bash
# Pulls the published image and swaps it in when it differs from the running one. The database is backed up first;
# when the new container is not healthy in time, the backup and the previous image are put back and that image is
# not tried again. Every run writes what it did to update-status.json in the config folder, which the admin portal's
# About page shows, and starts by removing the update-requested file the portal writes, which the path unit watches.
#
# Install it from the Compose directory with: sudo ./sofa-king-update.sh install
# That writes a service, a 15-minute timer and the path unit for that directory and this copy of the script, and
# starts them; see docs/deploy.md#update-automatically.
set -euo pipefail
# Backups hold provider session tokens, so each is readable by its owner alone: the container's user or root.
umask 077

# Writes the systemd units that run this script for the Compose directory $1 and starts them. SOFA_UPDATE_UNIT_DIR
# writes them elsewhere without starting them, for a check.
install_units() {
  local dir script units config file found=""
  dir=$(cd "$1" && pwd)
  script=$(readlink -f "$0")
  units=${SOFA_UPDATE_UNIT_DIR:-/etc/systemd/system}
  config=${SOFA_UPDATE_CONFIG_DIR:-$dir/config}
  for file in compose.yaml compose.yml docker-compose.yaml docker-compose.yml; do
    [ -f "$dir/$file" ] && found=$file
  done
  [ -n "$found" ] || { echo "sofa-king-update: $dir holds no compose.yaml; run install from the Compose directory, or name it" >&2; exit 1; }
  case "$dir:$script:$config" in
    *[[:space:]%\\]*) echo "sofa-king-update: a systemd unit cannot name a path with a space, % or backslash: $dir" >&2; exit 1 ;;
  esac
  if [ -z "${SOFA_UPDATE_UNIT_DIR:-}" ] && [ "$(id -u)" != 0 ]; then
    echo "sofa-king-update: install writes to $units; run it with sudo" >&2
    exit 1
  fi
  umask 022
  mkdir -p "$units"
  {
    printf '[Unit]\nDescription=Update the Sofa King server from its published image\n'
    printf 'Wants=network-online.target\nAfter=network-online.target docker.service\nRequires=docker.service\n\n'
    printf '[Service]\nType=oneshot\n# The directory with compose.yaml, .env and config/.\nEnvironment=SOFA_UPDATE_DIR=%s\n' "$dir"
    if [ -n "${SOFA_UPDATE_CONFIG_DIR:-}" ]; then printf 'Environment=SOFA_UPDATE_CONFIG_DIR=%s\n' "$config"; fi
    printf 'ExecStart=%s\n' "$script"
  } > "$units/sofa-king-update.service"
  {
    printf '[Unit]\nDescription=Check for a new Sofa King server image every 15 minutes\n\n'
    printf '[Timer]\nOnBootSec=5min\nOnUnitActiveSec=15min\nRandomizedDelaySec=60\n\n[Install]\nWantedBy=timers.target\n'
  } > "$units/sofa-king-update.timer"
  {
    printf '[Unit]\nDescription=Update the Sofa King server when its admin portal asks\n\n'
    printf '[Path]\n# The portal'"'"'s Update now writes this file in the config folder, and the updater removes it.\n'
    printf 'PathExists=%s/update-requested\nUnit=sofa-king-update.service\n\n[Install]\nWantedBy=paths.target\n' "$config"
  } > "$units/sofa-king-update.path"
  echo "sofa-king-update: wrote sofa-king-update.service, .timer and .path to $units for $dir"
  [ -z "${SOFA_UPDATE_UNIT_DIR:-}" ] || return 0
  systemctl daemon-reload
  systemctl enable --now sofa-king-update.timer sofa-king-update.path
  echo "sofa-king-update: checks every 15 minutes; to check now: sudo systemctl start sofa-king-update"
}

if [ "${1:-}" = install ]; then
  install_units "${2:-.}"
  exit 0
fi

dir=${SOFA_UPDATE_DIR:?set SOFA_UPDATE_DIR to the directory with compose.yaml, or run: sudo $0 install}
image=${SOFA_UPDATE_IMAGE:-ghcr.io/jamesarich/sofa-king:main}
container=${SOFA_UPDATE_CONTAINER:-sofa-king-backend}
# The tag Compose runs, which an update points at the new image: compose.yaml's image (the published one, or
# SOFA_IMAGE from .env) unless set.
local_tag=${SOFA_UPDATE_LOCAL_TAG:-$( (cd "$dir" && docker compose config --images 2>/dev/null) | head -n 1 || true)}
local_tag=${local_tag:-sofa-king-backend:local}
previous_tag=${SOFA_UPDATE_PREVIOUS_TAG:-sofa-king-backend:previous}
keep=${SOFA_UPDATE_KEEP_BACKUPS:-5}
wait_seconds=${SOFA_UPDATE_HEALTH_SECONDS:-180}
# The config folder compose.yaml mounts at /config: the portal reads the status file and writes the request file there.
config=${SOFA_UPDATE_CONFIG_DIR:-$dir/config}
status_file="$config/update-status.json"
request_file="$config/update-requested"
state="$dir/backups"

log() { echo "sofa-king-update: $*"; }
# Writes the run's outcome for the portal, readable by the config folder's owner, which is the container's user.
report() {
  local outcome=$1 message=$2
  [ -d "$config" ] || return 0
  printf '{"checkedAt":"%s","image":"%s","running":"%s","candidate":"%s","outcome":"%s","message":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$image" "${from:-}" "${to:-}" "$outcome" "${message//\"/\\\"}" > "$status_file.tmp"
  chmod 644 "$status_file.tmp"
  chown --reference="$config" "$status_file.tmp" 2>/dev/null || true
  mv -f "$status_file.tmp" "$status_file"
}
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
# The portal's request is taken whatever this run finds, so the path unit does not start it again for the same request.
rm -f "$request_file"

if ! docker pull --quiet "$image" >/dev/null; then
  report failed "could not pull $image"
  exit 1
fi
candidate=$(docker image inspect --format '{{.Id}}' "$image")
running=$(docker inspect --format '{{.Image}}' "$container" 2>/dev/null || echo none)
from=$(revision "$running")
to=$(revision "$candidate")
if [ "$candidate" = "$running" ]; then
  report up-to-date "running $to"
  exit 0
fi
if [ -f "$state/failed-image" ] && [ "$(cat "$state/failed-image")" = "$candidate" ]; then
  # When Compose runs the pulled tag itself, the pull moved it to the skipped image; point it back at the running
  # one, so a later docker compose up does not start the skipped image.
  if [ "$local_tag" = "$image" ] && [ "$running" != none ]; then docker tag "$running" "$local_tag"; fi
  report skipped "$to was not healthy before; waiting for a newer image"
  exit 0
fi

log "updating $from to $to"

# The database is in the container's SOFA_DATA_DIR; its host path is the source of the deepest mount holding that directory.
data_dir=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" | sed -n 's/^SOFA_DATA_DIR=//p' | tail -n 1)
[ -n "$data_dir" ] || { log "$container sets no SOFA_DATA_DIR"; report failed "$container sets no SOFA_DATA_DIR"; exit 1; }
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
[ -n "$data" ] || { log "no mount of $container holds $data_dir"; report failed "no mount of $container holds $data_dir"; exit 1; }
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
  report updated "updated $from to $to"
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
  report rolled-back "$to was not healthy after ${wait_seconds}s; running $from again"
else
  log "$from is not healthy either; check docker compose logs"
  report failed "$to was not healthy after ${wait_seconds}s, and $from is not healthy either"
fi
exit 1
