#!/usr/bin/env bash
# Pulls the published image and swaps it in when it differs from the running one. The image's signature and build
# attestation are checked first with cosign, the host's or a pinned copy run through docker, and nothing is swapped while a recording runs or is about to start. The database is
# backed up; when the new container is not healthy in time or cannot open a playback session, the backup and the
# previous image are put back and that image is not tried again. Every run writes what it did to update-status.json in the config folder, which the admin portal's
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
# A pull holds the lock, so a hung one would make every later run exit quietly as "another update is running".
pull_seconds=${SOFA_UPDATE_PULL_SECONDS:-900}
# How long the playback check reads each channel's playlist.
smoke_seconds=${SOFA_UPDATE_SMOKE_SECONDS:-20}
# The repository whose backend.yml signs the published image, where the running server answers the gates, and the lock.
verify_repo=jamesarich/sofa-king
server_url=${SOFA_UPDATE_URL:-http://127.0.0.1:${SOFA_PORT:-9193}}
lock_file=${SOFA_UPDATE_LOCK_FILE:-/run/lock/sofa-king-update.lock}
# The config folder compose.yaml mounts at /config: the portal reads the status file and writes the request file there.
config=${SOFA_UPDATE_CONFIG_DIR:-$dir/config}
status_file="$config/update-status.json"
request_file="$config/update-requested"
state="$dir/backups"

log() { echo "sofa-king-update: $*"; }
# What a gate could not check, joined for the report of a run that goes on to install the image.
gate_notes=""
verify_note=""
gate_note() { gate_notes="${gate_notes:+$gate_notes; }$1"; }
# Writes the run's outcome for the portal, readable by the config folder's owner, which is the container's user.
report() {
  local outcome=$1 message=$2 tmp
  [ -d "$config" ] || return 0
  # Backslashes and quotes are escaped and control characters, which JSON forbids in a string, become spaces.
  message=${message//\\/\\\\}
  message=${message//\"/\\\"}
  message=$(printf '%s' "$message" | tr '\000-\037' ' ')
  # The config folder is the container's: the file is made in root's own folder and renamed over whatever is there,
  # so root never writes through a link the container left.
  mkdir -p "$state"
  tmp=$(mktemp "$state/update-status.XXXXXX")
  printf '{"checkedAt":"%s","image":"%s","running":"%s","candidate":"%s","outcome":"%s","message":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$image" "${from:-}" "${to:-}" "$outcome" "$message" > "$tmp"
  chmod 644 "$tmp"
  chown --reference="$config" "$tmp" 2>/dev/null || true
  mv -fT "$tmp" "$status_file"
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
  python3 - "$data/backend.sqlite" "$backup" <<'EOF' || { rm -f "$backup"; return 1; }
import sqlite3, sys
source = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
target = sqlite3.connect(sys.argv[2])
source.backup(target)
target.close()
EOF
}

# Runs a command with a time limit when the host has timeout.
bounded() {
  local seconds=$1
  shift
  if command -v timeout >/dev/null 2>&1; then timeout "$seconds" "$@"; else "$@"; fi
}

# The cosign release that checks the image when the host has no cosign of its own, pinned by digest so the check does
# not trust a moving tag. To bump it, take the newest tag from https://github.com/sigstore/cosign/releases, read its
# digest with `docker buildx imagetools inspect ghcr.io/sigstore/cosign/cosign:<tag> --format '{{.Manifest.Digest}}'`,
# set both here, and run the two checks in verify_image by hand against the current ghcr.io/jamesarich/sofa-king:main.
cosign_image=ghcr.io/sigstore/cosign/cosign:v3.1.4@sha256:0f06154836d2de34f887a5c273506d52ca43edaa1d9e53e6510fa78a48f69c2d

# Runs cosign from the host's path, else from the pinned image.
run_cosign() {
  if command -v cosign >/dev/null 2>&1; then
    bounded 120 cosign "$@"
  else
    bounded 120 docker run --rm "$cosign_image" "$@"
  fi
}

# Invariant: an image is swapped in only if this repository's backend.yml signed it and attested its build, both
# checked with cosign. The one skip is a host with no cosign whose docker cannot pull the pinned image; it sets
# verify_note, which the run's report carries. A check that runs and fails is not a skip.
# Sets verify_error and returns 1 on a failure.
verify_image() {
  local ref identity
  verify_note=""
  case "$image" in
    ghcr.io/"$verify_repo":* | ghcr.io/"$verify_repo"@*) ;;
    *) log "not verifying $image: only ghcr.io/$verify_repo is signed"; return 0 ;;
  esac
  # The digest this pull resolved, so a tag that moves during the checks is not verified instead.
  ref=$(docker image inspect --format '{{index .RepoDigests 0}}' "$image" 2>/dev/null || true)
  ref=${ref:-$image}
  if ! command -v cosign >/dev/null 2>&1 && ! bounded 300 docker pull --quiet "$cosign_image" >/dev/null 2>&1; then
    verify_note="cosign is not installed and docker could not pull $cosign_image, so $ref was not verified"
    log "$verify_note"
    return 0
  fi
  identity="^https://github.com/$verify_repo/\\.github/workflows/backend\\.yml@refs/(heads/main|tags/v.*)\$"
  if ! run_cosign verify "$ref" --certificate-identity-regexp "$identity" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com >/dev/null 2>&1; then
    verify_error="the signature on $ref did not verify"
    return 1
  fi
  log "signature on $ref verified"
  if ! run_cosign verify-attestation "$ref" --type slsaprovenance1 --new-bundle-format \
    --certificate-identity-regexp "$identity" --certificate-oidc-issuer https://token.actions.githubusercontent.com >/dev/null 2>&1; then
    verify_error="the build attestation on $ref did not verify"
    return 1
  fi
  log "build attestation on $ref verified"
}

# Calls the running server for the gates below: api METHOD PATH OUT [JSON]. Prints the HTTP status, 000 when the
# server cannot be reached in time, and writes the body to OUT. The token goes in through curl's stdin, not argv.
api() {
  local method=$1 path=$2 out=$3 json=${4:-}
  printf 'header = "Authorization: Bearer %s"\n' "$api_token" |
    curl --silent --config - --max-time "$api_seconds" --request "$method" --output "$out" --write-out '%{http_code}' \
      ${json:+--header 'Content-Type: application/json' --data "$json"} "$server_url$path" 2>/dev/null || true
}
# One variable of the container's environment, empty when unset.
container_env() {
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" 2>/dev/null | sed -n "s/^$1=//p" | tail -n 1
}
# The host path of the container's SOFA_DATA_DIR: the source of the deepest mount holding that directory. Sets data
# and returns 1, with data_error, when the container names no such directory or no mount holds it.
resolve_data() {
  local destination source deepest=""
  data=""
  data_error=""
  data_dir=$(container_env SOFA_DATA_DIR)
  if [ -z "$data_dir" ]; then
    data_error="$container sets no SOFA_DATA_DIR"
    return 1
  fi
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
  [ -n "$data" ] || { data_error="no mount of $container holds $data_dir"; return 1; }
}
# The API token the server accepts: SOFA_API_TOKEN, else the file SOFA_API_TOKEN_FILE names (read inside the
# container), else the one the server generated into secrets.json in its data directory. Empty when none is found.
read_api_token() {
  local token file secrets
  token=$(container_env SOFA_API_TOKEN)
  if [ -z "$token" ]; then
    file=$(container_env SOFA_API_TOKEN_FILE)
    [ -z "$file" ] || token=$(docker exec "$container" cat "$file" 2>/dev/null | head -n 1 | tr -d '[:space:]' || true)
  fi
  if [ -z "$token" ]; then
    secrets="${data:-$config}/secrets.json"
    if [ -f "$secrets" ] && command -v python3 >/dev/null 2>&1; then
      token=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1])).get("apiToken") or "")' "$secrets" 2>/dev/null || true)
    fi
  fi
  printf '%s' "$token"
}

# Invariant: the container is not recreated while a recording runs or starts before the new one could be healthy,
# since the swap ends the recording and leaves a gap in its file. Sets busy_reason and returns 1 when the update must
# wait; a server that cannot be asked does not block it, since nothing records on a server that is down.
recordings_idle() {
  local body code
  busy_reason=""
  api_token=$(read_api_token)
  if [ -z "$api_token" ]; then
    gate_note "no API token found in the container's environment or secrets.json, so recordings and playback were not checked"
    log "no API token found; not checking for recordings"
    return 0
  fi
  api_seconds=10
  body=$(mktemp "$state/recordings.XXXXXX")
  code=$(api GET /api/v1/recordings "$body")
  if [ "$code" = 200 ]; then
    busy_reason=$(python3 -I - "$body" "$wait_seconds" <<'EOF' || true
import datetime, json, sys

horizon = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=int(sys.argv[2]))
for recording in json.load(open(sys.argv[1])).get("recordings", []):
    title = recording.get("title", "a recording")
    if recording.get("state") == "recording":
        print(f"{title} is recording")
        break
    if recording.get("state") == "scheduled":
        starts = datetime.datetime.fromisoformat(recording["startsAt"].replace("Z", "+00:00"))
        if starts - datetime.timedelta(seconds=recording.get("startPaddingSeconds", 0)) <= horizon:
            print(f"{title} starts at {recording['startsAt']}")
            break
EOF
    )
  else
    log "could not list recordings (HTTP $code); not waiting for any"
  fi
  rm -f "$body"
  [ -z "$busy_reason" ]
}

# Opens a preview session on channel $1 and reads its playlist for up to 20 seconds, with $2 as scratch file. Returns
# 0 when it plays, 1 with smoke_error when the server or its playlist is wrong, 2 when no media came (the playlist
# never answered, as with a dead source) and 3 when every playback slot is in use.
smoke_channel() {
  local channel=$1 body=$2 deadline code last=000 session id url playing=""
  api_seconds=10
  code=$(api POST /api/v1/playback-sessions "$body" "{\"channelId\":\"$channel\",\"intent\":\"preview\",\"idempotencyKey\":\"updater-$(date +%s)-$$\"}")
  [ "$code" != 429 ] || return 3
  session=$(python3 -I -c 'import json, sys; s = json.load(open(sys.argv[1])); print(s["id"], s["descriptor"]["url"])' "$body" 2>/dev/null || true)
  if { [ "$code" != 200 ] && [ "$code" != 201 ]; } || [ -z "$session" ]; then
    smoke_error="a playback session on channel $channel answered HTTP $code"
    return 1
  fi
  id=${session%% *}
  url=${session#* }
  deadline=$(($(date +%s) + smoke_seconds))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    api_seconds=$((deadline - $(date +%s)))
    [ "$api_seconds" -le 10 ] || api_seconds=10
    code=$(api GET "$url" "$body")
    last=$code
    # A media playlist starts with #EXTM3U; a session on the source's own format has no playlist to read.
    if [ "$code" = 200 ]; then
      case "${url%%\?*}" in
        *.m3u8) if head -c 7 "$body" | grep -q '^#EXTM3U'; then playing=yes; fi ;;
        *) playing=yes ;;
      esac
      [ -z "$playing" ] || break
    fi
    sleep 1
  done
  api_seconds=10
  api DELETE "/api/v1/playback-sessions/$id" "$body" >/dev/null
  [ -z "$playing" ] || return 0
  smoke_error="the playlist of the session on channel $channel did not load"
  [ "$last" = 200 ] || return 2
  return 1
}

# Invariant: a new container counts as healthy only if it opens a playback session on a playable channel and serves
# its playlist, which /readyz does not exercise. It tries up to three playable channels, so one dead source does not
# fail a good image, for at most 20 seconds each. A host with no playable channel, no free playback slot or no readable
# API token skips it, and so does one where none of the channels gives media; the last two set a gate note for the
# report. Sets smoke_error and returns 1 on a failure.
playback_smoke() {
  local body code channels channel result tried=0 failure=""
  smoke_error=""
  api_token=$(read_api_token)
  if [ -z "$api_token" ]; then
    gate_note "no API token found in the container's environment or secrets.json, so recordings and playback were not checked"
    log "no API token found; not checking playback"
    return 0
  fi
  body=$(mktemp "$state/playback.XXXXXX")
  api_seconds=10
  code=$(api GET /api/v1/channels "$body")
  case "$code" in
    200) ;;
    401 | 403)
      rm -f "$body"
      gate_note "the API token was refused, so playback was not checked"
      log "the API token was refused; not checking playback"
      return 0
      ;;
    *) rm -f "$body"; smoke_error="the channel list answered HTTP $code"; return 1 ;;
  esac
  channels=$(python3 -I -c 'import json, sys; print(*[c["id"] for c in json.load(open(sys.argv[1])).get("channels", []) if c.get("playbackEligible")][:3], sep="\n")' "$body" 2>/dev/null || true)
  if [ -z "$channels" ]; then
    rm -f "$body"
    log "no channel can play yet; not checking playback"
    return 0
  fi
  while IFS= read -r channel; do
    [ -n "$channel" ] || continue
    tried=$((tried + 1))
    result=0
    smoke_channel "$channel" "$body" || result=$?
    case "$result" in
      0) rm -f "$body"; smoke_error=""; log "playback on channel $channel works"; return 0 ;;
      3) rm -f "$body"; smoke_error=""; log "every playback slot is in use; not checking playback"; return 0 ;;
      1) failure=${failure:-$smoke_error} ;;
    esac
  done <<<"$channels"
  rm -f "$body"
  if [ -n "$failure" ]; then
    smoke_error=$failure
    return 1
  fi
  smoke_error=""
  gate_note "none of $tried channels gave media, so playback was not checked"
  log "none of $tried channels gave media; not checking playback"
}

exec 9>"$lock_file"
flock -n 9 || { log "another update is running"; exit 0; }
mkdir -p "$state"
# The portal's request is taken whatever this run finds, so the path unit does not start it again for the same request.
rm -f "$request_file"

if ! bounded "$pull_seconds" docker pull --quiet "$image" >/dev/null; then
  report failed "could not pull $image"
  exit 1
fi
candidate=$(docker image inspect --format '{{.Id}}' "$image")
running=$(docker inspect --format '{{.Image}}' "$container" 2>/dev/null || echo none)
from=$(revision "$running")
to=$(revision "$candidate")
if [ "$running" = none ]; then
  report failed "there is no $container container; start it with docker compose up -d"
  exit 1
fi
# A container left created but never started by a failed run is not up to date; this run starts it again.
if [ "$candidate" = "$running" ] && [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ]; then
  report up-to-date "running $to"
  exit 0
fi
# When Compose runs the pulled tag itself, the pull moved it to the candidate; a run that does not install the
# candidate points it back at the running image, so a later docker compose up does not start it.
keep_running_tag() {
  if [ "$local_tag" = "$image" ] && [ "$running" != none ]; then docker tag "$running" "$local_tag"; fi
}
if [ -f "$state/failed-image" ] && [ "$(cat "$state/failed-image")" = "$candidate" ]; then
  keep_running_tag
  report skipped "$to was not healthy before; waiting for a newer image"
  exit 0
fi
if ! verify_image; then
  keep_running_tag
  log "$verify_error; not updating"
  report verify-failed "$verify_error; $from is still running"
  exit 1
fi
# The gates read secrets.json from the data directory; a container without one is refused below.
resolve_data || true
if ! recordings_idle; then
  keep_running_tag
  log "$busy_reason; not updating now"
  report skipped "$busy_reason; $from keeps running until the recordings are done"
  exit 0
fi

log "updating $from to $to"

[ -n "$data" ] || { log "$data_error"; report failed "$data_error"; exit 1; }
# No update without a backup to roll back to.
if ! backup_database "backend-$(date -u +%Y%m%dT%H%M%SZ)-${from//[^A-Za-z0-9._-]/_}.sqlite"; then
  report failed "could not back up the database; $from is still running"
  exit 1
fi
# Backup names start with a UTC timestamp, so name order is age order.
for folder in "$state" "$data/backups"; do
  [ -d "$folder" ] || continue
  find "$folder" -maxdepth 1 -name 'backend-*.sqlite' -print0 | sort -zr | tail -zn +"$((keep + 1))" | xargs -0r rm --
done

docker tag "$running" "$previous_tag"
docker tag "$candidate" "$local_tag"
# A container that fails to be created or started counts as unhealthy, so the rollback below still runs.
failure="was not healthy after ${wait_seconds}s"
if compose up -d --no-build --force-recreate && healthy; then
  if playback_smoke; then
    rm -f "$state/failed-image"
    # The update is done; a prune that fails, as one does while another runs, only leaves old images for the next.
    docker image prune --force >/dev/null || log "could not prune old images"
    log "running $to"
    if [ -n "$verify_note" ]; then
      report verify-skipped "updated $from to $to; $verify_note${gate_notes:+; $gate_notes}"
    elif [ -n "$gate_notes" ]; then
      report gate-skipped "updated $from to $to; $gate_notes"
    else
      report updated "updated $from to $to"
    fi
    exit 0
  fi
  failure="failed its playback check: $smoke_error"
fi

# Migrations only move forward, so the previous image needs the database from before this one started.
log "$to $failure; restoring $from and its database"
# Kept first, so a restore that fails below is not followed by another try of this image on the next run.
echo "$candidate" > "$state/failed-image"
# With the container stopped nothing can put a link in place of the database between the rm and the cp.
restore_database() {
  local owner
  compose stop &&
    owner=$(stat -c '%u:%g' "$data/backend.sqlite") &&
    rm -f "$data/backend.sqlite" "$data/backend.sqlite-wal" "$data/backend.sqlite-shm" &&
    cp "$backup" "$data/backend.sqlite" &&
    chown -h "$owner" "$data/backend.sqlite"
}
if ! restore_database; then
  log "could not put back the database from $backup; restore it by hand before starting $from"
  report failed "$to $failure, and its database backup could not be put back"
  exit 1
fi
docker tag "$previous_tag" "$local_tag"
if compose up -d --no-build --force-recreate && healthy; then
  log "running $from again"
  report rolled-back "$to $failure; running $from again"
else
  log "$from is not healthy either; check docker compose logs"
  report failed "$to $failure, and $from is not healthy either"
fi
exit 1
