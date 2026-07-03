#!/usr/bin/env bash
# 清空 MySQL 数据卷并用当前 .env 密码重新初始化（会删除库内全部数据）。
# 适用：Access denied / .env 改过密码但 mysql 卷仍是旧密码。
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
[[ "$SCRIPT_SOURCE" = /* ]] || SCRIPT_SOURCE="$(pwd)/$SCRIPT_SOURCE"
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

ENV_FILE="${PROJECT_DIR}/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[ERROR] 未找到 .env"
  exit 1
fi

DATA_ROOT="$(grep '^DATA_ROOT=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
DATA_ROOT="${DATA_ROOT/#\~/$HOME}"
MYSQL_DIR="${DATA_ROOT}/mysql"

echo "[WARN] 将删除 ${MYSQL_DIR} 并重建 MySQL（所有库表数据会丢失）"
read -r -p "确认请输入 yes: " ans
[[ "$ans" == "yes" ]] || { echo "已取消"; exit 0; }

docker compose -f docker-compose.macos.yml stop mysql app 2>/dev/null || true
rm -rf "${MYSQL_DIR}"
mkdir -p "${MYSQL_DIR}"

echo "[INFO] 启动 MySQL 并等待初始化..."
docker compose -f docker-compose.macos.yml up -d mysql

DB_PASSWORD="$(grep '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
DB_NAME="$(grep '^DB_NAME=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
DB_USER="$(grep '^DB_USER=' "$ENV_FILE" | head -1 | cut -d= -f2-)"

for i in $(seq 1 40); do
  if docker exec pophie-mysql mysql -u"${DB_USER}" -p"${DB_PASSWORD}" -h127.0.0.1 -e "SELECT 1" "${DB_NAME}" >/dev/null 2>&1; then
    echo "[INFO] MySQL 已就绪（${DB_USER}@${DB_NAME}）"
    bash deploy/sync-env-to-local-properties.sh
    echo "[INFO] 可启动 IDE（profiles: dev,local）或：docker compose -f docker-compose.macos.yml up -d app"
    exit 0
  fi
  sleep 3
done

echo "[ERROR] MySQL 初始化超时，请查看：docker compose -f docker-compose.macos.yml logs mysql"
exit 1
