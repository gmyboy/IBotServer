# Pophie Server (Java / Spring Boot)

桌面陪伴机器人后端的 **Java 重写版**，从原 `backend/`（Python / FastAPI）迁移而来，采用
`service-scoffolding` 脚手架架构：Spring Boot 3.2 + MySQL(JPA) + Redis(Lettuce) + sa-token，
**完整保留原有全部业务逻辑**（对话 / 流式对话 / L1–L4 漏斗记忆 / OpenAI 兼容 LLM /
DashScope STT-TTS / 定时提醒 / 主动感知 / 主人档案 / Admin 后台 / 配置热更新）。

> 对外 HTTP 契约与原 FastAPI 保持一致：业务接口直接返回原始 JSON（不套统一包装），
> 错误体为 `{"detail": "..."}` + 对应状态码。前端 `index.html`/`admin.html`、Android 客户端基本零改动。
> 唯一变化：Admin 鉴权由 `X-Admin-Token` 头改为 sa-token 登录（先 `POST /api/admin/login`）。

## 架构分层
- `base/ configuration/ exception/ filter/ redis/ utils/ annotation/`：脚手架基础设施（包名 `com.pophie`）
- `entity/ repository/`：JPA 实体与数据访问（表名 `pb_{模块}_{表名}`，见 `com.pophie.db.DbTables`）
- `config/`：`RuntimeConfigService` —— 移植 `config.py`，读写 `config.yaml`，支持 admin 热更新
- `schema/`：DTO、枚举、表情/状态/手势映射、`REPLY_JSON_INSTRUCTION`（逐字对应 `schemas.py`）
- `service/`：`LlmService`/`MemoryService`/`ReminderService`/`ProactiveService`/`SpeechService`/`ChatService`/`RobotService`
- `controller/`：`ApiController`（业务，公开）、`AdminController`（`/api/admin/**`，sa-token）、`WebController`（静态前端）

## 本地运行
1. 准备 MySQL 8 与 Redis 7（可用根目录 `docker-compose.yml` 仅起这两个依赖）。
2. 复制配置：`cp config.example.yaml config.yaml`，按需填入 `llm.api_key` / `speech.tts.api_key`（DashScope）/ `server.admin_token`，
   或用环境变量 `LLM_API_KEY` / `DASHSCOPE_API_KEY` / `ADMIN_TOKEN` 覆盖。
3. 配置数据源：编辑 `src/main/resources/application.properties` 的 `spring.datasource.*`、`spring.data.redis.*`。
4. 启动（需 JDK 17+）：
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   开发档 `dev` 使用 `ddl-auto=update` 自动建表。
5. 验证：`GET http://localhost:8000/api/health` 返回 `{"ok":true,...}`。

## 部署

线上为 **Java 后端（9900）+ 管理后台（9901）** 两个独立服务，各有一键发布脚本，互不影响。

### 首次部署（空服务器）

把整个 `server-java` 目录传到服务器，然后：

```bash
sudo bash deploy/setup.sh          # 自动探测国内环境
sudo bash deploy/setup.sh --cn     # 强制走阿里云源 + Docker 镜像加速
```

脚本会：装 Docker → 生成 `.env`（随机 DB/Redis 密码 + AES_KEY）→ 生成 `config.yaml` →
构建并启动 `app/mysql/redis` 三容器。业务密钥（LLM/DashScope/ADMIN_TOKEN）按提示写入 `.env`。
详见 `deploy/README.md`。

> 管理后台（vben-admin）的首次部署见下方「管理后台」一节。

### 日常发布：Java 后端（端口 9900）

代码有更新后，在本仓库根目录一条命令完成「上传源码 → 构建 → 重启」：

```bash
bash deploy/deploy-server-java.sh              # 上传 + 构建 + 重启 app（默认）
bash deploy/deploy-server-java.sh --no-build   # 跳过构建，直接用已有镜像 up
bash deploy/deploy-server-java.sh --no-restart # 仅同步源码，不构建不重启
bash deploy/deploy-server-java.sh --logs       # 部署完直接 follow 日志（Ctrl+C 退出不影响服务）
```

脚本流程：校验依赖 → `tar over ssh` 上传源码 → 远端 `docker compose build app`
（多阶段：maven 打 jar → JRE 运行镜像）→ `up -d --no-deps app` 仅重建 app → 健康检查。

**安全保证（已实测）：**
- 绝不上传 `.env` / `config.yaml`（含密钥），tar 排除，部署前后 MD5 一致；
- `--no-deps` 只重建 app，**mysql/redis 不重启、数据卷不丢**；
- 健康检查：`/api/health` + MySQL `ping` + 容器健康状态，单 SSH 连接轮询。

可用环境变量覆盖（带默认值）：
```bash
REMOTE_HOST=223.109.143.135 REMOTE_USER=root \
REMOTE_DIR=/opt/IBotServer-java APP_PORT=9900 \
bash deploy/deploy-server-java.sh
```

发布后：
- 后端 API：`http://<服务器IP>:9900/api/health`
- Actuator：`http://<服务器IP>:9900/actuator/health`
- 日志：`docker compose logs -f app`
- 重启：`docker compose restart app`
- 停止（数据卷保留）：`docker compose down`

### 管理后台 vben-admin（端口 9901）

vben-admin 是 Vue3 管理前端，构建产物由独立 nginx 容器托管，**通过外部桥接网络
`ibotserver-java_pophie-net` 反代到已运行的 `pophie-app:8000`**，与 Java 后端解耦、可独立 up/down。

```bash
bash vben-admin/deploy/deploy-admin.sh              # 用本地已有 dist 发布（默认）
bash vben-admin/deploy/deploy-admin.sh --build      # 先 pnpm build:antd 再发布
bash vben-admin/deploy/deploy-admin.sh --no-restart # 仅更新静态文件，不起/重建容器
```

脚本流程：检查依赖 → 注入运行时 API 地址（patch `_app-config-*.js` → `/api`，免重新构建即可改后端地址）
→ Python 打包 dist（强制正斜杠，规避 Windows zip 问题）→ 上传 → 远端解压 + CRLF→LF
→ `docker compose up -d --force-recreate` → 健康检查。

可用环境变量覆盖：
```bash
REMOTE_HOST=223.109.143.135 REMOTE_USER=root \
REMOTE_DIR=/opt/pophie-admin ADMIN_PORT=9901 API_URL=/api \
bash vben-admin/deploy/deploy-admin.sh
```

发布后：
- 管理后台：`http://<服务器IP>:9901/`
- 健康检查：`http://<服务器IP>:9901/api/health`（经 nginx 反代到后端）

### 脚本文件对照

| 脚本 | 位置 | 发布对象 | 端口 |
|---|---|---|---|
| `deploy/setup.sh` | `server-java/deploy/` | 首次部署（装 Docker + 配置 + 起服务） | 9900 |
| `deploy-server-java.sh` | `server-java/deploy/` | Java 后端日常发布 | 9900 |
| `deploy-admin.sh` | `server-java/vben-admin/deploy/` | 管理后台发布 | 9901 |

## 关键说明
- **记忆 L1**：进程内缓冲（每 robot 8 帧），不入库，重启清空（与原实现一致）。
- **提醒调度**：`@Scheduled(fixedDelay=10s)` 扫描到期 pending，与原 10 秒轮询一致。
- **时间格式**：`created_at`/`updated_at` 用 `yyyy-MM-dd HH:mm:ss`（对应 SQLite CURRENT_TIMESTAMP）；
  `remind_at`/`fired_at`/`last_seen_at` 用 ISO8601（对应 Python `_now_iso()`）。
- **语音 DashScope**：TTS 用 `dashscope-sdk-java` ttsv2 `SpeechSynthesizer`；STT 用 `OmniRealtimeConversation`（`wss://.../api-ws/v1/realtime`，SDK ≥ 2.22.5）。
  STT 实时模型/情感字段在不同 SDK 版本可能有差异 —— 已确认编译通过，**首次接入真实 DashScope key 时需联调验证**。

## 测试
```bash
mvn test -Dtest=AlgorithmParityTest
```
覆盖流式分句、内心独白检测、JSON 容错解析、表情别名、共情对齐等纯算法（与 Python 同输入对拍）。
