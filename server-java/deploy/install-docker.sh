#!/usr/bin/env bash
# 一键安装 Docker Engine + Compose plugin
# 支持发行版：
#   - Debian 系：Ubuntu / Debian
#   - RHEL 系：Alibaba Cloud Linux 3 / CentOS 7-9 / Rocky / AlmaLinux / Anolis OS / RHEL 8-9
# 用法：
#   bash deploy/install-docker.sh              # 默认走 Docker 官方源（海外/能直连 docker.com）
#   bash deploy/install-docker.sh --cn         # 国内服务器：走阿里云镜像源 + 配置 Docker 镜像加速
# 安装完成后请退出当前 shell 重新登录（非 root），使 docker 命令免 sudo 生效
set -euo pipefail

USE_CN_MIRROR=0
for arg in "$@"; do
  case "$arg" in
    --cn) USE_CN_MIRROR=1 ;;
    -h|--help)
      sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "[WARN] 未知参数: $arg" ;;
  esac
done

# ===== OS 识别 =====
if [[ "$(uname -s)" != "Linux" ]]; then
  echo "[ERROR] 仅支持 Linux"; exit 1
fi
if [[ ! -f /etc/os-release ]]; then
  echo "[ERROR] 无法识别系统版本（缺 /etc/os-release）"; exit 1
fi
. /etc/os-release

OS_FAMILY=""
case "${ID:-}" in
  ubuntu|debian|raspbian)
    OS_FAMILY="debian" ;;
  alinux|anolis|centos|rhel|rocky|almalinux|fedora|ol)
    OS_FAMILY="rhel" ;;
  *)
    echo "[WARN] 未识别的发行版: ${ID:-unknown} ${VERSION_ID:-?}"
    if command -v apt-get >/dev/null 2>&1; then
      OS_FAMILY="debian"; echo "[INFO] 检测到 apt-get，按 Debian 系处理"
    elif command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then
      OS_FAMILY="rhel"; echo "[INFO] 检测到 dnf/yum，按 RHEL 系处理"
    else
      echo "[ERROR] 未找到 apt-get / dnf / yum，无法继续"; exit 1
    fi
    ;;
esac

echo "[INFO] 发行版: ${PRETTY_NAME:-${ID:-unknown}} (family=$OS_FAMILY)"
if [[ "$USE_CN_MIRROR" == "1" ]]; then
  echo "[INFO] 国内镜像模式：使用阿里云 docker-ce 镜像 + 配置 Docker daemon 加速器"
else
  echo "[INFO] 海外/官方源模式（如在国内可加 --cn）"
fi

# ===== 检查是否已装 =====
SKIP_INSTALL=0
if command -v docker >/dev/null 2>&1; then
  echo "[INFO] 已检测到 docker：$(docker --version)"
  if docker compose version >/dev/null 2>&1; then
    echo "[INFO] docker compose plugin 已就绪：$(docker compose version)"
    SKIP_INSTALL=1
  else
    echo "[INFO] docker 已装但 compose plugin 缺失，将补装"
  fi
fi

# ===== Debian 系安装函数 =====
install_debian() {
  # 先清掉前一次失败可能残留的 docker 源（指向无法访问的官方源时会让 apt update 整体失败）
  if [[ -f /etc/apt/sources.list.d/docker.list ]]; then
    echo "[INFO] 移除残留的 /etc/apt/sources.list.d/docker.list（避免 apt 整体刷新失败）"
    sudo rm -f /etc/apt/sources.list.d/docker.list
  fi

  echo "[INFO] 清理可能存在的旧版 docker.io / docker-engine ..."
  sudo apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true

  sudo apt-get update
  sudo apt-get install -y ca-certificates curl gnupg lsb-release git

  sudo install -m 0755 -d /etc/apt/keyrings

  if [[ "$USE_CN_MIRROR" == "1" ]]; then
    REPO_BASE="https://mirrors.aliyun.com/docker-ce/linux/${ID}"
  else
    REPO_BASE="https://download.docker.com/linux/${ID}"
  fi

  if [[ ! -f /etc/apt/keyrings/docker.gpg ]]; then
    curl -fsSL "$REPO_BASE/gpg" | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg
  fi

  CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-}")"
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] $REPO_BASE ${CODENAME} stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
}

# ===== RHEL 系安装函数（含 Alibaba Cloud Linux 3） =====
install_rhel() {
  if command -v dnf >/dev/null 2>&1; then
    PKG_MGR="dnf"
  else
    PKG_MGR="yum"
  fi

  # 先清掉前一次失败可能残留的 docker-ce.repo（指向无法访问的官方源时会让 dnf 整体刷新失败）
  if [[ -f /etc/yum.repos.d/docker-ce.repo ]]; then
    echo "[INFO] 移除残留的 /etc/yum.repos.d/docker-ce.repo（避免 dnf 整体刷新失败）"
    sudo rm -f /etc/yum.repos.d/docker-ce.repo
    sudo $PKG_MGR clean metadata 2>/dev/null || true
  fi

  echo "[INFO] 清理可能存在的旧版 docker / podman-docker ..."
  sudo $PKG_MGR remove -y docker docker-client docker-client-latest docker-common \
       docker-latest docker-latest-logrotate docker-logrotate docker-engine \
       podman-docker runc 2>/dev/null || true

  if [[ "$PKG_MGR" == "dnf" ]]; then
    sudo dnf install -y dnf-plugins-core git curl
  else
    sudo yum install -y yum-utils git curl
  fi

  # docker-ce 官方仓库基于 CentOS，使用 $releasever 变量。
  # 但 Alibaba Cloud Linux 3 的 $releasever 是 "3"，Anolis 是 "8"/"23"，需要映射到 CentOS 兼容版本号。
  if [[ "$USE_CN_MIRROR" == "1" ]]; then
    REPO_URL="https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo"
  else
    REPO_URL="https://download.docker.com/linux/centos/docker-ce.repo"
  fi

  if [[ "$PKG_MGR" == "dnf" ]]; then
    sudo dnf config-manager --add-repo "$REPO_URL"
  else
    sudo yum-config-manager --add-repo "$REPO_URL"
  fi

  # 把 docker-ce.repo 里的 $releasever 强制改成 8（alinux 3 / anolis 8 / 类似 RHEL 8 衍生版的兼容选择）
  # 如果用户是 CentOS 9 / Rocky 9，则改成 9
  RHEL_VER="8"
  case "${ID:-}" in
    centos|rhel|rocky|almalinux|ol)
      RHEL_VER="${VERSION_ID%%.*}"  # 取主版本号
      ;;
    fedora)
      # Fedora 没有对应 docker-ce 通用映射，用 8 兜底
      RHEL_VER="8"
      ;;
    alinux)
      # Alibaba Cloud Linux 3 → 兼容 RHEL 8
      RHEL_VER="8"
      ;;
    anolis)
      # Anolis OS 8.x → 8，23 → 8 兜底
      RHEL_VER="${VERSION_ID%%.*}"
      [[ "$RHEL_VER" == "23" || "$RHEL_VER" == "3" ]] && RHEL_VER="8"
      ;;
  esac
  echo "[INFO] 把 docker-ce.repo 中 \$releasever 替换为 $RHEL_VER"
  sudo sed -i "s|\$releasever|${RHEL_VER}|g" /etc/yum.repos.d/docker-ce.repo

  # 如果开启国内镜像，把 repo 里的 download.docker.com 也替换为阿里云
  if [[ "$USE_CN_MIRROR" == "1" ]]; then
    sudo sed -i 's|https://download.docker.com|https://mirrors.aliyun.com/docker-ce|g' /etc/yum.repos.d/docker-ce.repo
  fi

  if [[ "$PKG_MGR" == "dnf" ]]; then
    sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    sudo yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  fi
}

# ===== 执行安装 =====
if [[ "$SKIP_INSTALL" == "0" ]]; then
  case "$OS_FAMILY" in
    debian) install_debian ;;
    rhel)   install_rhel ;;
  esac
fi

# ===== 配置国内镜像加速器（daemon.json） =====
if [[ "$USE_CN_MIRROR" == "1" ]]; then
  echo "[INFO] 配置 Docker daemon 镜像加速器（中科大 / 腾讯云 / 网易 / DaoCloud）"
  sudo mkdir -p /etc/docker
  sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://mirror.ccs.tencentyun.com",
    "https://hub-mirror.c.163.com",
    "https://docker.m.daocloud.io"
  ],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
  sudo systemctl daemon-reload || true
fi

# ===== 启动并设开机自启 =====
sudo systemctl enable --now docker

# ===== 加入 docker 组 =====
if [[ -n "${SUDO_USER:-}" ]]; then
  TARGET_USER="$SUDO_USER"
else
  TARGET_USER="$USER"
fi
NEED_RELOGIN=0
if [[ "$TARGET_USER" != "root" ]]; then
  if ! id -nG "$TARGET_USER" | tr ' ' '\n' | grep -qx docker; then
    sudo usermod -aG docker "$TARGET_USER"
    NEED_RELOGIN=1
  fi
fi

# ===== 验证 =====
echo
echo "===== Docker 安装结果 ====="
sudo docker --version
sudo docker compose version
sudo docker info --format '  Server Version: {{.ServerVersion}}' 2>/dev/null || true
if [[ "$USE_CN_MIRROR" == "1" ]]; then
  sudo docker info --format '  Registry Mirrors: {{.RegistryConfig.Mirrors}}' 2>/dev/null || true
fi
echo "==========================="

if [[ "$NEED_RELOGIN" == "1" ]]; then
  echo "[INFO] 已把用户 '$TARGET_USER' 加入 docker 组。请退出并重新登录使 docker 命令免 sudo 生效。"
else
  echo "[INFO] Docker 已就绪。"
fi
