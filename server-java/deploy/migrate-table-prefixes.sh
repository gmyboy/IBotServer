#!/usr/bin/env bash
# 为已有 MySQL 库执行表前缀迁移（pb_{模块}_{表名}）并补建新表。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_DIR="$SCRIPT_DIR/../src/main/resources/db"
MYSQL_USER="${MYSQL_USER:-root}"

if [[ -f "$SCRIPT_DIR/../.env" ]]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/../.env"
  MYSQL_DB="${DB_NAME:-pophie}"
  MYSQL_PASSWORD="${DB_ROOT_PASSWORD:-${MYSQL_PASSWORD:-}}"
fi
MYSQL_DB="${MYSQL_DB:-pophie}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

run_sql() {
  local file="$1"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'pophie-mysql'; then
    docker exec -i pophie-mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" < "$file"
  else
    mysql -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-9902}" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" < "$file"
  fi
}

echo "[INFO] 重命名旧表…"
run_sql "$DB_DIR/migrate_legacy_table_names.sql"
echo "[INFO] 确保新表存在…"
run_sql "$DB_DIR/schema.sql"
echo "[OK] 表前缀迁移完成（pb_core / pb_mem / pb_chat / pb_rem / pb_pro / pb_voice）"
