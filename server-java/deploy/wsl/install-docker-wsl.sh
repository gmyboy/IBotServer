#!/usr/bin/env bash
# 在 WSL(Ubuntu 22.04) 内安装 Docker，全部走国内镜像；无 systemd 用 service 启动。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

echo "[1/4] 配置 apt 国内源（USTC）"
sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.ustc.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.ustc.edu.cn/ubuntu|g; s|http://ports.ubuntu.com|https://mirrors.ustc.edu.cn|g' /etc/apt/sources.list
apt-get update -y
apt-get install -y ca-certificates curl gnupg openssl

echo "[2/4] 添加 Docker 源（USTC docker-ce 镜像）"
install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
  curl -fsSL https://mirrors.ustc.edu.cn/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
fi
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.ustc.edu.cn/docker-ce/linux/ubuntu jammy stable" > /etc/apt/sources.list.d/docker.list
apt-get update -y

echo "[3/4] 安装 docker-ce + compose 插件"
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

echo "[4/4] 配置镜像加速并启动 Docker"
mkdir -p /etc/docker
cat >/etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://docker.m.daocloud.io", "https://docker.1ms.run", "https://dockerproxy.net"]
}
EOF
service docker start || true
sleep 5
if ! docker version >/dev/null 2>&1; then
  echo "[WARN] service 启动失败，直接拉起 dockerd ..."
  nohup dockerd >/var/log/dockerd.log 2>&1 &
  sleep 8
fi
docker version
echo "===== Docker 安装完成 ====="
