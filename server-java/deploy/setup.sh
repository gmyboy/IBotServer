#!/usr/bin/env bash
# Linux 真·一键部署：空 Ubuntu/Debian/alinux 服务器 → Pophie 服务上线
# 用法：sudo bash deploy/setup.sh [--cn]
#   --cn  国内服务器，强制走阿里云源 + Docker 镜像加速
set -euo pipefail
cd "$(dirname "$0")/.."

USE_CN=0
for arg in "$@"; do
  case "$arg" in
    --cn) USE_CN=1 ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
  esac
done

# ----- 0. 自动判断是否需要国内镜像（时区检测，零网络依赖） -----
if [[ "$USE_CN" == "0" ]]; then
  TZ_NAME=""
  if command -v timedatectl >/dev/null 2>&1; then
    TZ_NAME=$(timedatectl show -p Timezone --value 2>/dev/null || echo "")
  fi
  [[ -z "$TZ_NAME" && -f /etc/timezone ]] && TZ_NAME=$(cat /etc/timezone 2>/dev/null || echo "")
  case "$TZ_NAME" in
    Asia/Shanghai|Asia/Beijing|Asia/Chongqing|Asia/Urumqi|Asia/Harbin|Asia/Hong_Kong|Asia/Macau|PRC|CST-8)
      echo "[INFO] 时区 [$TZ_NAME] 指示中国大陆/香港，启用国内镜像源"; USE_CN=1 ;;
  esac
fi

# ----- 1. 装 Docker -----
echo
echo "===== 步骤 1/4：安装 Docker Engine ====="
if [[ "$USE_CN" == "1" ]]; then
  bash deploy/install-docker.sh --cn
else
  bash deploy/install-docker.sh
fi

# ----- 2. 准备 .env -----
echo
echo "===== 步骤 2/4：准备 .env 配置 ====="
if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "[INFO] 已生成 .env（基于 .env.example）"
  if command -v openssl >/dev/null 2>&1; then
    DB_ROOT_PW=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)
    DB_PW=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)
    REDIS_PW=$(openssl rand -base64 24 | tr -d '=+/' | head -c 24)
    AES_KEY=$(openssl rand -base64 32)
    sed -i "s|^DB_ROOT_PASSWORD=.*|DB_ROOT_PASSWORD=${DB_ROOT_PW}|" .env
    sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${DB_PW}|" .env
    sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${REDIS_PW}|" .env
    sed -i "s|^AES_KEY=.*|AES_KEY=${AES_KEY}|" .env
    sed -i "s|^JPA_DDL_AUTO=.*|JPA_DDL_AUTO=update|" .env
    echo "[INFO] 已自动生成 DB/Redis 密码与 AES_KEY，JPA_DDL_AUTO=update（首次建表）"
    echo "[INFO] 当前敏感字段（已脱敏）："
    grep -E '^(DB_|REDIS_|AES_|JPA_)' .env | sed 's/=\(.\{4\}\).*/=\1******/'
  else
    echo "[WARN] 未检测到 openssl，请手动替换 .env 中所有 change-me 与 AES_KEY 占位"; exit 1
  fi
  echo
  echo "[ACTION] 业务密钥请填入 .env（也可留空，之后在 admin 后台配置）："
  echo "         LLM_API_KEY=<DeepSeek/OpenAI 等的 key>"
  echo "         DASHSCOPE_API_KEY=<阿里云百炼 key，启用语音才需要>"
  echo "         ADMIN_TOKEN=<管理后台登录口令，强烈建议设置>"
else
  echo "[INFO] .env 已存在，跳过初始化"
fi

# ----- 3. 准备 config.yaml -----
echo
echo "===== 步骤 3/4：准备 config.yaml ====="
if [[ ! -f config.yaml ]]; then
  cp config.example.yaml config.yaml
  echo "[INFO] 已生成 config.yaml（密钥可由 .env 的环境变量覆盖，也可直接编辑此文件）"
else
  echo "[INFO] config.yaml 已存在，跳过"
fi

# ----- 4. 部署 -----
echo
echo "===== 步骤 4/4：构建并启动服务 ====="
if id -nG "$USER" | tr ' ' '\n' | grep -qx docker || [[ "$EUID" == "0" ]]; then
  bash deploy/deploy.sh
else
  echo "[WARN] 当前用户未在 docker 组生效，使用 sudo 兜底"
  sudo -E bash deploy/deploy.sh
fi

echo
echo "🎉 一键部署完成！下次升级直接：bash deploy/deploy.sh"
