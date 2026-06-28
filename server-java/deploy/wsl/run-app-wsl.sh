#!/usr/bin/env bash
# 在 WSL 内准备配置并部署 Pophie。用法：bash run-app-wsl.sh [prep|build|all]
set -e
PROJ=/mnt/e/GithubWorkspace/IBotServer/server-java
STAGE="${1:-all}"
cd "$PROJ"

prep() {
  sed -i 's/\r$//' deploy/*.sh deploy/wsl/*.sh 2>/dev/null || true
  [ -f .env ] || cp .env.example .env
  if grep -q 'change-me' .env; then
    RB=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)
    DB=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)
    RP=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)
    sed -i "s|^DB_ROOT_PASSWORD=.*|DB_ROOT_PASSWORD=${RB}|" .env
    sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${DB}|" .env
    sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${RP}|" .env
    echo "[INFO] 已写入随机 DB/Redis 密码"
  fi
  if grep -q '^AES_KEY=AAAA' .env; then
    AK=$(openssl rand -base64 32)
    sed -i "s|^AES_KEY=.*|AES_KEY=${AK}|" .env
    echo "[INFO] 已写入随机 AES_KEY"
  fi
  sed -i "s|^JPA_DDL_AUTO=.*|JPA_DDL_AUTO=update|" .env
  [ -f config.yaml ] || cp config.example.yaml config.yaml
  echo "=== 预拉 mysql:8.0 / redis:7-alpine ==="
  docker compose pull mysql redis
  docker images
}

build() {
  echo "=== 构建 app 镜像（首次较慢：容器内 Maven 拉依赖）==="
  docker compose build app
  echo "=== 启动全部服务 ==="
  docker compose up -d
  docker compose ps
}

case "$STAGE" in
  prep)  prep ;;
  build) build ;;
  all)   prep; build ;;
esac
