#!/usr/bin/env bash
# Linux 一键发布：拉代码 + 重建镜像 + 拉起容器
# 前置：deploy/install-docker.sh 已执行，.env 与 config.yaml 已就绪
set -euo pipefail
cd "$(dirname "$0")/.."

# ----- 1. .env / config.yaml 校验 -----
if [[ ! -f .env ]]; then
  echo "[ERROR] .env 不存在。请先：cp .env.example .env 并填写，或运行 deploy/setup.sh"; exit 1
fi
if [[ ! -f config.yaml ]]; then
  echo "[INFO] config.yaml 不存在，从 config.example.yaml 生成"; cp config.example.yaml config.yaml
fi
if grep -qE '^[A-Z_]+=change-me' .env; then
  echo "[ERROR] .env 中仍有 change-me 占位密码，请替换为真实强密码："
  grep -nE '^[A-Z_]+=change-me' .env; exit 1
fi
if grep -qE '^AES_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' .env; then
  echo "[ERROR] AES_KEY 仍是占位值。生成：openssl rand -base64 32"; exit 1
fi

# ----- 2. Docker daemon 校验 -----
command -v docker >/dev/null 2>&1 || { echo "[ERROR] 未安装 docker，请先 bash deploy/install-docker.sh"; exit 1; }
docker info >/dev/null 2>&1 || { echo "[ERROR] 无法连接 Docker daemon（未启动或当前用户未在 docker 组）"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[ERROR] 未检测到 docker compose plugin"; exit 1; }

# ----- 3. 拉代码（git 仓库时） -----
if git rev-parse --git-dir >/dev/null 2>&1; then
  echo "[INFO] git pull --ff-only ..."; git pull --ff-only || echo "[WARN] git pull 失败，用本地代码继续"
fi

# ----- 4. 预拉底层镜像 + 构建 + 启动 -----
echo "[INFO] 预拉 mysql:8.0、redis:7-alpine ..."; docker compose pull mysql redis
echo "[INFO] 构建 app 镜像 ..."; docker compose build app
echo "[INFO] 启动服务 ..."; docker compose up -d

# ----- 5. 等待健康（最多 90s） -----
echo "[INFO] 等待 app 健康检查 ..."
for i in $(seq 1 30); do
  sleep 3
  status=$(docker inspect --format='{{.State.Health.Status}}' pophie-app 2>/dev/null || echo "starting")
  echo "  [$i/30] app health: $status"
  [[ "$status" == "healthy" ]] && break
done

echo
echo "===== 部署完成 ====="
docker compose ps
echo
echo "  - 健康：curl http://localhost:8000/api/health"
echo "  - 日志：docker compose logs -f app"
echo "  - 重启：docker compose restart app"
echo "  - 停止：docker compose down  (数据卷保留)"
