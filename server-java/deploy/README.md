# 部署说明

三份部署：**macOS 本机**、**Windows 本机** 与 **Linux 线上一键**。

---

## 一、macOS 本机部署（Docker Desktop）

前置：安装并启动 [Docker Desktop](https://www.docker.com/products/docker-desktop/)。

一键启动（在 `server-java` 目录）：
```bash
bash deploy/macos-deploy.sh
```
**不要**在未配置 `DATA_ROOT` 时直接 `docker compose -f docker-compose.macos.yml up`（旧 `.env` 会导致挂载 `/redis` 失败）。若必须手写 compose，请在 `.env` 中设置：
```bash
DATA_ROOT=$HOME/PophieData
HOST_PORT=9901
```

```bash
# 自定义数据目录：
bash deploy/macos-deploy.sh --data-root "$HOME/PophieData"
# 自定义宿主机端口：
bash deploy/macos-deploy.sh --port 9901
# 自定义 MySQL 连接端口：
bash deploy/macos-deploy.sh --mysql-port 9902
# 自定义 Redis 连接端口：
bash deploy/macos-deploy.sh --redis-port 9903
```
脚本会：检查 Docker Desktop → 在数据目录建 `mysql/redis/logs` 目录与 `config.yaml` →
生成 `.env`（随机 DB/Redis 密码 + AES_KEY + `DATA_ROOT` + `HOST_PORT` + `MYSQL_HOST_PORT` + `REDIS_HOST_PORT`）→ `docker compose -f docker-compose.macos.yml up -d --build` → 等待健康。

启用 LLM / 语音 / 后台口令：编辑 `server-java/.env` 填 `LLM_API_KEY` / `DASHSCOPE_API_KEY` / `ADMIN_TOKEN`，重跑脚本即可。

- 访问：前端 `http://<本机IP>:9901/`，后台 `http://<本机IP>:9901/admin`，健康 `http://<本机IP>:9901/api/health`
- MQTT：`tcp://<本机IP>:1883`，WebSocket `ws://<本机IP>:8083/mqtt`，控制台 `http://<本机IP>:18083/`（默认 `admin` / `public`）
- 默认数据位置：`~/PophieData/{mysql,redis,logs,config.yaml}`
- 停止（保留数据）：`docker compose -f docker-compose.macos.yml down`

**数据库升级（已有库）：** 若部署前已有 MySQL 数据，需执行 `src/main/resources/db/migrate_user_device.sql`（新增 `pb_core_users` / `pb_core_devices` 用户设备绑定表）。全新部署由 `schema.sql` 自动建表，无需单独迁移。

查看本机 IP：
```bash
ipconfig getifaddr en0 || ipconfig getifaddr en1
```

### IntelliJ 本机调试（连 Docker 里的 MySQL/Redis）

Docker 跑在 **9900/9901**（`HOST_PORT`），IDE 本地跑 **9901** 时，数据库仍连容器映射端口（默认 **9902**）。

每次改 `.env` 或重跑部署后，同步 IDE 配置：

```bash
cd server-java
bash deploy/sync-env-to-local-properties.sh
```

IntelliJ → Run Configuration → **Active profiles: `dev,local`**，再启动 `Application`。

若仍报 `Access denied`：多为 **MySQL 数据目录是旧密码初始化的**（改过 `.env` 但未删数据卷）。重置库（会清空库内数据）：

```bash
cd server-java
bash deploy/reset-mysql-volume.sh   # 交互确认，会删库重建
```

端口对照：**Docker 应用** `HOST_PORT`（你当前 Docker 若是 9900，见 `.env` 的 `APP_HOST_PORT` / `HOST_PORT`）；**IDE 本地** `application-local.properties` 里 `server.port=9901`；**MySQL** 连 `127.0.0.1:9902`（不是 9900/9901）。

### IntelliJ 数据库与 Redis 连接

连接前确认服务已启动：
```bash
cd server-java
docker compose -f docker-compose.macos.yml ps
```

查看当前连接字段：
```bash
grep -E '^(DB_NAME|DB_USER|DB_PASSWORD|REDIS_PASSWORD|MYSQL_HOST_PORT|REDIS_HOST_PORT)=' .env
```

MySQL 数据源配置：
| IntelliJ 字段 | 填写值 |
|---|---|
| Data Source | MySQL |
| Host | `<本机IP>` |
| Port | `.env` 的 `MYSQL_HOST_PORT`，默认 `9902` |
| Database | `.env` 的 `DB_NAME`，默认 `	` |
| User | `.env` 的 `DB_USER`，默认 `pophie_user` |
| Password | `.env` 的 `DB_PASSWORD` |
| URL | `jdbc:mysql://<本机IP>:9902/pophie?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true` |

Redis 数据源配置：
| IntelliJ 字段 | 填写值 |
|---|---|
| Data Source | Redis |
| Host | `<本机IP>` |
| Port | `.env` 的 `REDIS_HOST_PORT`，默认 `9903` |
| Password | `.env` 的 `REDIS_PASSWORD` |
| Database | `0` |

如果 IntelliJ 没有 Redis 数据源，需要先安装 Redis 相关数据库插件，或使用 JetBrains DataGrip/Database 工具窗口支持的 Redis 连接能力。

如果拉取 `mysql:8.0` / `redis:7-alpine` 时报 `auth.docker.io` 证书不匹配，例如证书域名变成其他站点，优先检查本机 VPN/代理/DNS/公司网关；这通常不是项目配置问题。可用：
```bash
scutil --proxy
dscacheutil -q host -a name auth.docker.io
curl -Iv https://auth.docker.io/token
```
确认当前代理与解析结果。

---

## 二、Windows 本机部署（数据落 E 盘，不占 C 盘）

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

## 三、Linux 线上一键部署

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
| `docker-compose.macos.yml` | macOS Docker Desktop 编排（数据落 `${DATA_ROOT}` bind mount） |
| `docker-compose.windows.yml` | Windows 编排（数据落 `${DATA_ROOT}` E 盘 bind mount） |
| `deploy/macos-deploy.sh` | macOS 一键部署（Docker Desktop） |
| `deploy/setup.sh` | Linux 一键：装 Docker + 配置 + 部署 |
| `deploy/deploy.sh` | Linux 升级发布 |
| `deploy/install-docker.sh` | Linux 装 Docker Engine（国内加速） |
| `deploy/windows-deploy.ps1` | Windows 一键部署（PowerShell） |

> 两份部署用同一镜像与同一套 `.env` / `config.yaml` 约定，差异仅在数据卷落盘方式。
