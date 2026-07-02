#!/usr/bin/env bash
# =============================================================================
# Pophie Java 后端（Spring Boot）一键发布到 Linux 服务器
# -----------------------------------------------------------------------------
# 做什么：
#   1. 本地 rsync/scp 上传源码（src/pom.xml/Dockerfile/docker-compose.yml 等）
#      —— 绝不上传 .env / config.yaml（远端已配置好，避免覆盖密钥）
#   2. 远端 docker compose build app（多阶段：maven 打 jar → JRE 运行镜像）
#   3. docker compose up -d（仅重建 app 容器；mysql/redis 用命名卷，数据不丢）
#   4. 健康检查（/actuator/health 容器内 + /api/health 端口外）
#
# 用法：
#   bash deploy/deploy-server-java.sh              # 默认：上传源码 + 构建 + 重启 app
#   bash deploy/deploy-server-java.sh --no-build   # 不重新 build，仅 up（用已有镜像）
#   bash deploy/deploy-server-java.sh --no-restart # 仅上传源码，不构建不重启
#   bash deploy/deploy-server-java.sh --logs       # 部署后直接 follow 日志（ctrl+C 退出不影响服务）
#
# 可用环境变量覆盖（带默认值）：
#   REMOTE_HOST=223.109.143.135   REMOTE_USER=root
#   REMOTE_DIR=/opt/IBotServer-java   APP_PORT=9900
# =============================================================================
set -euo pipefail

# ----------------------------- 可配置默认值 ----------------------------------
REMOTE_HOST="${REMOTE_HOST:-223.109.143.135}"
REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_DIR="${REMOTE_DIR:-/opt/IBotServer-java}"
APP_PORT="${APP_PORT:-9900}"

DO_BUILD=1    # --no-build：跳过 docker build，直接 up
DO_UP=1       # --no-restart：仅传源码
SHOW_LOGS=0   # --logs：部署后 follow 日志

for arg in "$@"; do
  case "$arg" in
    --no-build)   DO_BUILD=0 ;;
    --no-restart) DO_UP=0; DO_BUILD=0 ;;   # 仅传源码：既不构建也不重启
    --logs)       SHOW_LOGS=1 ;;
    -h|--help)    sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "[WARN] 未知参数: $arg" ;;
  esac
done

# ----------------------------- 路径与 SSH/SCP 选项 ---------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# 脚本在 server-java/deploy/ 下，项目根在上一层
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15)
SCP_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15)

log()  { echo -e "\033[36m[INFO]\033[0m  $*"; }
ok()   { echo -e "\033[32m[OK]\033[0m    $*"; }
warn() { echo -e "\033[33m[WARN]\033[0m  $*"; }
die()  { echo -e "\033[31m[ERROR]\033[0m $*" >&2; exit 1; }

# ----------------------------- 0. 前置依赖检查 -------------------------------
log "检查本地依赖 ..."
command -v ssh >/dev/null 2>&1 || die "未找到 ssh"
command -v scp >/dev/null 2>&1 || die "未找到 scp"
[[ -f "$PROJECT_ROOT/pom.xml" ]]      || die "未找到 pom.xml（项目根：$PROJECT_ROOT）"
[[ -f "$PROJECT_ROOT/Dockerfile" ]]   || die "未找到 Dockerfile"
[[ -d "$PROJECT_ROOT/src" ]]          || die "未找到 src/ 目录"
ok "本地依赖就绪（项目根：$PROJECT_ROOT）"

REMOTE="$REMOTE_USER@$REMOTE_HOST"

# ----------------------------- 1. 上传源码 -----------------------------------
# 排除清单：绝不覆盖远端的 .env / config.yaml（含密钥）、本地构建产物、IDE 文件、
#           前端 vben-admin（独立发布）、git 历史、node_modules
log "上传源码到 $REMOTE:$REMOTE_DIR ..."
ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p '$REMOTE_DIR'"

# 用 tar 流式上传：本地打包（排除敏感/无关文件）→ 管道 ssh → 远端解包
# 这样只需一次连接，且天然支持 exclude，比逐个 scp 高效
UPLOAD_EXCLUDES=(
  --exclude='.git'
  --exclude='.idea'
  --exclude='target'
  --exclude='logs'
  --exclude='*.log'
  --exclude='.env'                 # 远端已配置，绝不覆盖
  --exclude='config.yaml'          # 远端已配置，绝不覆盖
  --exclude='node_modules'
  --exclude='vben-admin'           # 前端独立发布（deploy-admin.sh）
  --exclude='.turbo'
  --exclude='admin-dist.zip'
)

log "  打包并传输（tar over ssh，排除 .env/config.yaml/vben-admin 等）..."
( cd "$PROJECT_ROOT" && tar czf - "${UPLOAD_EXCLUDES[@]}" . ) \
  | ssh "${SSH_OPTS[@]}" "$REMOTE" "cd '$REMOTE_DIR' && tar xzf -"
ok "源码上传完成"

# 校验远端 .env / config.yaml 仍在（保险）
log "校验远端配置文件未被覆盖 ..."
ssh "${SSH_OPTS[@]}" "$REMOTE" "REMOTE_DIR='$REMOTE_DIR' bash -s" <<'REMOTE'
cd "$REMOTE_DIR"
miss=""
[[ -f .env ]]        || miss="$miss .env"
[[ -f config.yaml ]] || miss="$miss config.yaml"
if [[ -n "$miss" ]]; then
  echo "[ERROR] 缺失配置文件:$miss —— 请勿直接重传，需先按 .env.example / config.example.yaml 生成"
  exit 1
fi
# 简单校验 .env 里没有残留占位值
if grep -qE '^(DB_ROOT_PASSWORD|DB_PASSWORD|REDIS_PASSWORD)=change-me' .env; then
  echo "[ERROR] .env 仍有 change-me 占位密码，请先填真实值"; exit 1
fi
echo "[OK]    .env / config.yaml 就绪（密钥保留）"
REMOTE

# ----------------------------- 2. 远端构建镜像 ------------------------------
if [[ "$DO_BUILD" == "1" ]]; then
  log "远端 docker compose build app（多阶段构建：maven 打包 → JRE 运行镜像）..."
  log "  （首次较慢；底层镜像 maven/temurin 已预拉，后续构建走缓存）"
  ssh "${SSH_OPTS[@]}" "$REMOTE" "REMOTE_DIR='$REMOTE_DIR' bash -s" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
# 把 Windows 上传的脚本转 LF，避免 shell 报错（不影响 java 构建，但 deploy/*.sh 可能被调用）
find deploy -name '*.sh' -exec sed -i 's/\r$//' {} + 2>/dev/null || true
echo "----- build start -----"
docker compose build app
echo "----- build done ------"
REMOTE
  ok "镜像构建完成"
else
  warn "跳过构建（--no-build），使用已有镜像 pophie-server:latest"
fi

# ----------------------------- 3. 重启 app 容器 -----------------------------
if [[ "$DO_UP" == "1" ]]; then
  log "重启 app 容器（mysql/redis 数据卷保留，不重启）..."
  ssh "${SSH_OPTS[@]}" "$REMOTE" "REMOTE_DIR='$REMOTE_DIR' bash -s" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
# up -d：检测到 app 镜像变化会自动重建 app；mysql/redis 配置未变则不动（数据卷安全）
docker compose up -d --no-deps app
echo "[remote] 当前容器："
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
REMOTE
else
  ok "仅上传源码完成（--no-restart，未构建未重启）"
  exit 0
fi

# ----------------------------- 4. 健康检查 -----------------------------------
# 单次 SSH 远端轮询，避免高频短连接触发 sshd 限流
log "等待 app 健康并校验（远端单连接轮询，最多 ~90s）..."
result="$(ssh "${SSH_OPTS[@]}" "$REMOTE" "APP_PORT='$APP_PORT' bash -s" <<'REMOTE'
set +e
healthy=0
# Spring Boot 启动约 15-40s，最多等 90s
for i in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$APP_PORT/api/health" 2>/dev/null)"
  if [[ "$code" == "200" ]]; then healthy=1; break; fi
  echo "  [$i/30] app /api/health HTTP $code，等待中 ..."
  sleep 3
done
if [[ "$healthy" == "1" ]]; then echo "APP_OK"; else echo "APP_FAIL:$code"; fi
echo "HEALTH:$(curl -s "http://localhost:$APP_PORT/api/health" 2>/dev/null)"
echo "CONTAINER:$(docker inspect --format='{{.State.Status}} / health: {{.State.Health.Status}}' pophie-app 2>/dev/null || echo unknown)"
echo "DBCHECK:$(docker exec pophie-mysql mysqladmin ping -uroot -p"$(grep ^DB_ROOT_PASSWORD /opt/IBotServer-java/.env | cut -d= -f2)" 2>/dev/null | tr -d '\n' || echo 'skip')"
REMOTE
)"
  echo "$result"
  echo
  echo "===== 健康检查结果 ====="
  if echo "$result" | grep -q APP_OK; then ok "后端接口   http://localhost:$APP_PORT/api/health → HTTP 200"; else die "后端未就绪（$(echo "$result" | grep APP_FAIL)）"; fi
  echo "  接口响应： $(echo "$result" | grep '^HEALTH:')"
  echo "  容器状态： $(echo "$result" | grep '^CONTAINER:')"
  echo "  MySQL 状态：$(echo "$result" | grep '^DBCHECK:' | sed 's/^DBCHECK://')"
  echo
  echo "===== 访问地址 ====="
  echo "  后端 API：  http://$REMOTE_HOST:$APP_PORT/api/health"
  echo "  管理后台：  http://$REMOTE_HOST:$APP_PORT/admin"
  echo "  Actuator：  http://$REMOTE_HOST:$APP_PORT/actuator/health"
  echo
  echo "===== 运维 ====="
  echo "  日志：    ssh $REMOTE 'cd $REMOTE_DIR && docker compose logs -f app'"
  echo "  重启：    ssh $REMOTE 'cd $REMOTE_DIR && docker compose restart app'"
  echo "  停止：    ssh $REMOTE 'cd $REMOTE_DIR && docker compose down'（数据卷保留）"

# ----------------------------- 5. 可选：follow 日志 --------------------------
if [[ "$SHOW_LOGS" == "1" ]]; then
  echo
  log "follow app 日志（Ctrl+C 退出，不影响服务）..."
  ssh "${SSH_OPTS[@]}" "$REMOTE" "cd '$REMOTE_DIR' && docker compose logs -f --tail=50 app"
fi
