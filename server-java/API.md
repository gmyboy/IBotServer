# Pophie Server API 接口文档

> **版本**: 1.0.0 | **Base URL**: `http://localhost:8000` | **协议**: HTTP/1.1 REST + WebSocket

---

## 目录

- [1. 概述](#1-概述)
- [2. 通用约定](#2-通用约定)
- [3. WebSocket 接口](#3-websocket-接口)
- [4. 业务 API（`/api`）](#4-业务-apiapi)
  - [4.1 健康检查 & Schema](#41-健康检查--schema)
  - [4.2 设备绑定](#42-设备绑定)
  - [4.3 用户档案](#43-用户档案)
  - [4.4 机器人配置](#44-机器人配置)
  - [4.5 会话管理](#45-会话管理)
  - [4.6 主人档案](#46-主人档案)
  - [4.7 语音服务（STT / TTS）](#47-语音服务stt--tts)
  - [4.8 语音段流水](#48-语音段流水)
  - [4.9 对话（Chat）](#49-对话chat)
  - [4.10 主动感知（Tick）](#410-主动感知tick)
  - [4.11 回复通知测试](#411-回复通知测试)
  - [4.12 记忆（Memories）](#412-记忆memories)
  - [4.13 对话历史（Conversations）](#413-对话历史conversations)
  - [4.14 主动日志 & 消息](#414-主动日志--消息)
  - [4.15 提醒（Reminders）](#415-提醒reminders)
  - [4.16 L1 感知帧](#416-l1-感知帧)
- [5. 管理后台 API（`/api/admin`）](#5-管理后台-apiapiadmin)
  - [5.1 登录](#51-登录)
  - [5.2 机器人管理](#52-机器人管理)
  - [5.3 用户管理](#53-用户管理)
  - [5.4 记忆管理](#54-记忆管理)
  - [5.5 配置管理](#55-配置管理)
  - [5.6 服务管理](#56-服务管理)
  - [5.7 数据清除](#57-数据清除)
- [6. 数据模型参考](#6-数据模型参考)
  - [6.1 通用结构体](#61-通用结构体)
  - [6.2 枚举值](#62-枚举值)

---

## 1. 概述

Pophie Server 是桌面陪伴机器人的后端服务，提供对话、语音合成/识别、记忆管理、主动感知、设备绑定、管理后台等能力。

- **技术栈**: Spring Boot 3.2 + MySQL (JPA) + Redis + sa-token
- **默认端口**: `8000`
- **业务接口**: 全部公开，无需认证
- **管理接口**: 需 sa-token 登录认证（`/api/admin/login` 除外）
- **语音引擎**: 阿里云 DashScope（STT 实时流 + TTS v2）
- **LLM**: OpenAI 兼容接口（默认 DeepSeek）

---

## 2. 通用约定

### 请求格式

- `Content-Type: application/json`（除非特别说明）
- 字段命名风格：**snake_case**（Jackson 自动转换，Java 端为 camelCase）
- 流式接口使用 `application/x-ndjson`（每行一个 JSON 对象，以 `\n` 分隔）

### 响应格式

业务接口直接返回原始 JSON，**不套统一包装**。错误响应格式：

```json
{
  "detail": "错误描述信息"
}
```

对应的 HTTP 状态码：
| 状态码 | 含义 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证（Admin 接口） |
| 404 | 资源不存在 |
| 503 | 语音功能未启用 |

### Admin 鉴权

管理后台接口（`/api/admin/**`）需要先调用 `POST /api/admin/login` 获取 sa-token，后续请求自动携带 Cookie/Header 中的 token。

---

## 3. WebSocket 接口

### 3.1 实时语音识别（STT 流）

```
ws://localhost:8000/api/stt/stream
```

客户端推送 PCM 音频，服务端流式返回识别结果。

**客户端 → 服务端**（JSON 文本帧）：

| 消息类型 | 字段 | 说明 |
|----------|------|------|
| `start` | `{"type":"start","sample_rate":16000}` | 开始识别，声明采样率 |
| `audio` | `{"type":"audio","data":"<base64>"}` | 音频数据块（base64 编码 PCM） |
| `commit` | `{"type":"commit"}` | 提交当前音频段，触发 final 结果 |
| `close` | `{"type":"close"}` | 关闭连接 |

**服务端 → 客户端**：

| 消息类型 | 字段 | 说明 |
|----------|------|------|
| `ready` | `{"type":"ready"}` | 识别引擎就绪 |
| `partial` | `{"type":"partial","text":"..."}` | 中间识别结果 |
| `final` | `{"type":"final","text":"...","voice":{"tone":"...","intonation":"...","speed":"..."}}` | 最终识别结果 + 语音情感 |
| `error` | `{"type":"error","message":"..."}` | 错误信息 |

---

### 3.2 回复通知推送

```
ws://localhost:8000/api/reply/notify
```

服务端主动向客户端推送机器人回复通知（含 TTS 音频）。客户端建立连接后持续接收。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robot_id` | string | 是 | 机器人 ID |
| `user_id` | string | 是 | 用户 ID |
| `session_id` | string | 是 | 会话 ID |

**服务端 → 客户端（JSON 文本帧）**：

| 消息类型 | 字段 | 说明 |
|----------|------|------|
| `connected` | `{"type":"connected","robot_id":"...","user_id":"...","session_id":"..."}` | 连接成功 |
| `reply_meta` | `{"type":"reply_meta","reply_id":"...","text":"...","format":"wav","sample_rate":16000}` | 回复元信息（含 TTS 音频参数） |
| `reply_chunk` | `{"type":"reply_chunk","reply_id":"...","data":"<base64>","index":0}` | TTS 音频数据块 |
| `reply_done` | `{"type":"reply_done","reply_id":"..."}` | 回复推送完毕 |
| `error` | `{"type":"error","message":"..."}` | 错误信息 |

---

## 4. 业务 API（`/api`）

### 4.1 健康检查 & Schema

#### `GET /api/health`

服务健康检查。

**响应示例**：

```json
{
  "ok": true,
  "speech_enabled": true,
  "stt_engine": "dashscope_realtime",
  "tts_engine": "dashscope_ttsv2"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `ok` | boolean | 服务是否正常 |
| `speech_enabled` | boolean | 语音功能是否启用 |
| `stt_engine` | string\|null | STT 引擎名称 |
| `tts_engine` | string\|null | TTS 引擎名称 |

---

#### `GET /api/schema`

获取运行时 Schema 元数据（表情、语音、手势、状态等枚举值），供客户端/前端使用。

**响应字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `facial_expressions` | array | 面部表情列表 `[{key, label}]` |
| `facial_expression_aliases` | object | 表情别名映射（如 `{"开心":"happy"}`） |
| `voice_prosody` | object | 语音副通道可选值：`tone` / `intonation` / `speed` 各自的合法值列表 |
| `gestures` | array | 手势列表 `[{key, label}]` |
| `robot_states` | array | 机器人 FSM 状态列表 `[{key, label}]` |
| `perception_fields` | object | 感知字段说明 |
| `fsm_state_mapping` | object | 表情 → FSM 状态的映射规则 |
| `tts_voices` | array | 可用 TTS 音色列表 |
| `reserved_fields` | array | 保留字段（暂不进入 LLM） |

---

### 4.2 设备绑定

#### `POST /api/device/bind`

绑定（或注册）一个端侧设备。若 `user_id` 已存在则绑定到已有用户，否则新建用户。

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `device_id` | string | 是 | 端侧设备唯一标识 |
| `user_id` | string | 否 | 绑定到已有用户；省略则自动创建 |
| `robot_id` | string | 否 | 绑定到已有机器人；省略则自动分配 |
| `device_name` | string | 否 | 设备名称 |
| `display_name` | string | 否 | 显示名称 |

**响应**：

```json
{
  "device_id": "dev-001",
  "user_id": "usr-abc123",
  "robot_id": "rbt-xyz789",
  "new_user": false,
  "new_device": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `device_id` | string | 设备 ID |
| `user_id` | string | 关联的用户 ID |
| `robot_id` | string | 关联的机器人 ID |
| `new_user` | boolean | 是否新创建的用户 |
| `new_device` | boolean | 是否新绑定的设备 |

---

#### `GET /api/device/bind`

查询已有设备绑定信息。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 是 | 设备唯一标识 |

**响应**：同上 `DeviceBindResponse`。未绑定时返回 404。

---

### 4.3 用户档案

#### `GET /api/users/{userId}/profile`

获取用户档案。

**路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | string | 用户 ID |

**响应**：

```json
{
  "user_id": "usr-abc123",
  "display_name": "小明",
  "nickname": "小明",
  "gender": "male",
  "birthday": "1995-06-15",
  "avatar_url": "https://...",
  "voice_enrolled": true,
  "created_at": "2025-01-01 12:00:00",
  "updated_at": "2025-06-15 08:30:00"
}
```

---

#### `PUT /api/users/{userId}/profile`

更新用户档案（部分更新，只传需要修改的字段）。

**请求体**（所有字段可选）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `nickname` | string | 昵称 |
| `gender` | string | 性别 |
| `birthday` | string | 生日 |
| `avatar_url` | string | 头像 URL |
| `voice_data` | string | 声纹数据（base64） |
| `voice_data_format` | string | 声纹数据格式 |
| `voice_data_sample_rate` | integer | 声纹采样率 |
| `voice_enrolled` | boolean | 是否已录入声纹 |

**响应**：同 `GET` 返回完整 `UserProfileResponse`。

---

#### `GET /api/users/{userId}/voice`

获取用户声纹数据。

**响应**：

```json
{
  "voice_data": "<base64>",
  "voice_data_format": "wav",
  "voice_data_sample_rate": 16000,
  "voice_enrolled": true
}
```

---

#### `DELETE /api/users/{userId}/voice`

删除用户声纹数据。

**响应**：

```json
{
  "ok": true,
  "user_id": "usr-abc123"
}
```

---

#### `GET /api/users/{userId}/robots`

获取与用户绑定的所有机器人列表。

**响应**：

```json
[
  {
    "robot_id": "rbt-001",
    "display_name": "小泡芙",
    "last_seen_at": "2025-07-01T10:30:00"
  }
]
```

---

### 4.4 机器人配置

#### `GET /api/robots/{robotId}/config`

获取机器人完整配置。

**响应**：

```json
{
  "robot_id": "rbt-xyz789",
  "display_name": "小泡芙",
  "persona": "你是一只可爱的桌面宠物...",
  "voice_id": "zhixiaobai",
  "voice_style": "chat",
  "greeting": "你好呀，我是小泡芙~",
  "avatar_url": "https://...",
  "language": "zh",
  "personality_tags": "可爱,温柔,活泼",
  "system_prompt": "你是主人的桌面宠物...",
  "created_at": "2025-01-01 12:00:00",
  "last_seen_at": "2025-07-01T10:30:00",
  "updated_at": "2025-06-15 08:30:00"
}
```

---

#### `PUT /api/robots/{robotId}/config`

更新机器人配置（部分更新）。

**请求体**（所有字段可选）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `display_name` | string | 显示名称 |
| `persona` | string | 机器人人设描述 |
| `voice_id` | string | TTS 音色 ID |
| `voice_style` | string | TTS 风格 |
| `greeting` | string | 欢迎语 |
| `avatar_url` | string | 头像 URL |
| `language` | string | 语言（如 `zh`, `en`） |
| `personality_tags` | string | 性格标签（逗号分隔） |
| `system_prompt` | string | 自定义系统提示词 |

**响应**：同 `GET` 返回完整 `RobotConfigResponse`。

---

### 4.5 会话管理

#### `POST /api/session/new`

创建新的聊天会话。同时支持设备绑定（若提供 `deviceId`）。

**查询参数**（均为可选）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `robotId` | string | 机器人 ID；省略则自动分配 |
| `userId` | string | 用户 ID；省略则使用默认用户 |
| `deviceId` | string | 设备 ID；若提供则触发设备绑定流程 |

**响应**：

```json
{
  "robot_id": "rbt-xyz789",
  "user_id": "usr-abc123",
  "session_id": "sess-20250701-abc123",
  "device_id": "dev-001",
  "new_user": false,
  "new_device": false
}
```

> 若未传 `deviceId`，响应中不包含 `device_id` / `new_user` / `new_device` 字段。

---

### 4.6 主人档案

主人档案是机器人的"拥有者"信息，用于个性化对话（如称呼、生日提醒等）。

#### `GET /api/robots/{robotId}/owner`

获取机器人主人档案。未设置时返回 404。

**响应**：

```json
{
  "nickname": "小明",
  "robot_name": "小泡芙",
  "gender": "male",
  "birthday": "1995-06-15",
  "face_registered": true
}
```

---

#### `PUT /api/robots/{robotId}/owner`

设置/更新主人档案。

**请求体**：

```json
{
  "owner": {
    "nickname": "小明",
    "robot_name": "小泡芙",
    "gender": "male",
    "birthday": "1995-06-15",
    "face_registered": true
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `owner.nickname` | string | 是 | 主人昵称（trim 后非空） |
| `owner.robot_name` | string | 是 | 机器人名称（trim 后非空） |
| `owner.gender` | string | 否 | 性别：`male` / `female` / `other` |
| `owner.birthday` | string | 否 | 生日，格式 `YYYY-MM-DD` |
| `owner.face_registered` | boolean | 否 | 是否已注册人脸，默认 `false` |

**校验规则**：
- `gender` 非空时必须为 `male`、`female` 或 `other`（大小写不敏感）
- `birthday` 非空时必须匹配 `YYYY-MM-DD` 格式

**响应**：

```json
{
  "ok": true,
  "robot_id": "rbt-xyz789",
  "owner": {
    "nickname": "小明",
    "robot_name": "小泡芙",
    "gender": "male",
    "birthday": "1995-06-15",
    "face_registered": true
  }
}
```

---

#### `DELETE /api/robots/{robotId}/owner`

删除主人档案。

**响应**：

```json
{
  "ok": true,
  "robot_id": "rbt-xyz789"
}
```

---

### 4.7 语音服务（STT / TTS）

> 语音功能需在 `config.yaml` 中启用并配置 DashScope API Key。未启用时所有语音接口返回 503。

#### `POST /api/stt`

单次语音识别（非流式）。将完整音频发送后一次性返回结果。

**请求体**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `audio` | AudioPayload | 音频载荷 |

**AudioPayload** 结构：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `format` | string | `"wav"` | 音频格式 |
| `encoding` | string | `"base64"` | 编码方式 |
| `sample_rate` | integer | `16000` | 采样率 |
| `data` | string | `""` | base64 编码的音频数据 |

**响应**（SttResult）：

```json
{
  "text": "你好小泡芙",
  "voice": {
    "tone": "温柔",
    "intonation": "平稳",
    "speed": "正常"
  }
}
```

---

#### `POST /api/tts`

单次文本转语音（非流式）。

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `text` | string | 是 | 待合成文本 |
| `voice` | VoiceProsody | 否 | 语音情感参数（tone / intonation / speed） |
| `voice_id` | string | 否 | 指定音色 ID；省略使用机器人默认音色 |

**响应**（TtsResponse）：

```json
{
  "text": "你好呀，我是小泡芙~",
  "voice": { "tone": "温柔", "intonation": "平稳", "speed": "正常" },
  "audio": {
    "format": "wav",
    "encoding": "base64",
    "sample_rate": 16000,
    "data": "<base64-encoded-audio>"
  }
}
```

---

#### `POST /api/tts/stream`

流式文本转语音。响应为 `application/x-ndjson`，每行一个 JSON 对象。

**请求体**：同 `POST /api/tts`。

**响应流（NDJSON）**：

```
{"type":"meta","format":"wav","sample_rate":16000,"encoding":"base64"}
{"type":"chunk","data":"<base64-chunk-1>"}
{"type":"chunk","data":"<base64-chunk-2>"}
{"type":"done","first_packet_ms": 345}
```

| 消息类型 | 字段 | 说明 |
|----------|------|------|
| `meta` | `format`, `sample_rate`, `encoding` | 音频流元信息 |
| `chunk` | `data` | base64 编码的音频数据块 |
| `done` | `first_packet_ms` | 首包延迟（毫秒） |
| `error` | `message` | 合成失败时的错误信息 |

---

### 4.8 语音段流水

记录端侧语音段流水（含声纹标记、STT 文本、音频等），用于对话上下文构造和声纹分析。

#### `POST /api/voice/segments`

上传一个语音段流水记录。

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robot_id` | string | 是 | 机器人 ID |
| `user_id` | string | 是 | 用户 ID |
| `session_id` | string | 是 | 会话 ID |
| `client_segment_id` | string | 否 | 客户端段 ID（去重） |
| `duration_ms` | long | 是 | 语音段时长（毫秒） |
| `sample_rate` | integer | 是 | 采样率 |
| `audio` | AudioPayload | 否 | 音频数据 |
| `speaker` | SpeakerSnapshot | 否 | 声纹判定快照 |
| `stt_text` | string | 否 | 客户端 STT 识别文本 |
| `stt_source` | string | 否 | STT 来源：`none` / `realtime` / `manual`，默认 `none` |
| `embedding` | float[] | 否 | 声纹嵌入向量 |
| `metadata` | object | 否 | 附加元数据 |
| `server_stt_if_empty` | boolean | 否 | stt_text 为空时是否服务端补做 STT，默认 `true` |

**SpeakerSnapshot** 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 说话人名称 |
| `state` | string | 说话状态 |
| `is_owner` | boolean | 是否为主人 |
| `confidence` | float | 置信度 |
| `margin` | float | 与第二名的差距 |
| `runner_up` | string | 第二名说话人 |
| `raw_score` | float | 原始分数 |

**响应**（VoiceSegmentUploadResponse）：

```json
{
  "id": 12345,
  "session_id": "sess-abc",
  "stt_text": "你好小泡芙",
  "stt_source": "server",
  "created_at": "2025-07-01 12:00:00"
}
```

---

#### `GET /api/voice/segments`

查询语音段流水列表。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 按机器人筛选 |
| `userId` | string | 否 | 按用户筛选 |
| `sessionId` | string | 否 | 按会话筛选 |
| `isOwner` | boolean | 否 | 是否仅筛选主人语音 |
| `limit` | integer | 否 | 返回条数，默认 50 |

**响应**：

```json
{
  "items": [ ... ]
}
```

---

#### `GET /api/voice/segments/{id}`

获取单条语音段详情。

**查询参数**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `includeAudio` | boolean | `false` | 是否包含音频数据（base64） |

---

#### `PATCH /api/voice/segments/{id}/stt`

补写语音段的 STT 文本（当 final 结果晚于段入库时使用）。

**请求体**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `stt_text` | string | STT 识别文本 |
| `stt_source` | string | STT 来源，默认 `realtime` |

**响应**：同 `VoiceSegmentUploadResponse`。

---

### 4.9 对话（Chat）

#### `POST /api/chat`

非流式对话。发送用户输入，获取完整的机器人回复。

**请求体**（ChatRequest）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robot_id` | string | 否 | 机器人 ID |
| `user_id` | string | 否 | 用户 ID |
| `device_id` | string | 否 | 设备 ID（若已绑定则自动解析 user/robot） |
| `session_id` | string | 否 | 会话 ID |
| `input` | ChatInput | 是 | 聊天输入 |

**ChatInput** 结构：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `text` | string | `""` | 文本输入 |
| `audio` | AudioPayload | — | 音频输入（用于语音对话） |
| `perception` | PerceptionInput | — | 感知输入（表情/语气/触摸等） |
| `voice_id` | string | — | 指定 TTS 音色 |
| `skip_tts` | boolean | — | 跳过 TTS 合成（`null`/`true`/`false` 三态） |
| `server_tts` | boolean | — | 开启服务端 TTS（`true` 时回复含 tts_meta/tts_chunk 事件） |

**PerceptionInput** 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| `facial_expression` | string | 用户面部表情（7 类或中文别名） |
| `voice` | VoiceProsody | 语音副通道 |
| `touch` | string | 触摸类型（如 `摸头`、`拥抱`） |
| `identity` | string\|object | 端侧身份识别到的人名 |
| `gesture` | GestureAction | 手势识别结果 |
| `posture` | PostureAction | 体姿态（预留，暂不进入 LLM） |

**响应**（ChatResponse）：

```json
{
  "robot_id": "rbt-xyz789",
  "user_id": "usr-abc123",
  "session_id": "sess-abc",
  "output": {
    "text": "你好呀，小明~",
    "facial_expression": "happy",
    "facial_expression_label": "开心",
    "robot_state": "happy",
    "robot_state_label": "开心",
    "voice": { "tone": "温柔", "intonation": "平稳", "speed": "正常" },
    "audio": { ... },
    "gesture": null,
    "posture": null
  },
  "stt": { "text": "你好小泡芙", "voice": { ... } },
  "memory_flow": [ ... ],
  "l1_frames": [ ... ],
  "recalled": [ ... ],
  "scheduled_reminders": [ ... ]
}
```

---

#### `POST /api/chat/stream`

流式对话。响应为 `application/x-ndjson`，逐行推送对话事件。

**请求体**：同 `POST /api/chat`。

**响应流（NDJSON）**：

```
{"type":"stt","text":"你好","voice":{...}}
{"type":"thinking","subtype":"memory_recall","data":{...}}
{"type":"chunk","text":"你"}
{"type":"chunk","text":"好"}
{"type":"chunk","text":"呀"}
{"type":"tts_meta","format":"wav","sample_rate":16000}
{"type":"tts_chunk","data":"<base64>"}
{"type":"done","output":{...}}
{"type":"error","message":"..."}
```

| 事件类型 | 说明 |
|----------|------|
| `stt` | STT 识别结果 |
| `thinking` | 思考过程（记忆召回、判断等） |
| `chunk` | LLM 生成的文本片段 |
| `tts_meta` | 服务端 TTS 音频元信息（需 `server_tts: true`） |
| `tts_chunk` | 服务端 TTS 音频数据块（需 `server_tts: true`） |
| `done` | 对话完成，含完整 `output` |
| `error` | 错误信息 |

---

### 4.10 主动感知（Tick）

#### `POST /api/tick`

端侧周期性上报感知信号，触发机器人的主动行为决策（如主动打招呼、提醒等）。

**请求体**（TickRequest）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robot_id` | string | 否 | 机器人 ID |
| `user_id` | string | 否 | 用户 ID |
| `session_id` | string | 否 | 会话 ID |
| `signal` | object | 是 | 感知信号（自由格式 Map） |

**响应**：

```json
{
  "decision": "speak",
  "content": "小明，该休息一下了哦~",
  "output": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `decision` | string | 决策类型：`speak` / `silent` 等 |
| `content` | string | 决策内容（发言文本等） |
| `output` | RobotOutput | 当 decision=`speak` 时，包含完整的机器人输出（含 TTS） |

---

### 4.11 回复通知测试

#### `POST /api/reply/test`

开发/联调用：向已连接 WebSocket `/api/reply/notify` 的客户端推送一条测试回复（含 TTS）。

**请求体**（所有字段可选）：

```json
{
  "robot_id": "rbt-001",
  "user_id": "usr-001",
  "session_id": "sess-001",
  "text": "这是一条测试回复"
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `robot_id` | string | 自动解析 | 机器人 ID |
| `user_id` | string | 自动解析 | 用户 ID |
| `session_id` | string | 自动创建 | 会话 ID |
| `text` | string | `"这是一条测试回复，请确认你能听到。"` | 推送的文本 |

**响应**：

```json
{
  "ok": true,
  "robot_id": "rbt-001",
  "user_id": "usr-001",
  "session_id": "sess-001",
  "text": "这是一条测试回复"
}
```

> 该方法会阻塞等待 WebSocket 客户端确认（超时 120 秒）。

---

### 4.12 记忆（Memories）

机器人的分层记忆系统（L1–L4 漏斗模型）。

#### `GET /api/memories`

查询记忆列表。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 机器人 ID（暂用于解析） |
| `userId` | string | 否 | 用户 ID |
| `deviceId` | string | 否 | 设备 ID（自动解析 userId） |
| `layer` | string | 否 | 记忆层：`L1` / `L2` / `L3` / `L4` |
| `sessionId` | string | 否 | 按会话筛选 |

**响应**：

```json
{
  "user_id": "usr-abc123",
  "items": [
    {
      "id": 101,
      "robot_id": "rbt-001",
      "user_id": "usr-abc123",
      "layer": "L3",
      "content": "用户喜欢喝咖啡",
      "summary": null,
      "emotion_score": 0.8,
      "importance": 0.7,
      "repetition_count": 3,
      "tags": ["偏好", "饮食"],
      "created_at": "2025-06-01 10:00:00",
      "updated_at": "2025-06-15 14:00:00"
    }
  ]
}
```

---

#### `GET /api/memories/{memId}`

获取单条记忆详情。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 用于校验归属 |

---

### 4.13 对话历史（Conversations）

#### `GET /api/conversations`

查询对话历史记录。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 机器人 ID |
| `userId` | string | 否 | 用户 ID |
| `deviceId` | string | 否 | 设备 ID（自动解析 userId） |
| `sessionId` | string | 否 | 按会话筛选 |
| `limit` | integer | 否 | 返回条数，默认 100 |

**响应**：

```json
{
  "items": [
    {
      "id": 5001,
      "robot_id": "rbt-001",
      "user_id": "usr-abc123",
      "session_id": "sess-abc",
      "role": "user",
      "content": "你好小泡芙",
      "modality": "text",
      "metadata": "{}",
      "created_at": "2025-07-01 12:00:00"
    }
  ]
}
```

> 结果按时间倒序（最新在前）。

---

### 4.14 主动日志 & 消息

#### `GET /api/proactive_log`

查询主动感知决策日志。

**查询参数**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `robotId` | string | 否 | 机器人 ID |
| `limit` | integer | `50` | 返回条数 |

**响应**：

```json
{
  "items": [
    {
      "id": 2001,
      "robot_id": "rbt-001",
      "user_id": "usr-abc123",
      "trigger": "idle_timeout",
      "decision": "speak",
      "content": "小明，你还在吗？",
      "related_memory_ids": [101, 102],
      "created_at": "2025-07-01 14:00:00"
    }
  ]
}
```

---

#### `GET /api/proactive_messages`

轮询主动推送的消息（用于不支持 WebSocket 的客户端）。

**查询参数**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `robotId` | string | 否 | 机器人 ID |
| `sessionId` | string | 否 | 会话 ID |
| `sinceId` | long | `0` | 起始消息 ID（增量拉取） |
| `limit` | integer | `50` | 返回条数 |

**响应**：

```json
{
  "items": [
    {
      "id": 6001,
      "session_id": "sess-abc",
      "content": "该休息了哦~",
      "metadata": { "type": "proactive_reminder" },
      "created_at": "2025-07-01 15:00:00"
    }
  ],
  "last_id": 6001
}
```

> 用 `last_id` 作为下一次请求的 `sinceId` 实现增量轮询。

---

### 4.15 提醒（Reminders）

#### `GET /api/reminders`

查询提醒列表。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 机器人 ID |
| `status` | string | 否 | 按状态筛选：`pending` / `fired` / `cancelled` |

**响应**：

```json
{
  "items": [
    {
      "id": 3001,
      "robot_id": "rbt-001",
      "user_id": "usr-abc123",
      "session_id": "sess-abc",
      "remind_at": "2025-07-01T15:30:00",
      "content": "该喝下午茶了",
      "note": "每天下午提醒",
      "status": "pending",
      "created_at": "2025-07-01 10:00:00"
    }
  ]
}
```

---

#### `DELETE /api/reminders/{reminderId}`

取消一个待触发提醒（仅 `pending` 状态可取消）。

**响应**：

```json
{
  "ok": true,
  "id": 3001
}
```

---

### 4.16 L1 感知帧

#### `GET /api/l1_frames`

获取 L1 感知缓冲区的当前快照（每机器人最多保留 8 帧，进程内存储，重启清空）。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 机器人 ID |

**响应**：

```json
{
  "items": [
    {
      "frame_id": 1,
      "timestamp": "2025-07-01T12:00:01",
      "perception": { ... }
    }
  ]
}
```

---

## 5. 管理后台 API（`/api/admin`）

> 除 `/api/admin/login` 外，所有 Admin 接口需要 sa-token 认证。

### 5.1 登录

#### `POST /api/admin/login`

管理员登录。校验 `config.yaml` 中的 `server.admin_token`。

**请求体**（可选）：

```json
{
  "token": "your-admin-token"
}
```

- 若 `server.admin_token` 为空（未配置），任何请求均放行。
- 若已配置，`token` 必须匹配。

**响应**：

```json
{
  "ok": true,
  "token": "sa-token-value"
}
```

后续请求自动携带返回的 token（Cookie / Header）。

---

### 5.2 机器人管理

#### `GET /api/admin/robots`

列出所有机器人（含统计信息）。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `q` | string | 否 | 搜索关键词（匹配 display_name 或 robot_id） |

**响应**：

```json
{
  "items": [
    {
      "robot_id": "rbt-001",
      "display_name": "小泡芙",
      "user_count": 3,
      "device_count": 5,
      "memory_count": 120,
      "last_seen_at": "2025-07-01T10:30:00",
      "created_at": "2025-01-01 12:00:00"
    }
  ]
}
```

---

#### `GET /api/admin/robots/{robotId}`

获取机器人详情（含完整配置 + 统计）。

---

#### `PATCH /api/admin/robots/{robotId}`

修改机器人显示名称。

**请求体**：

```json
{
  "display_name": "新名字"
}
```

---

#### `DELETE /api/admin/robots/{robotId}`

删除机器人及其所有关联数据（用户绑定、记忆、对话、提醒、主动日志、语音段流水、L1 缓冲）。

**响应**：

```json
{
  "ok": true,
  "robot_id": "rbt-001",
  "deleted": {
    "pb_core_robots": 1,
    "pb_core_devices": 5,
    "pb_mem_memories": 120,
    "pb_chat_conversations": 300,
    "pb_rem_reminders": 10,
    "pb_pro_proactive_log": 50,
    "pb_voice_segment_logs": 200
  }
}
```

---

### 5.3 用户管理

#### `GET /api/admin/users`

列出所有用户（含统计信息）。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `q` | string | 否 | 搜索关键词 |

---

#### `GET /api/admin/users/{userId}`

获取用户详情（含绑定设备、机器人列表）。

---

### 5.4 记忆管理

#### `DELETE /api/admin/memories/{memId}`

删除指定记忆。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 用于校验归属 |

**响应**：

```json
{
  "ok": true,
  "id": 101
}
```

---

### 5.5 配置管理

#### `GET /api/admin/config`

获取当前完整运行时配置（敏感字段如 `api_key` 已脱敏）。

**响应**：

```json
{
  "config": {
    "llm": { "api_key": "sk-****", "model": "deepseek-chat", ... },
    "speech": { "enabled": true, "tts": { "api_key": "sk-****" }, ... },
    "server": { "host": "0.0.0.0", "port": 8000, "admin_token": "***" },
    ...
  },
  "schema": { ... },
  "tts_voices": [ ... ],
  "config_path": "/app/config.yaml",
  "restart_required_for": ["server.host", "server.port"]
}
```

---

#### `PUT /api/admin/config`

更新配置（深度合并到 `config.yaml`，热生效）。

**请求体**：

```json
{
  "config": {
    "llm": {
      "model": "deepseek-v3"
    },
    "speech": {
      "enabled": false
    }
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `config` | object | 是 | 要更新的配置键值对（深度合并） |

> 修改 `server.host` 或 `server.port` 后需手动重启服务生效。

---

### 5.6 服务管理

#### `GET /api/admin/service`

获取服务运行信息。

**响应**：

```json
{
  "pid": 12345,
  "host": "0.0.0.0",
  "port": 8000,
  "speech_config_enabled": true,
  "speech_runtime": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `pid` | long | 进程 PID |
| `host` | string | 监听地址 |
| `port` | integer | 监听端口 |
| `speech_config_enabled` | boolean | 配置中语音是否启用 |
| `speech_runtime` | boolean | 运行时语音是否可用（含 SDK 初始化状态） |

---

#### `PUT /api/admin/service`

更新服务设置（当前支持开关语音功能）。

**请求体**：

```json
{
  "speech_enabled": true
}
```

**响应**：

```json
{
  "ok": true,
  "speech_config_enabled": true,
  "speech_runtime": true
}
```

---

#### `POST /api/admin/service/restart`

重启服务进程（延迟 600ms 后 `System.exit(0)`，由容器/守护进程自动拉起）。

**响应**：

```json
{
  "ok": true,
  "message": "服务正在重启"
}
```

---

### 5.7 数据清除

#### `POST /api/admin/wipe`

清除指定机器人的数据。

**请求体**：

```json
{
  "scope": "memories",
  "robot_id": "rbt-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scope` | string | 是 | 清除范围：`memories` / `conversations` / `reminders` / `all` |
| `robot_id` | string | 是 | 目标机器人 ID |

**scope 行为**：

| scope | 清除内容 |
|-------|----------|
| `memories` | 记忆表 + L1 缓冲 |
| `conversations` | 对话历史表 |
| `reminders` | 提醒表 |
| `all` | 以上全部 + 主动日志 + 语音段流水 |

**响应**：

```json
{
  "ok": true,
  "scope": "memories",
  "deleted": {
    "pb_mem_memories": 120
  }
}
```

---

## 6. 数据模型参考

### 6.1 通用结构体

#### AudioPayload

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `format` | string | `"wav"` | 音频格式 |
| `encoding` | string | `"base64"` | 编码方式 |
| `sample_rate` | integer | `16000` | 采样率 |
| `data` | string | `""` | base64 编码的音频数据 |

#### VoiceProsody

| 字段 | 类型 | 可选值 | 说明 |
|------|------|--------|------|
| `tone` | string | `温柔`, `平静`, `急躁`, `兴奋`, `低落`, `撒娇`, `疑问`, `冷淡` | 语气 |
| `intonation` | string | `平稳`, `上扬`, `下沉`, `起伏大` | 语调 |
| `speed` | string | `慢`, `正常`, `快`, `极快` | 语速 |

#### RobotOutput

| 字段 | 类型 | 说明 |
|------|------|------|
| `text` | string | 回复文本 |
| `facial_expression` | FacialExpression | 面部表情 |
| `facial_expression_label` | string | 表情中文标签 |
| `robot_state` | RobotState | 机器人状态 |
| `robot_state_label` | string | 状态中文标签 |
| `voice` | VoiceProsody | 语音参数 |
| `audio` | AudioPayload | TTS 音频 |
| `gesture` | GestureAction | 手势动作 |
| `posture` | PostureAction | 体态动作 |

#### GestureAction / PostureAction

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 动作类型（取值见 Schema 接口） |
| `params` | object | 动作参数（预留） |

---

### 6.2 枚举值

#### FacialExpression（7 类）

| key | label |
|-----|-------|
| `angry` | 愤怒 |
| `disgust` | 厌恶 |
| `fear` | 恐惧 |
| `happy` | 开心 |
| `neutral` | 平静 |
| `sad` | 悲伤 |
| `surprise` | 惊讶 |

> 支持中文别名输入，如 `"开心"` → `happy`，`"生气"` → `angry`。详见 `GET /api/schema` 的 `facial_expression_aliases`。

#### RobotState（9 态 FSM）

| key | label | 说明 |
|-----|-------|------|
| `idle` | 待机 | 空闲状态 |
| `gazing` | 注视 | 正在注视用户 |
| `listening` | 倾听 | 正在听用户说话 |
| `thinking` | 思考 | 正在处理信息 |
| `happy` | 开心 | 愉悦状态 |
| `confused` | 困惑 | 不理解/疑问 |
| `sleepy` | 困倦 | 想睡觉 |
| `sleeping` | 睡觉 | 睡眠中 |
| `waking` | 醒来 | 刚被唤醒 |

#### 表情 → 状态映射（FSM）

| 表情 | 状态 |
|------|------|
| `happy` | `happy` |
| `neutral` | `idle` |
| `sad` | `sleepy` |
| `angry` | `confused` |
| `disgust` | `confused` |
| `fear` | `confused` |
| `surprise` | `confused` |

---

> 📄 本文档基于 Pophie Server Java v1.0.0 源码生成，与 FastAPI 原版 HTTP 契约完全对齐。
