#!/usr/bin/env bash
###############################################################################
# IBotServer 一键部署脚本
#
# 功能：
#   1. 同步本地最新代码到远程服务器（scp，排除本地缓存/密钥）
#   2. 远程安装/更新 Python 依赖
#   3. 远程平滑重启服务（kill 旧进程 → nohup 启动新进程）
#   4. 健康检查验证
#
# 用法：
#   bash deploy.sh                # 默认部署（增量更新依赖 + 重启）
#   bash deploy.sh --build-deps   # 强制重建 venv 并重装依赖
#   bash deploy.sh --no-restart   # 只上传代码不重启
#
# 可通过环境变量覆盖默认值：
#   REMOTE_HOST=1.2.3.4 REMOTE_USER=root REMOTE_DIR=/opt/IBotServer bash deploy.sh
###############################################################################
set -euo pipefail

# ===== 可配置项（环境变量优先）=====
REMOTE_HOST="${REMOTE_HOST:-223.109.143.135}"
REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_DIR="${REMOTE_DIR:-/opt/IBotServer}"
PYPI_INDEX="${PYPI_INDEX:-https://pypi.org/simple}"
PORT="${PORT:-8000}"

# ===== 参数解析 =====
FORCE_INSTALL_DEPS=false
RESTART=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-deps) FORCE_INSTALL_DEPS=true; shift ;;
    --no-restart) RESTART=false; shift ;;
    -h|--help)    sed -n '2,16p' "$0"; exit 0 ;;
    *)            echo "[error] 未知参数: $1" >&2; exit 1 ;;
  esac
done

LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE="${REMOTE_USER}@${REMOTE_HOST}"
SSH_OPTS=(-o StrictHostKeyChecking=no)
SCP_OPTS=(-r -o StrictHostKeyChecking=no)

# ===== 颜色输出 =====
info() { printf "\033[36m[deploy]\033[0m %s\n" "$*"; }
ok()   { printf "\033[32m[ok]\033[0m    %s\n" "$*"; }
warn() { printf "\033[33m[warn]\033[0m  %s\n" "$*"; }
die()  { printf "\033[31m[error]\033[0m %s\n" "$*" >&2; exit 1; }

# ===== Step 1: 本地前置检查 =====
info "本地目录: $LOCAL_DIR"
info "远程目标: ${REMOTE}:${REMOTE_DIR}"
info "服务端口: $PORT"

[[ -f "$LOCAL_DIR/backend/main.py" ]] || die "未找到 backend/main.py，请在项目根目录运行"
[[ -f "$LOCAL_DIR/requirements.txt" ]] || die "未找到 requirements.txt"

# ===== Step 2: 确保远程目录与环境 =====
info "检查远程环境..."
ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p ${REMOTE_DIR}" \
  || die "无法连接远程服务器或创建目录失败"

# ===== Step 3: 上传代码（不覆盖远程 config.yaml / server.log） =====
info "上传项目文件..."
cd "$LOCAL_DIR"

# 需要上传的顶层目录/文件（逐个上传，已存在目录会覆盖内容）
UPLOAD=(backend frontend scripts tests docs android
        requirements.txt requirements-speech.txt
        config.example.yaml run.sh run.bat .gitignore .env.example README.md)

# 过滤出实际存在的目标
FILES=()
for t in "${UPLOAD[@]}"; do
  [[ -e "$t" ]] && FILES+=("$t")
done

scp "${SCP_OPTS[@]}" "${FILES[@]}" "${REMOTE}:${REMOTE_DIR}/" \
  || die "上传文件失败"
ok "代码上传完成"

# ===== Step 4: venv 与依赖 =====
info "检查远程 venv..."
VENV_OK=$(ssh "${SSH_OPTS[@]}" "$REMOTE" "test -f ${REMOTE_DIR}/.venv/bin/python && echo yes || echo no")

if [[ "$VENV_OK" == "no" || "$FORCE_INSTALL_DEPS" == "true" ]]; then
  info "创建远程 venv 并安装依赖..."
  ssh "${SSH_OPTS[@]}" "$REMOTE" \
    "cd ${REMOTE_DIR} && python3 -m venv .venv && \
     .venv/bin/pip install --upgrade pip -i ${PYPI_INDEX} -q && \
     .venv/bin/pip install -r requirements.txt -i ${PYPI_INDEX}" \
    || die "依赖安装失败"
  ok "依赖安装完成"
else
  info "venv 已存在，增量同步依赖..."
  ssh "${SSH_OPTS[@]}" "$REMOTE" \
    "cd ${REMOTE_DIR} && .venv/bin/pip install -r requirements.txt -i ${PYPI_INDEX} -q" \
    || warn "依赖同步有警告（通常无碍）"
  ok "依赖已同步"
fi

# ===== Step 5: 确保配置文件存在（保留已有 config.yaml） =====
info "检查配置文件..."
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "cd ${REMOTE_DIR} && { [[ -f config.yaml ]] && echo '[info] config.yaml 已保留' || { cp config.example.yaml config.yaml && echo '[setup] 已创建 config.yaml，请填入 API Key'; }; }"

# ===== Step 6: 重启服务 =====
if [[ "$RESTART" != "true" ]]; then
  ok "代码已上传（--no-restart，未重启）"
  echo "  手动重启: ssh ${REMOTE} 'cd ${REMOTE_DIR} && pgrep -f backend.main | xargs -r kill; nohup .venv/bin/python -m backend.main > server.log 2>&1 &'"
  exit 0
fi

info "停止旧进程..."
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "pgrep -f 'backend\.main' | xargs -r kill 2>/dev/null; sleep 1; \
   pgrep -f 'backend\.main' | xargs -r kill -9 2>/dev/null; echo done" || true

info "启动新进程..."
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "nohup bash -c 'cd ${REMOTE_DIR} && .venv/bin/python -m backend.main > server.log 2>&1' >/dev/null 2>&1 & echo started"

# ===== Step 7: 健康检查（最多重试 10 次） =====
info "等待服务启动..."
HEALTHY=false
RESP=""
for i in $(seq 1 10); do
  sleep 1
  RESP=$(ssh "${SSH_OPTS[@]}" "$REMOTE" "curl -s -m 3 http://127.0.0.1:${PORT}/api/health" 2>/dev/null || echo "")
  if echo "$RESP" | grep -q '"ok":true'; then
    HEALTHY=true
    break
  fi
  printf "."
done
echo ""

if [[ "$HEALTHY" == "true" ]]; then
  ok "部署成功！服务已运行"
  echo ""
  echo "  健康检查: $RESP"
  echo "  访问地址: http://${REMOTE_HOST}:${PORT}"
  echo "  管理后台: http://${REMOTE_HOST}:${PORT}/admin"
  echo "  查看日志: ssh ${REMOTE} 'tail -f ${REMOTE_DIR}/server.log'"
else
  die "健康检查失败，查看日志: ssh ${REMOTE} 'tail -50 ${REMOTE_DIR}/server.log'"
fi
