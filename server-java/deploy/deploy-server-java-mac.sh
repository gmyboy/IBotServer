#!/usr/bin/env bash
# =============================================================================
# Pophie Java Backend (Spring Boot) One-Click Deploy to Linux Server
# (macOS local runner -- compatible with macOS bash 3.2 and bsdtar)
# -----------------------------------------------------------------------------
# What it does:
#   1. Upload source via tar-over-ssh (NEVER overwrites remote .env / config.yaml)
#   2. Remote: docker compose build app (multi-stage: maven jar -> JRE image)
#   3. Remote: docker compose up -d (only app container; mysql/redis use named volumes)
#   4. Health check (/api/health)
#
# Usage:
#   bash deploy/deploy-server-java-mac.sh              # upload + build + restart app
#   bash deploy/deploy-server-java-mac.sh --no-build   # skip build, just up (reuse image)
#   bash deploy/deploy-server-java-mac.sh --no-restart # upload source only
#   bash deploy/deploy-server-java-mac.sh --logs       # follow logs after deploy
#
# Env overrides (with defaults):
#   REMOTE_HOST=223.109.143.135   REMOTE_USER=root
#   REMOTE_DIR=/opt/IBotServer-java   APP_PORT=9900
#
# macOS compatibility:
#   - Uses ${VAR} brace expansion everywhere to avoid bash 3.2 UTF-8 boundary bugs
#   - Uses bsdtar-compatible --exclude ordering
#   - All sed/awk operations run on remote Linux; local only uses ssh/scp/tar
# =============================================================================
set -euo pipefail

# ----------------------------- Config defaults --------------------------------
REMOTE_HOST="${REMOTE_HOST:-223.109.143.135}"
REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_DIR="${REMOTE_DIR:-/opt/IBotServer-java}"
APP_PORT="${APP_PORT:-9900}"

DO_BUILD=1
DO_UP=1
SHOW_LOGS=0

for arg in "$@"; do
  case "$arg" in
    --no-build)   DO_BUILD=0 ;;
    --no-restart) DO_UP=0; DO_BUILD=0 ;;
    --logs)       SHOW_LOGS=1 ;;
    -h|--help)    sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "[WARN] unknown argument: $arg" ;;
  esac
done

# ----------------------------- Path & SSH options -----------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15)

log()  { printf '\033[36m[INFO]\033[0m  %s\n' "$*"; }
ok()   { printf '\033[32m[OK]\033[0m    %s\n' "$*"; }
warn() { printf '\033[33m[WARN]\033[0m  %s\n' "$*"; }
die()  { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

# ----------------------------- 0. Prerequisite checks ------------------------
log "Checking local dependencies ..."
command -v ssh >/dev/null 2>&1 || die "ssh not found"
command -v scp >/dev/null 2>&1 || die "scp not found"
[[ -f "${PROJECT_ROOT}/pom.xml" ]]      || die "pom.xml not found (project root: ${PROJECT_ROOT})"
[[ -f "${PROJECT_ROOT}/Dockerfile" ]]   || die "Dockerfile not found"
[[ -d "${PROJECT_ROOT}/src" ]]          || die "src/ directory not found"
ok "Local dependencies ready (project root: ${PROJECT_ROOT})"

REMOTE="${REMOTE_USER}@${REMOTE_HOST}"

# ----------------------------- 1. Upload source ------------------------------
log "Uploading source to ${REMOTE}:${REMOTE_DIR} ..."
ssh "${SSH_OPTS[@]}" "${REMOTE}" "mkdir -p '${REMOTE_DIR}'"

# bsdtar on macOS requires --exclude flags BEFORE the path argument
UPLOAD_EXCLUDES=(
  --exclude='.git'
  --exclude='.idea'
  --exclude='target'
  --exclude='logs'
  --exclude='*.log'
  --exclude='.env'
  --exclude='config.yaml'
  --exclude='node_modules'
  --exclude='vben-admin'
  --exclude='.turbo'
  --exclude='admin-dist.zip'
  --exclude='.DS_Store'
)

log "  Packing and transferring (tar over ssh) ..."
( cd "${PROJECT_ROOT}" && tar -czf - "${UPLOAD_EXCLUDES[@]}" . ) \
  | ssh "${SSH_OPTS[@]}" "${REMOTE}" "cd '${REMOTE_DIR}' && tar -xzf -"
ok "Source upload complete"

# Verify remote secrets preserved
log "Verifying remote config files are preserved ..."
ssh "${SSH_OPTS[@]}" "${REMOTE}" "REMOTE_DIR='${REMOTE_DIR}' bash -s" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
miss=""
[[ -f .env ]]        || miss="$miss .env"
[[ -f config.yaml ]] || miss="$miss config.yaml"
if [[ -n "$miss" ]]; then
  echo "[ERROR] Missing config files:$miss"
  exit 1
fi
if grep -qE '^(DB_ROOT_PASSWORD|DB_PASSWORD|REDIS_PASSWORD)=change-me' .env; then
  echo "[ERROR] .env still has change-me placeholder passwords"; exit 1
fi
echo "[OK]    .env / config.yaml ready (secrets preserved)"
REMOTE

# ----------------------------- 2. Remote build -------------------------------
if [[ "${DO_BUILD}" == "1" ]]; then
  log "Remote docker compose build app ..."
  ssh "${SSH_OPTS[@]}" "${REMOTE}" "REMOTE_DIR='${REMOTE_DIR}' bash -s" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
find deploy -name '*.sh' -exec sed -i 's/\r$//' {} + 2>/dev/null || true
echo "----- build start -----"
docker compose build app
echo "----- build done ------"
REMOTE
  ok "Image build complete"
else
  warn "Skipping build (--no-build), using existing image"
fi

# ----------------------------- 3. Restart app container ----------------------
if [[ "${DO_UP}" == "1" ]]; then
  log "Restarting app container ..."
  ssh "${SSH_OPTS[@]}" "${REMOTE}" "REMOTE_DIR='${REMOTE_DIR}' bash -s" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
docker compose up -d --no-deps app
echo "[remote] Current containers:"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
REMOTE
else
  ok "Source upload only (--no-restart)"
  exit 0
fi

# ----------------------------- 4. Health check -------------------------------
log "Waiting for app health (max ~90s) ..."
result="$(ssh "${SSH_OPTS[@]}" "${REMOTE}" "APP_PORT='${APP_PORT}' REMOTE_DIR='${REMOTE_DIR}' bash -s" <<'REMOTE'
set +e
healthy=0
for i in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$APP_PORT/api/health" 2>/dev/null)"
  if [[ "$code" == "200" ]]; then healthy=1; break; fi
  echo "  [$i/30] app /api/health HTTP $code, waiting ..."
  sleep 3
done
if [[ "$healthy" == "1" ]]; then echo "APP_OK"; else echo "APP_FAIL:$code"; fi
echo "HEALTH:$(curl -s "http://localhost:$APP_PORT/api/health" 2>/dev/null)"
echo "CONTAINER:$(docker inspect --format='{{.State.Status}} / health: {{.State.Health.Status}}' pophie-app 2>/dev/null || echo unknown)"
echo "DBCHECK:$(docker exec pophie-mysql mysqladmin ping -uroot -p"$(grep ^DB_ROOT_PASSWORD $REMOTE_DIR/.env | cut -d= -f2)" 2>/dev/null | tr -d '\n' || echo 'skip')"
REMOTE
)"
  echo "$result"
  echo
  echo "===== Health Check Results ====="
  if echo "$result" | grep -q APP_OK; then ok "Backend API   http://localhost:${APP_PORT}/api/health -> HTTP 200"; else die "Backend not ready"; fi
  echo "  Response:  $(echo "$result" | grep '^HEALTH:')"
  echo "  Container: $(echo "$result" | grep '^CONTAINER:')"
  echo "  MySQL:     $(echo "$result" | grep '^DBCHECK:' | sed 's/^DBCHECK://')"
  echo
  echo "===== Access URLs ====="
  echo "  Backend API:  http://${REMOTE_HOST}:${APP_PORT}/api/health"
  echo "  Admin Panel:  http://${REMOTE_HOST}:${APP_PORT}/admin"
  echo "  Actuator:     http://${REMOTE_HOST}:${APP_PORT}/actuator/health"
  echo
  echo "===== Operations ====="
  echo "  Logs:    ssh ${REMOTE} 'cd ${REMOTE_DIR} && docker compose logs -f app'"
  echo "  Restart: ssh ${REMOTE} 'cd ${REMOTE_DIR} && docker compose restart app'"
  echo "  Stop:    ssh ${REMOTE} 'cd ${REMOTE_DIR} && docker compose down'"

# ----------------------------- 5. Optional: follow logs ----------------------
if [[ "${SHOW_LOGS}" == "1" ]]; then
  echo
  log "Following app logs (Ctrl+C to exit) ..."
  ssh "${SSH_OPTS[@]}" "${REMOTE}" "cd '${REMOTE_DIR}' && docker compose logs -f --tail=50 app"
fi
