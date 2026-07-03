#!/usr/bin/env bash
# 根据 server-java/.env 生成/更新 application-local.properties（IDE 本机调试）。
# 避免手抄密码与 Docker MySQL/Redis 不一致导致 Access denied。
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
[[ "$SCRIPT_SOURCE" = /* ]] || SCRIPT_SOURCE="$(pwd)/$SCRIPT_SOURCE"
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${PROJECT_DIR}/.env"
OUT_FILE="${PROJECT_DIR}/src/main/resources/application-local.properties"
LOCAL_PORT="${LOCAL_SERVER_PORT:-9901}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "[ERROR] 未找到 ${ENV_FILE}，请先运行 deploy/macos-deploy.sh"
  exit 1
fi

read_env() {
  local key="$1" default="${2:-}"
  local line
  line="$(grep -E "^${key}=" "$ENV_FILE" | head -1 || true)"
  if [[ -z "$line" ]]; then
    echo "$default"
    return
  fi
  echo "${line#*=}"
}

DATA_ROOT="$(read_env DATA_ROOT "$HOME/PophieData")"
DATA_ROOT="${DATA_ROOT/#\~/$HOME}"
MYSQL_HOST_PORT="$(read_env MYSQL_HOST_PORT 9902)"
REDIS_HOST_PORT="$(read_env REDIS_HOST_PORT 9903)"
DB_NAME="$(read_env DB_NAME pophie)"
DB_USER="$(read_env DB_USER pophie_user)"
DB_PASSWORD="$(read_env DB_PASSWORD)"
REDIS_PASSWORD="$(read_env REDIS_PASSWORD)"
AES_KEY="$(read_env AES_KEY)"

if [[ -z "$DB_PASSWORD" || -z "$REDIS_PASSWORD" || -z "$AES_KEY" ]]; then
  echo "[ERROR] .env 缺少 DB_PASSWORD / REDIS_PASSWORD / AES_KEY"
  exit 1
fi

CONFIG_PATH="${DATA_ROOT}/config.yaml"
if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "[WARN] 未找到 ${CONFIG_PATH}，将仍写入 pophie.config-path"
fi

cat > "$OUT_FILE" <<EOF
# 由 deploy/sync-env-to-local-properties.sh 根据 .env 自动生成，勿手改密码（可改 server.port）。
# IntelliJ Active profiles: dev,local
server.port=${LOCAL_PORT}
spring.application.name=pophie-server
pophie.config-path=${CONFIG_PATH}

spring.datasource.url=jdbc:mysql://127.0.0.1:${MYSQL_HOST_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}

spring.data.redis.host=127.0.0.1
spring.data.redis.port=${REDIS_HOST_PORT}
spring.data.redis.password=${REDIS_PASSWORD}

app.crypto.aes-key=${AES_KEY}
EOF

echo "[INFO] 已写入 ${OUT_FILE}"
echo "[INFO] MySQL 127.0.0.1:${MYSQL_HOST_PORT}/${DB_NAME} 用户 ${DB_USER}"
echo "[INFO] Redis 127.0.0.1:${REDIS_HOST_PORT}"
echo "[INFO] 本机服务端口 ${LOCAL_PORT}（Docker 应用端口见 .env 的 HOST_PORT）"
echo "[HINT] 若仍 Access denied：MySQL 数据卷可能是旧密码初始化的，需重置："
echo "       docker compose -f docker-compose.macos.yml stop mysql app"
echo "       rm -rf \"${DATA_ROOT}/mysql\""
echo "       docker compose -f docker-compose.macos.yml up -d mysql && bash deploy/macos-deploy.sh"
