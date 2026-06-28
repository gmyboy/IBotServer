# 部署说明

两份部署：**Windows 本机** 与 **Linux 线上一键**。

---

## 一、Windows 本机部署（数据落 E 盘，不占 C 盘）

前置：安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)。
> 想让**镜像/构建缓存**也不在 C 盘：Docker Desktop → Settings → Resources → Advanced →
> "Disk image location" 改到 E 盘（如 `E:\DockerData`）→ Apply & Restart。
> （数据库/缓存/日志/配置已由下面脚本固定落到 `-DataRoot`，默认 `E:\PophieData`。）

一键启动（在 `server-java` 目录）：
```powershell
powershell -ExecutionPolicy Bypass -File deploy\windows-deploy.ps1
# 自定义数据盘位置：
powershell -ExecutionPolicy Bypass -File deploy\windows-deploy.ps1 -DataRoot "E:\PophieData"
```
脚本会：检查 Docker → 在 `DataRoot` 建 `mysql/redis/logs` 目录与 `config.yaml` →
生成 `.env`（随机 DB/Redis 密码 + AES_KEY + `DATA_ROOT`）→ `docker compose -f docker-compose.windows.yml up -d --build` → 等待健康。

启用 LLM / 语音 / 后台口令：编辑 `server-java\.env` 填 `LLM_API_KEY` / `DASHSCOPE_API_KEY` / `ADMIN_TOKEN`，重跑脚本即可。

- 访问：前端 http://localhost:8000/ ，后台 http://localhost:8000/admin ，健康 http://localhost:8000/api/health
- 数据位置：`E:\PophieData\{mysql,redis,logs,config.yaml}`
- 停止（保留数据）：`docker compose -f docker-compose.windows.yml down`

---

## 二、Linux 线上一键部署

空 Ubuntu/Debian/alinux 服务器，把 `server-java` 整个目录传上去（或 git clone），然后：
```bash
sudo bash deploy/setup.sh          # 自动探测国内环境
sudo bash deploy/setup.sh --cn     # 强制走阿里云源 + Docker 镜像加速
```
脚本会：装 Docker Engine → 生成 `.env`（随机密码 + AES_KEY）→ 生成 `config.yaml` →
`docker compose up -d --build` → 等待健康。业务密钥（LLM/DashScope/ADMIN_TOKEN）按提示写入 `.env`。

后续升级（已部署过）：
```bash
bash deploy/deploy.sh
```

- 健康：`curl http://localhost:8000/api/health`
- 日志：`docker compose logs -f app`
- 数据：MySQL/Redis 用命名卷 `mysql-data` / `redis-data`（`docker volume ls`）

---

## 文件对照
| 文件 | 用途 |
|---|---|
| `docker-compose.yml` | Linux 标准编排（命名卷） |
| `docker-compose.windows.yml` | Windows 编排（数据落 `${DATA_ROOT}` E 盘 bind mount） |
| `deploy/setup.sh` | Linux 一键：装 Docker + 配置 + 部署 |
| `deploy/deploy.sh` | Linux 升级发布 |
| `deploy/install-docker.sh` | Linux 装 Docker Engine（国内加速） |
| `deploy/windows-deploy.ps1` | Windows 一键部署（PowerShell） |

> 两份部署用同一镜像与同一套 `.env` / `config.yaml` 约定，差异仅在数据卷落盘方式。
