# 回复通知与服务端 TTS 架构

> 版本：1.0.0 · 最后更新：2026-07-01  
> 适用：`server-java` 后端 + Android `:voice-server` / `:voicedemo`

当服务端判定「需要回复用户」时（对话回复、主动发言、提醒等），采用**非阻断通知 + 服务端合成 + 客户端播放**的架构：用户可继续说话，采集不中断；客户端收到通知后展示文本，并播放服务端推送的 TTS 音频。

---

## 一、设计目标

| 目标 | 说明 |
|------|------|
| **不阻断采集** | `VoiceEngine` 持续麦克风采集与 VAD 断句；TTS 在独立线程播放 |
| **及时通知客户端** | 通过 NDJSON 行流或 WebSocket 推送回复阶段与文本 |
| **服务端统一 TTS** | 合成在服务端完成，音色/情感与 LLM 输出一致 |
| **客户端只播放** | 客户端不二次请求 `/api/tts/stream`，直接播放 `tts_chunk` |
| **双通道合一** | 对话 `chat/stream` 与主动 `reply/notify` 共用同一套事件协议与播放队列 |

---

## 二、总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         Android 端侧                             │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────┐ │
│  │ VoiceEngine  │───►│ VoiceServerBridge │───►│ ReplyAudio    │ │
│  │ (采集/VAD)   │    │ (桥接/路由)       │    │ Player (播放) │ │
│  └──────────────┘    └────────┬─────────┘    └───────────────┘ │
│                               │                                   │
│                    ┌──────────┴──────────┐                       │
│                    ▼                     ▼                       │
│           PophieApiClient        ReplyNotifyClient               │
│           POST chat/stream       WS /api/reply/notify            │
└────────────────────┬────────────────────┬──────────────────────┘
                     │ NDJSON               │ WebSocket JSON
                     ▼                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                         server-java                              │
│  ┌─────────────┐   ┌──────────────────┐   ┌────────────────────┐  │
│  │ ChatService │──►│ ReplyStreamEmitter│──►│ SpeechService TTS │  │
│  │ chat/stream │   │ (统一事件发射)    │   │ (CosyVoice 流式)  │  │
│  └─────────────┘   └──────────────────┘   └────────────────────┘  │
│  ┌──────────────────┐   ┌──────────────────────────────────────┐  │
│  │ ProactiveService │──►│ ReplyNotifyService → reply/notify WS │  │
│  │ ReminderService  │   └──────────────────────────────────────┘  │
│  └──────────────────┘                                              │
└─────────────────────────────────────────────────────────────────┘
```

### 两条触发路径

| 路径 | 触发场景 | 传输 | 典型调用方 |
|------|----------|------|------------|
| **A. 对话回复** | 用户说完一段 → `chat/stream` | HTTP `POST /api/chat/stream` → NDJSON | `VoiceServerBridge.requestChatStream` |
| **B. 主动推送** | 主动陪伴 / 提醒到期 | WebSocket `GET /api/reply/notify` | `ProactiveService`、`ReminderService` |

两条路径的事件格式由 `ReplyStreamEmitter` 统一生成，Android 端由 `ReplyAudioPlayer` 按 `seq` 顺序排队播放。

---

## 三、事件协议（NDJSON / WebSocket 共用）

每行一个 JSON 对象。`chat/stream` 在 HTTP 响应体按行推送；`reply/notify` 通过 WebSocket 文本帧推送（内容与 NDJSON 行相同）。

### 3.1 回复生命周期 `type: reply`

| phase | 含义 | 主要字段 |
|-------|------|----------|
| `start` | 开始生成回复 | `session_id`, `source`（`chat` / `proactive` / `reminder` 等） |
| `speak` | 一段可朗读文本（LLM 流式切片） | `seq`, `text` |
| `speak_done` | 该 `seq` 的 TTS 音频已全部推送完毕 | `seq` |
| `tts_error` | 该 `seq` TTS 合成失败 | `seq`, `message` |
| `done` | 整轮回复结束（仅 `chat/stream` 在 LLM 完成后） | `session_id` |

示例：

```json
{"type":"reply","phase":"start","session_id":"sess-a1b2c3d4","source":"chat"}
{"type":"reply","phase":"speak","session_id":"sess-a1b2c3d4","seq":1,"text":"你好呀"}
{"type":"speak","text":"你好呀","seq":1}
{"type":"tts_meta","seq":1,"format":"pcm","sample_rate":22050,"encoding":"base64"}
{"type":"tts_chunk","seq":1,"data":"<base64 pcm>"}
{"type":"reply","phase":"speak_done","session_id":"sess-a1b2c3d4","seq":1}
{"type":"reply","phase":"done","session_id":"sess-a1b2c3d4"}
```

### 3.2 朗读文本 `type: speak`

与 `reply.phase=speak` 同步推送，便于 UI 逐字/逐句展示。字段：`text`, `seq`。

### 3.3 TTS 音频

| type | 说明 |
|------|------|
| `tts_meta` | 该 `seq` 音频格式：`format`（`pcm` / `mp3`）、`sample_rate`、`encoding` |
| `tts_chunk` | 音频分片，`data` 为 Base64 编码的二进制 |

**播放规则（客户端）：**

1. 收到 `tts_meta(seq)` → 记录格式与采样率  
2. 累加同 `seq` 的 `tts_chunk`  
3. 收到 `reply.phase=speak_done`（或 `tts_error`）→ 标记该 `seq` 可播放  
4. 按 `seq` 从 1 递增顺序播放；空音频跳过，避免队列卡住  

### 3.4 chat/stream 收尾

`chat/stream` 在 LLM 与侧任务处理完成后额外推送：

```json
{"type":"done","response":{...ChatResponse...}}
```

`response` 结构与 `POST /api/chat` 的 200 响应一致（`output.text`、`output.robot_state` 等）。

---

## 四、服务端组件

### 4.1 ReplyStreamEmitter

路径：`server-java/.../ReplyStreamEmitter.java`

统一发射 `reply` / `speak` / `tts_meta` / `tts_chunk` 事件。

- `emitSpeak(text)`：立即推送文本通知；若启用服务端 TTS，在 `bgExecutor` 异步合成并推送音频  
- TTS 与文本推送解耦：LLM 继续流式生成，不等待合成完成  
- `emitLock`：保证同一连接上 NDJSON 行顺序  

### 4.2 ChatService.chatStream

路径：`server-java/.../ChatService.java`

- 入口：`POST /api/chat/stream`（`application/x-ndjson`）  
- 请求体与 `POST /api/chat` 相同（`ChatRequest`）  
- 关键请求字段：`input.skip_tts=true`（不走内嵌 MP3）、`input.server_tts=true`（附带 `tts_*` 事件）  
- `shouldServerTts()` 逻辑：  
  - `server_tts=false` → 不推送音频  
  - `server_tts=true` → 且 `speech.enabled`  
  - 省略时遵循配置 `chat.stream_server_tts`（默认 `true`）  

### 4.3 ReplyNotifyService + WebSocket

| 组件 | 路径 | 职责 |
|------|------|------|
| `ReplyNotifyWebSocketHandler` | `websocket/ReplyNotifyWebSocketHandler.java` | 注册连接，下发 `ready` |
| `ReplyNotifyService` | `service/ReplyNotifyService.java` | 按 `robot_id` + `user_id`（及可选 `session_id`）匹配订阅者并推送 |
| `WebSocketConfig` | `configuration/WebSocketConfig.java` | 注册 `/api/reply/notify` |

**连接 URL：**

```
ws://<host>:<port>/api/reply/notify?robot_id=<id>&user_id=<id>&session_id=<可选>
```

连接成功后服务端推送：`{"type":"ready"}`。

**调用方：**

- `ProactiveService`：主动陪伴决策为 `speak` 时  
- `ReminderService`：提醒触发时  

### 4.4 与旧方案的关系

| 方案 | 适用客户端 | 说明 |
|------|------------|------|
| `skip_tts` + `POST /api/tts/stream` | 官方 App（`:app`）、Web | 客户端二次请求 TTS，见 [API对接文档](./API对接文档.md) §3.6.1 |
| `server_tts` + `chat/stream` / `reply/notify` | VoiceDemo（`:voice-server`） | 本文档描述的架构，一次请求/长连接拿齐文本+音频 |

两种方案可并存，按客户端能力选择。

---

## 五、Android 端组件（`:voice-server`）

依赖关系：`:voicedemo` → `:voice-server` → `:voice`

| 类 | 职责 |
|----|------|
| `VoiceServerBridge` | 桥接 `VoiceEngine` 与服务端；段结束触发 `chat/stream`；管理 notify 连接 |
| `PophieApiClient` | HTTP：`chat/stream` NDJSON 解析；请求带 `server_tts` |
| `ReplyNotifyClient` | WebSocket 订阅 `/api/reply/notify` |
| `ReplyAudioPlayer` | 按 `seq` 排队播放 PCM/MP3，独立线程，不阻塞采集 |
| `VoiceServerConfig` | `serverReplyTts`、`replyNotifyEnabled` 等开关 |
| `VoiceServerListener` | UI 回调：`onReplyNotify`、`onReplyPlayStart/End` 等 |

### 5.1 对话时序（路径 A）

```
用户说话 → VoiceEngine 断句 → VoiceServerBridge.onSegmentEnd
    ├─ uploadSegmentLog（可选，POST /api/voice/segments）
    └─ requestChatStream（后台线程）
           POST /api/chat/stream { skip_tts: true, server_tts: true, audio: wav }
           ├─ reply/speak → UI 展示文本
           ├─ tts_meta + tts_chunk → ReplyAudioPlayer 缓冲
           └─ speak_done → 播放该 seq
（同时 VoiceEngine 可继续采集下一段，互不阻塞）
```

### 5.2 主动推送时序（路径 B）

```
App 启动 / connectRealtime / testConnection
    → ReplyNotifyClient.connect(/api/reply/notify)
服务端 ProactiveService / ReminderService
    → ReplyNotifyService.notifyReply(text, source)
    → WebSocket 推送与 chat/stream 相同的事件
    → ReplyAudioPlayer（与路径 A 共用队列）
```

### 5.3 配置示例

```java
VoiceServerConfig config = new VoiceServerConfig.Builder()
    .baseUrl("http://192.168.1.100:9901/")
    .robotId("default")
    .userId("demo")
    .serverReplyTts(true)      // chat/stream 请求服务端 TTS
    .replyNotifyEnabled(true)  // 订阅主动回复 WebSocket
    .build();
```

---

## 六、相关 API 索引

| 接口 | 文档章节 |
|------|----------|
| `POST /api/chat/stream` | [API对接文档 §3.4.1](./API对接文档.md#341-流式聊天-apichatstream) |
| `WS /api/reply/notify` | [API对接文档 §3.8.1](./API对接文档.md#381-回复通知websocket-apireplynotify) |
| `POST /api/voice/segments` | 语音段流水（与实时 STT 配合，见 API 文档后续补充） |
| `WS /api/stt/stream` | 实时 STT（`RealtimeSttClient`，与回复播放并行） |

---

## 七、配置项

### 服务端

| 配置 | 默认 | 说明 |
|------|------|------|
| `speech.enabled` | `true` | 关闭后无 TTS 音频事件 |
| `chat.stream_server_tts` | `true` | 未显式传 `server_tts` 时是否附带音频 |
| `speech.tts.stream_format` | `pcm` | `tts_meta.format` |
| `speech.tts.sample_rate` | `22050` | `tts_meta.sample_rate` |

### Android

| 字段 | 默认 | 说明 |
|------|------|------|
| `VoiceServerConfig.serverReplyTts` | `true` | 请求体 `input.server_tts` |
| `VoiceServerConfig.replyNotifyEnabled` | `true` | 是否连接 `reply/notify` |

---

## 八、验证清单

1. 重建并部署 `server-java` Docker 镜像（**必须**，旧镜像无 `reply`/`tts_*` 事件）：
   ```bash
   cd server-java && bash deploy/macos-deploy.sh
   ```
2. Android Studio 编译 `voicedemo`，Base URL 指向 Docker 端口（默认 **9901**）
3. 开启 chat + 实时 STT，说话触发对话 → UI 显示回复文本 + 扬声器播放  
4. 播放过程中继续说话 → 采集与 STT 不应中断  
5. 触发主动消息/提醒 → `reply/notify` WebSocket 收到事件并播放  
6. **快速联调**：`POST /api/reply/test`（或 Demo「测试回复推送」按钮），无需说话即可验证 notify + TTS

---

## 九、源码索引

**服务端**

- `server-java/src/main/java/com/pophie/service/ReplyStreamEmitter.java`
- `server-java/src/main/java/com/pophie/service/ReplyNotifyService.java`
- `server-java/src/main/java/com/pophie/service/ChatService.java`（`chatStream`）
- `server-java/src/main/java/com/pophie/websocket/ReplyNotifyWebSocketHandler.java`
- `server-java/src/main/java/com/pophie/schema/ChatInput.java`（`serverTts`）

**Android**

- `android/voice-server/src/main/java/com/pophie/voice/server/VoiceServerBridge.java`
- `android/voice-server/src/main/java/com/pophie/voice/server/PophieApiClient.java`
- `android/voice-server/src/main/java/com/pophie/voice/server/ReplyNotifyClient.java`
- `android/voice-server/src/main/java/com/pophie/voice/server/ReplyAudioPlayer.java`
- `android/voicedemo/src/main/java/com/pophie/voice/demo/MainActivity.java`
