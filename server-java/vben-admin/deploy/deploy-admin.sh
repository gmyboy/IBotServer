#!/usr/bin/env bash
# =============================================================================
# Pophie 管理后台（vben-admin）一键发布到 Linux 服务器
# -----------------------------------------------------------------------------
# 做什么：
#   1. （可选）pnpm 重新构建前端
#   2. 注入运行时 API 地址（patch _app-config-*.js → /api，免重新构建即可改地址）
#   3. 用 Python 打包 dist（强制正斜杠，解决 Windows zip 反斜杠问题）
#   4. 上传 dist + nginx.conf + docker-compose.admin.yml 到远端
#   5. 远端解压、CRLF→LF、起/重建 nginx 容器
#   6. 健康检查（前端首页 + /api 反代到后端）
#
# 用法：
#   bash deploy/deploy-admin.sh              # 用本地已有 dist 发布（默认）
#   bash deploy/deploy-admin.sh --build      # 先 pnpm build:antd 再发布
#   bash deploy/deploy-admin.sh --no-restart # 仅更新静态文件，不动容器
#
# 可用环境变量覆盖（带默认值）：
#   REMOTE_HOST=223.109.143.135   REMOTE_USER=root
#   REMOTE_DIR=/opt/pophie-admin  ADMIN_PORT=9901   API_URL=/api
# =============================================================================
set -euo pipefail

# ----------------------------- 可配置默认值 ----------------------------------
REMOTE_HOST="${REMOTE_HOST:-223.109.143.135}"
REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_DIR="${REMOTE_DIR:-/opt/pophie-admin}"
ADMIN_PORT="${ADMIN_PORT:-9901}"
API_URL="${API_URL:-/api}"

BUILD=0        # --build：发布前重新构建前端
DO_UP=1        # --no-restart：置 0 则只传文件，不 up 容器

for arg in "$@"; do
  case "$arg" in
    --build)      BUILD=1 ;;
    --no-restart) DO_UP=0 ;;
    -h|--help)    sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "[WARN] 未知参数: $arg" ;;
  esac
done

# ----------------------------- 路径与 SSH/SCP 选项 ---------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VBEN_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_DIR="$VBEN_ROOT/apps/web-antd/dist"
WORK_DIR="$SCRIPT_DIR"                       # admin-dist.zip 生成于此
NGINX_CONF="$SCRIPT_DIR/nginx.conf"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.admin.yml"

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15)
SCP_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15)

log()  { echo -e "\033[36m[INFO]\033[0m  $*"; }
ok()   { echo -e "\033[32m[OK]\033[0m    $*"; }
warn() { echo -e "\033[33m[WARN]\033[0m  $*"; }
die()  { echo -e "\033[31m[ERROR]\033[0m $*" >&2; exit 1; }

# ----------------------------- 0. 前置依赖检查 -------------------------------
log "检查本地依赖 ..."
command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || die "未找到 python/python3（打包 dist 需要）"
command -v ssh >/dev/null 2>&1 || die "未找到 ssh"
command -v scp >/dev/null 2>&1 || die "未找到 scp"
[[ -f "$NGINX_CONF" ]]   || die "缺少 $NGINX_CONF"
[[ -f "$COMPOSE_FILE" ]] || die "缺少 $COMPOSE_FILE"
if [[ "$BUILD" == "1" ]]; then
  command -v pnpm >/dev/null 2>&1 || die "--build 需要 pnpm，但未找到"
fi
ok "本地依赖就绪"

# ----------------------------- 1. （可选）构建前端 ---------------------------
if [[ "$BUILD" == "1" ]]; then
  log "pnpm build:antd （构建到 apps/web-antd/dist）..."
  (cd "$VBEN_ROOT" && pnpm install --frozen-lockfile 2>/dev/null || pnpm install)
  (cd "$VBEN_ROOT" && pnpm build:antd)
fi

# ----------------------------- 2. 校验 dist + 注入 API 地址 ------------------
[[ -f "$DIST_DIR/index.html" ]] || die "未找到前端构建产物：$DIST_DIR/index.html（加 --build 先构建）"

# 找到运行时配置文件 _app-config-*.js 并把接口地址改成 $API_URL
APP_CONFIG="$(find "$DIST_DIR" -maxdepth 1 -name '_app-config-*.js' | head -1)"
if [[ -n "$APP_CONFIG" ]]; then
  log "注入运行时 API 地址 → $API_URL （$APP_CONFIG）"
  # 注意：不能用 argv 传 $API_URL，Git Bash(MSYS2) 会把 /api 这种 POSIX 路径
  # 自动转成 Windows 路径(如 C:/Program Files/Git/api)。改用环境变量规避。
  APP_CONFIG_PATH="$APP_CONFIG" APP_API_URL="$API_URL" python <<'PY'
import os, re
path = os.environ['APP_CONFIG_PATH']
url  = os.environ['APP_API_URL']
# 兜底清洗：万一 $API_URL 被 MSYS 转换污染，只保留从 /api 起的后缀
m = re.search(r'(/api.*)', url)
if m and not url.startswith('/api'):
    url = m.group(1)
s = open(path, encoding='utf-8').read()
s2 = re.sub(r'("VITE_GLOB_API_URL"\s*:\s*)"[^"]*"', r'\1"%s"' % url, s)
if s2 == s:
    print("[WARN] 未匹配到 VITE_GLOB_API_URL，原样保留")
open(path, 'w', encoding='utf-8').write(s2)
print("[OK]   ", open(path, encoding='utf-8').read().strip()[:120])
PY
else
  warn "未找到 _app-config-*.js，跳过 API 地址注入（接口地址以打包时 .env.production 为准）"
fi

# ----------------------------- 3. 打包 dist（正斜杠）------------------------
ZIP_FILE="$WORK_DIR/admin-dist.zip"
log "打包 dist → $(basename "$ZIP_FILE") ..."
# 用环境变量传路径，避免 Git Bash(MSYS2) 把 /d/... 等 POSIX 路径转换导致 Python 找不到文件
PACK_SRC="$DIST_DIR" PACK_OUT="$ZIP_FILE" python <<'PY'
import os, zipfile
src = os.environ['PACK_SRC']
out = os.environ['PACK_OUT']
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    n = 0
    for root, dirs, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, src).replace(os.sep, '/')  # 强制正斜杠
            z.write(full, arc); n += 1
    print("[OK]    %d 个文件写入 %s" % (n, out))
PY

# ----------------------------- 4. 上传 ---------------------------------------
REMOTE="$REMOTE_USER@$REMOTE_HOST"
log "上传到 $REMOTE:$REMOTE_DIR ..."
ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p '$REMOTE_DIR/html'"
scp "${SCP_OPTS[@]}" "$ZIP_FILE" "$NGINX_CONF" "$COMPOSE_FILE" "$REMOTE:$REMOTE_DIR/"
ok "上传完成"

# ----------------------------- 5. 远端：解压 + 转码 + 起容器 -----------------
log "远端处理：解压 / CRLF→LF $([[ "$DO_UP" == "1" ]] && echo '/ 起容器') ..."
# 用 bash -s + 环境变量传参 + quoted heredoc（字面量，远端变量在远端展开）
# 注意：必须显式 bash -s，否则 ssh 默认用 login shell（某些系统是 dash），
#       [[ ]] / 数组等 bash 语法会失败，导致脚本静默中断。
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "ADMIN_PORT='$ADMIN_PORT' REMOTE_DIR='$REMOTE_DIR' DO_UP='$DO_UP' bash -s" <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"

# 5.1 解压 dist 到 html/（先清空，避免旧文件残留）
rm -rf html && mkdir html
python3 -c 'import zipfile; zipfile.ZipFile("admin-dist.zip").extractall("html")'

# 5.2 Windows 上传的配置文件去掉 CRLF（否则 nginx 解析失败）
sed -i 's/\r$//' nginx.conf docker-compose.admin.yml

echo "[remote] html/ 目录结构："
ls -F html/ | head -20

# 5.3 启动 / 重建 nginx 容器（与现有 Java/Python 部署完全解耦）
if [[ "$DO_UP" == "1" ]]; then
  # 复用 Java 版已建的桥接网络（容器名 pophie-app 可直接解析）
  # --force-recreate：确保 nginx.conf / healthcheck 等配置变更一定生效
  #   （compose 对 volume 挂载内容变化不会触发重建）
  export ADMIN_HOST_PORT="$ADMIN_PORT"
  docker compose -f docker-compose.admin.yml up -d --force-recreate
fi
REMOTE

# ----------------------------- 6. 健康检查 -----------------------------------
# 用单次 SSH 在远端循环 curl，避免高频短连接触发服务器 sshd 限流（MaxStartups）
if [[ "$DO_UP" == "1" ]]; then
  log "等待 nginx 就绪并校验（远端单连接轮询）..."
  result="$(ssh "${SSH_OPTS[@]}" "$REMOTE" "ADMIN_PORT='$ADMIN_PORT' bash -s" <<'REMOTE'
set +e
healthy=0
for i in $(seq 1 20); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$ADMIN_PORT/" 2>/dev/null)"
  [[ "$code" == "200" ]] && { healthy=1; break; }
  echo "  [$i/20] nginx 响应 HTTP $code，等待中 ..."
  sleep 2
done
if [[ "$healthy" == "1" ]]; then
  echo "INDEX_OK"
else
  echo "INDEX_FAIL:$code"
fi
echo "API:$(curl -s -w '|HTTP %{http_code}' "http://localhost:$ADMIN_PORT/api/health" 2>/dev/null)"
echo "STATUS:$(docker inspect --format='{{.State.Status}} {{.State.Health.Status}}' pophie-admin-nginx 2>/dev/null || echo unknown)"
REMOTE
)"
  echo "$result"
  echo
  echo "===== 健康检查结果 ====="
  if echo "$result" | grep -q INDEX_OK; then ok "前端首页   http://localhost:$ADMIN_PORT/  → HTTP 200"; else die "前端首页未就绪（$(echo "$result" | grep INDEX_FAIL)）"; fi
  echo "  /api 反代：$(echo "$result" | grep '^API:')"
  echo "  容器状态：$(echo "$result" | grep '^STATUS:')"
  echo
  echo "===== 访问地址 ====="
  echo "  管理后台：  http://$REMOTE_HOST:$ADMIN_PORT/"
  echo "  健康检查：  http://$REMOTE_HOST:$ADMIN_PORT/api/health"
  echo "  容器名：    pophie-admin-nginx（独立 compose，不影响 Java/Python）"
else
  ok "仅更新静态文件完成（未动容器）。如需生效新 nginx.conf 请手动：docker compose -f docker-compose.admin.yml up -d --force-recreate"
fi
