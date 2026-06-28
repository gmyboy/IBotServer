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
- `entity/ repository/`：6 张表 JPA 映射与数据访问（memories/conversations/reminders/proactive_log/robots/owner_profiles）
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

## Docker 部署
```bash
cp .env.example .env   # 填写 DB/Redis 密码、AES_KEY、业务密钥
cp config.example.yaml config.yaml
docker compose up -d --build
```
应用 8000 端口对外；MySQL/Redis 仅在内网。`config.yaml` 以卷挂载，admin 热更新可持久化。

## 关键说明
- **记忆 L1**：进程内缓冲（每 robot 8 帧），不入库，重启清空（与原实现一致）。
- **提醒调度**：`@Scheduled(fixedDelay=10s)` 扫描到期 pending，与原 10 秒轮询一致。
- **时间格式**：`created_at`/`updated_at` 用 `yyyy-MM-dd HH:mm:ss`（对应 SQLite CURRENT_TIMESTAMP）；
  `remind_at`/`fired_at`/`last_seen_at` 用 ISO8601（对应 Python `_now_iso()`）。
- **语音 DashScope**：TTS 用 `dashscope-sdk-java` ttsv2 `SpeechSynthesizer`；STT 用 `Recognition` 流式接口。
  STT 实时模型/情感字段在不同 SDK 版本可能有差异 —— 已确认编译通过，**首次接入真实 DashScope key 时需联调验证**。

## 测试
```bash
mvn test -Dtest=AlgorithmParityTest
```
覆盖流式分句、内心独白检测、JSON 容错解析、表情别名、共情对齐等纯算法（与 Python 同输入对拍）。
