#!/usr/bin/env bash
# 在 WSL 原生磁盘(/opt/pophie，位于 E:\WSL 的 vhdx 内)构建并部署，避开 /mnt/e 的 9p 卡顿。
set -e
SRC=/mnt/e/GithubWorkspace/IBotServer/server-java
DST=/opt/pophie

echo "[0/5] 清理卡住的旧构建并重启 docker"
pkill -f 'compose build' 2>/dev/null || true
pkill -f 'docker-buildx' 2>/dev/null || true
service docker restart || true
sleep 5
docker version >/dev/null

echo "[1/5] 安装 rsync 并同步源码到 $DST"
command -v rsync >/dev/null 2>&1 || { apt-get update -y >/dev/null; apt-get install -y rsync >/dev/null; }
mkdir -p "$DST"
rsync -a --delete --exclude target --exclude .git "$SRC"/ "$DST"/
cd "$DST"
sed -i 's/\r$//' deploy/*.sh deploy/wsl/*.sh 2>/dev/null || true

echo "[2/5] 准备 .env / config.yaml"
[ -f .env ] || cp .env.example .env
if grep -q 'change-me' .env; then
  sed -i "s|^DB_ROOT_PASSWORD=.*|DB_ROOT_PASSWORD=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)|" .env
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)|" .env
  sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)|" .env
fi
grep -q '^AES_KEY=AAAA' .env && sed -i "s|^AES_KEY=.*|AES_KEY=$(openssl rand -base64 32)|" .env
sed -i "s|^JPA_DDL_AUTO=.*|JPA_DDL_AUTO=update|" .env
[ -f config.yaml ] || cp config.example.yaml config.yaml

echo "[3/5] 构建 app 镜像（原生盘，快很多）"
docker compose build app

echo "[4/5] 启动全部服务"
docker compose up -d

echo "[5/5] 状态"
docker compose ps
