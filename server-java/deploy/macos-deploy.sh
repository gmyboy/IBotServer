#!/usr/bin/env bash
# macOS 本机一键部署 Pophie（Docker Desktop）。
# 用法：
#   bash deploy/macos-deploy.sh
#   bash deploy/macos-deploy.sh --data-root /Users/you/PophieData
#   bash deploy/macos-deploy.sh --port 9901
#   bash deploy/macos-deploy.sh --mysql-port 9902
#   bash deploy/macos-deploy.sh --redis-port 9903
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
[[ "$SCRIPT_SOURCE" = /* ]] || SCRIPT_SOURCE="$(pwd)/$SCRIPT_SOURCE"
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd)"
SCRIPT_FILE="$SCRIPT_DIR/$(basename "$SCRIPT_SOURCE")"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

DATA_ROOT="${HOME}/PophieData"
HOST_PORT="9900"
MYSQL_HOST_PORT="9902"
REDIS_HOST_PORT="9903"
MQTT_HOST_PORT="1883"
MQTT_WS_PORT="8083"
MQTT_DASHBOARD_PORT="18083"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --data-root)
      [[ $# -ge 2 ]] || { echo "[ERROR] --data-root 需要一个目录参数"; exit 1; }
      DATA_ROOT="$2"
      shift 2
      ;;
    --port)
      [[ $# -ge 2 ]] || { echo "[ERROR] --port 需要一个端口参数"; exit 1; }
      HOST_PORT="$2"
      shift 2
      ;;
    --mysql-port)
      [[ $# -ge 2 ]] || { echo "[ERROR] --mysql-port 需要一个端口参数"; exit 1; }
      MYSQL_HOST_PORT="$2"
      shift 2
      ;;
    --redis-port)
      [[ $# -ge 2 ]] || { echo "[ERROR] --redis-port 需要一个端口参数"; exit 1; }
      REDIS_HOST_PORT="$2"
      shift 2
      ;;
    -h|--help)
      sed -n '2,6p' "$SCRIPT_FILE"
      exit 0
      ;;
    *)
      echo "[ERROR] 未知参数：$1"
      echo "用法：bash deploy/macos-deploy.sh [--data-root /path/to/PophieData] [--port 9901] [--mysql-port 9902] [--redis-port 9903]"
      exit 1
      ;;
  esac
done

DATA_ROOT="${DATA_ROOT/#\~/$HOME}"
DATA_ROOT="$(mkdir -p "$DATA_ROOT" && cd "$DATA_ROOT" && pwd)"

if ! [[ "$HOST_PORT" =~ ^[0-9]+$ ]] || [[ "$HOST_PORT" -lt 1 ]] || [[ "$HOST_PORT" -gt 65535 ]]; then
  echo "[ERROR] --port 必须是 1-65535 之间的数字"
  exit 1
fi
if ! [[ "$MYSQL_HOST_PORT" =~ ^[0-9]+$ ]] || [[ "$MYSQL_HOST_PORT" -lt 1 ]] || [[ "$MYSQL_HOST_PORT" -gt 65535 ]]; then
  echo "[ERROR] --mysql-port 必须是 1-65535 之间的数字"
  exit 1
fi
if ! [[ "$REDIS_HOST_PORT" =~ ^[0-9]+$ ]] || [[ "$REDIS_HOST_PORT" -lt 1 ]] || [[ "$REDIS_HOST_PORT" -gt 65535 ]]; then
  echo "[ERROR] --redis-port 必须是 1-65535 之间的数字"
  exit 1
fi
for _mqtt_var in MQTT_HOST_PORT MQTT_WS_PORT MQTT_DASHBOARD_PORT; do
  _mqtt_val="${!_mqtt_var}"
  if ! [[ "$_mqtt_val" =~ ^[0-9]+$ ]] || [[ "$_mqtt_val" -lt 1 ]] || [[ "$_mqtt_val" -gt 65535 ]]; then
    echo "[ERROR] ${_mqtt_var} 必须是 1-65535 之间的数字"
    exit 1
  fi
done

detect_access_host() {
  local iface ip
  for iface in en0 en1; do
    ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
    if [[ -n "$ip" ]]; then
      echo "$ip"
      return
    fi
  done

  iface="$(route get default 2>/dev/null | awk '/interface:/{print $2; exit}' || true)"
  if [[ -n "$iface" ]]; then
    ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
    if [[ -n "$ip" ]]; then
      echo "$ip"
      return
    fi
  fi

  echo "127.0.0.1"
}

echo "[INFO] 项目目录: $(pwd)"
echo "[INFO] 数据目录: ${DATA_ROOT}"
echo "[INFO] 访问端口: ${HOST_PORT}"
echo "[INFO] MySQL 端口: ${MYSQL_HOST_PORT}"
echo "[INFO] Redis 端口: ${REDIS_HOST_PORT}"
echo "[INFO] MQTT 端口: ${MQTT_HOST_PORT} (WS ${MQTT_WS_PORT}, Dashboard ${MQTT_DASHBOARD_PORT})"

# ----- 1. 检查 macOS 与 Docker Desktop -----
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "[ERROR] 当前脚本仅用于 macOS。Linux 请使用 deploy/setup.sh 或 deploy/deploy.sh"
  exit 1
fi

command -v docker >/dev/null 2>&1 || {
  echo "[ERROR] 未检测到 docker。请先安装并启动 Docker Desktop:"
  echo "        https://www.docker.com/products/docker-desktop/"
  exit 1
}

if ! docker info >/dev/null 2>&1; then
  echo "[ERROR] Docker 引擎未运行。请启动 Docker Desktop，等待状态变为 Running 后重试。"
  exit 1
fi

docker compose version >/dev/null 2>&1 || {
  echo "[ERROR] 未检测到 docker compose plugin。请升级 Docker Desktop 后重试。"
  exit 1
}

ACCESS_HOST="$(detect_access_host)"
echo "[INFO] 访问 IP: ${ACCESS_HOST}"

# ----- 2. 创建本机数据目录 -----
mkdir -p "${DATA_ROOT}/mysql" "${DATA_ROOT}/redis" "${DATA_ROOT}/logs" \
  "${DATA_ROOT}/mqtt-data" "${DATA_ROOT}/mqtt-log"

# ----- 3. config.yaml（挂载到容器） -----
if [[ ! -f "${DATA_ROOT}/config.yaml" ]]; then
  cp config.example.yaml "${DATA_ROOT}/config.yaml"
  echo "[INFO] 已生成 ${DATA_ROOT}/config.yaml（密钥可由 .env 覆盖，或直接编辑此文件）"
else
  echo "[INFO] ${DATA_ROOT}/config.yaml 已存在，跳过"
fi

# ----- 4. 生成或补全 .env -----
new_secret() {
  openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"
}

new_aes_key() {
  openssl rand -base64 32
}

print_docker_network_help() {
  echo
  echo "[HINT] Docker 拉取镜像失败，且错误看起来像 Docker Hub 认证域名证书/DNS/代理问题。"
  echo "       如果看到 auth.docker.io 的证书却显示为其他域名，例如 player.jeifm.com，通常不是项目配置问题。"
  echo
  echo "       建议按顺序检查："
  echo "       1. 关闭或切换 VPN/代理/抓包工具后，重启 Docker Desktop。"
  echo "       2. Docker Desktop -> Settings -> Resources -> Proxies，确认没有错误代理。"
  echo "       3. macOS 系统 DNS 改为可信 DNS 后重试，例如路由器/运营商/公司内网指定 DNS。"
  echo "       4. 检查 /etc/hosts 是否写死了 auth.docker.io、registry-1.docker.io 或 docker.io。"
  echo "       5. 如果在公司网络，确认公司代理证书已被 Docker Desktop 信任。"
  echo
  echo "       可用这些命令辅助确认当前解析/代理："
  echo "       scutil --proxy"
  echo "       dscacheutil -q host -a name auth.docker.io"
  echo "       curl -Iv https://auth.docker.io/token"
}

command -v openssl >/dev/null 2>&1 || {
  echo "[ERROR] 未检测到 openssl，无法生成随机密钥。macOS 通常自带 openssl/libressl，请检查 PATH。"
  exit 1
}

if [[ ! -f .env ]]; then
  {
    echo "DB_ROOT_PASSWORD=$(new_secret 24)"
    echo "DB_NAME=pophie"
    echo "DB_USER=pophie_user"
    echo "DB_PASSWORD=$(new_secret 24)"
    echo "REDIS_PASSWORD=$(new_secret 24)"
    echo "AES_KEY=$(new_aes_key)"
    echo "JPA_DDL_AUTO=update"
    echo "DATA_ROOT=${DATA_ROOT}"
    echo "HOST_PORT=${HOST_PORT}"
    echo "MYSQL_HOST_PORT=${MYSQL_HOST_PORT}"
    echo "REDIS_HOST_PORT=${REDIS_HOST_PORT}"
    echo "MQTT_HOST_PORT=${MQTT_HOST_PORT}"
    echo "MQTT_WS_PORT=${MQTT_WS_PORT}"
    echo "MQTT_DASHBOARD_PORT=${MQTT_DASHBOARD_PORT}"
    echo "MQTT_DASHBOARD_USER=admin"
    echo "MQTT_DASHBOARD_PASSWORD=public"
    echo "LLM_API_KEY="
    echo "DASHSCOPE_API_KEY="
    echo "ADMIN_TOKEN="
  } > .env
  echo "[INFO] 已生成 .env（随机 DB/Redis 密码与 AES_KEY）。"
  echo "[ACTION] 如需 LLM/语音/管理后台，请在 .env 填写 LLM_API_KEY / DASHSCOPE_API_KEY / ADMIN_TOKEN 后重跑本脚本。"
else
  echo "[INFO] .env 已存在，跳过生成"
  if grep -q '^DATA_ROOT=' .env; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      sed -i '' "s|^DATA_ROOT=.*|DATA_ROOT=${DATA_ROOT}|" .env
    else
      sed -i "s|^DATA_ROOT=.*|DATA_ROOT=${DATA_ROOT}|" .env
    fi
    echo "[INFO] 已更新 .env 中的 DATA_ROOT=${DATA_ROOT}"
  else
    printf '\nDATA_ROOT=%s\n' "${DATA_ROOT}" >> .env
    echo "[INFO] 已补写 DATA_ROOT=${DATA_ROOT} 到 .env"
  fi

  if grep -q '^HOST_PORT=' .env; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      sed -i '' "s|^HOST_PORT=.*|HOST_PORT=${HOST_PORT}|" .env
    else
      sed -i "s|^HOST_PORT=.*|HOST_PORT=${HOST_PORT}|" .env
    fi
    echo "[INFO] 已更新 .env 中的 HOST_PORT=${HOST_PORT}"
  else
    printf 'HOST_PORT=%s\n' "${HOST_PORT}" >> .env
    echo "[INFO] 已补写 HOST_PORT=${HOST_PORT} 到 .env"
  fi

  if grep -q '^MYSQL_HOST_PORT=' .env; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      sed -i '' "s|^MYSQL_HOST_PORT=.*|MYSQL_HOST_PORT=${MYSQL_HOST_PORT}|" .env
    else
      sed -i "s|^MYSQL_HOST_PORT=.*|MYSQL_HOST_PORT=${MYSQL_HOST_PORT}|" .env
    fi
    echo "[INFO] 已更新 .env 中的 MYSQL_HOST_PORT=${MYSQL_HOST_PORT}"
  else
    printf 'MYSQL_HOST_PORT=%s\n' "${MYSQL_HOST_PORT}" >> .env
    echo "[INFO] 已补写 MYSQL_HOST_PORT=${MYSQL_HOST_PORT} 到 .env"
  fi

  if grep -q '^REDIS_HOST_PORT=' .env; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      sed -i '' "s|^REDIS_HOST_PORT=.*|REDIS_HOST_PORT=${REDIS_HOST_PORT}|" .env
    else
      sed -i "s|^REDIS_HOST_PORT=.*|REDIS_HOST_PORT=${REDIS_HOST_PORT}|" .env
    fi
    echo "[INFO] 已更新 .env 中的 REDIS_HOST_PORT=${REDIS_HOST_PORT}"
  else
    printf 'REDIS_HOST_PORT=%s\n' "${REDIS_HOST_PORT}" >> .env
    echo "[INFO] 已补写 REDIS_HOST_PORT=${REDIS_HOST_PORT} 到 .env"
  fi

  for _pair in \
    "MQTT_HOST_PORT:${MQTT_HOST_PORT}" \
    "MQTT_WS_PORT:${MQTT_WS_PORT}" \
    "MQTT_DASHBOARD_PORT:${MQTT_DASHBOARD_PORT}"; do
    _key="${_pair%%:*}"
    _val="${_pair#*:}"
    if grep -q "^${_key}=" .env; then
      if [[ "$(uname -s)" == "Darwin" ]]; then
        sed -i '' "s|^${_key}=.*|${_key}=${_val}|" .env
      else
        sed -i "s|^${_key}=.*|${_key}=${_val}|" .env
      fi
      echo "[INFO] 已更新 .env 中的 ${_key}=${_val}"
    else
      printf '%s=%s\n' "${_key}" "${_val}" >> .env
      echo "[INFO] 已补写 ${_key}=${_val} 到 .env"
    fi
  done

  if ! grep -q '^MQTT_DASHBOARD_USER=' .env; then
    printf 'MQTT_DASHBOARD_USER=admin\nMQTT_DASHBOARD_PASSWORD=public\n' >> .env
    echo "[INFO] 已补写 MQTT Dashboard 默认账号到 .env"
  fi

  # 兼容旧 .env：仅有 APP_HOST_PORT 时同步为 HOST_PORT
  if ! grep -q '^HOST_PORT=' .env && grep -q '^APP_HOST_PORT=' .env; then
    HOST_PORT="$(grep '^APP_HOST_PORT=' .env | head -1 | cut -d= -f2-)"
    printf 'HOST_PORT=%s\n' "${HOST_PORT}" >> .env
    echo "[INFO] 已从 APP_HOST_PORT 补写 HOST_PORT=${HOST_PORT}"
  fi
fi

if grep -qE '^[A-Z_]+=change-me' .env; then
  echo "[ERROR] .env 中仍有 change-me 占位密码，无法启动容器。"
  echo "        任选其一："
  echo "        1) 删除 .env 后重跑： rm .env && bash deploy/macos-deploy.sh"
  echo "        2) 手动替换 DB_ROOT_PASSWORD / DB_PASSWORD / REDIS_PASSWORD 与 AES_KEY"
  echo "           AES_KEY 生成：openssl rand -base64 32"
  grep -nE '^[A-Z_]+=change-me' .env
  exit 1
fi

if grep -qE '^AES_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' .env; then
  echo "[ERROR] AES_KEY 仍是占位值。生成命令：openssl rand -base64 32"
  exit 1
fi

# ----- 4.5 将 .env 同步到 config.yaml 与 IDE 本地配置 -----
bash deploy/sync-env-to-config.sh
bash deploy/sync-env-to-local-properties.sh

# ----- 5. 构建并启动 -----
echo "[INFO] 构建并启动容器（数据落 ${DATA_ROOT}）..."
COMPOSE_LOG="$(mktemp)"
if ! docker compose -f docker-compose.macos.yml up -d --build 2>&1 | tee "$COMPOSE_LOG"; then
  if grep -qiE 'auth\.docker\.io|registry-1\.docker\.io|failed to fetch anonymous token|failed to verify certificate|x509: certificate' "$COMPOSE_LOG"; then
    print_docker_network_help
  fi
  rm -f "$COMPOSE_LOG"
  exit 1
fi
rm -f "$COMPOSE_LOG"

# ----- 6. 等待健康 -----
echo "[INFO] 等待 app 健康检查 ..."
for i in $(seq 1 30); do
  sleep 3
  status="$(docker inspect --format='{{.State.Health.Status}}' pophie-app 2>/dev/null || echo "starting")"
  echo "  [$i/30] app health: $status"
  [[ "$status" == "healthy" ]] && break
done

read_env_val() {
  local key="$1" fallback="$2"
  if [[ -f .env ]] && grep -q "^${key}=" .env; then
    grep "^${key}=" .env | head -1 | cut -d= -f2-
  else
    echo "$fallback"
  fi
}

MQTT_DASHBOARD_USER="$(read_env_val MQTT_DASHBOARD_USER admin)"
MQTT_DASHBOARD_PASSWORD="$(read_env_val MQTT_DASHBOARD_PASSWORD public)"
MQTT_HOST_PORT="$(read_env_val MQTT_HOST_PORT "${MQTT_HOST_PORT}")"
MQTT_WS_PORT="$(read_env_val MQTT_WS_PORT "${MQTT_WS_PORT}")"
MQTT_DASHBOARD_PORT="$(read_env_val MQTT_DASHBOARD_PORT "${MQTT_DASHBOARD_PORT}")"

echo
echo "===== 部署完成 ====="
docker compose -f docker-compose.macos.yml ps
echo
echo "  - 健康： curl http://${ACCESS_HOST}:${HOST_PORT}/api/health"
echo "  - 前端： http://${ACCESS_HOST}:${HOST_PORT}/   后台： http://${ACCESS_HOST}:${HOST_PORT}/admin"
echo "  - MySQL： ${ACCESS_HOST}:${MYSQL_HOST_PORT}  数据库/用户见 server-java/.env 的 DB_NAME / DB_USER / DB_PASSWORD"
echo "  - Redis： ${ACCESS_HOST}:${REDIS_HOST_PORT}  密码见 server-java/.env 的 REDIS_PASSWORD"
echo "  - MQTT Broker： tcp://${ACCESS_HOST}:${MQTT_HOST_PORT}  （容器内服务用 tcp://mqtt:1883）"
echo "  - MQTT WebSocket： ws://${ACCESS_HOST}:${MQTT_WS_PORT}/mqtt"
echo "  - MQTT Dashboard： http://${ACCESS_HOST}:${MQTT_DASHBOARD_PORT}/  账号 ${MQTT_DASHBOARD_USER} / ${MQTT_DASHBOARD_PASSWORD}"
echo "  - 数据： ${DATA_ROOT}/{mysql,redis,logs,mqtt-data,mqtt-log,config.yaml}"
echo "  - 日志： docker compose -f docker-compose.macos.yml logs -f app"
echo "  - 停止： docker compose -f docker-compose.macos.yml down  (数据保留在 ${DATA_ROOT})"
