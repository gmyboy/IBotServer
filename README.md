# Pophie 四层记忆陪伴机器人（参考实现）

参照 `docs/` 下两份调研报告中 **Pophie 的 L1-L4 漏斗记忆架构** 设计，
实现网页版 + **Android 原生客户端** 陪伴机器人：支持 JSON 结构化 API、语音对话（STT/TTS）、
7 类面部表情，自动完成记忆下沉与跨层固化。

## 一、架构概览

```
用户(文字/语音/表情) ──► FastAPI JSON API
                              │
                    ┌─────────┼─────────┐
                    ▼         ▼         ▼
              DashScope STT   LLM 回复   DashScope TTS
              (Paraformer)  (结构化)   (CosyVoice API)
                    │         │         │
                    └─────────┴─────────┘
                              ▼
                    L1-L4 记忆漏斗 + 主动感知
```

### 面部表情（7 类）

| key | 中文 |
|-----|------|
| angry | 恼怒 |
| disgust | 厌恶 |
| fear | 恐惧 |
| happy | 开心 |
| neutral | 中性 |
| sad | 悲伤 |
| surprise | 惊讶 |

`gesture` / `posture` 已在 API 中预留，当前版本不实现。

## 二、运行

### 1. 配置

编辑 [`config.yaml`](config.yaml)：

```yaml
llm:
  base_url: "https://api.deepseek.com/v1"
  api_key: "sk-..."
  model: "deepseek-chat"

server:
  host: "0.0.0.0"    # 允许手机通过局域网访问
  port: 8000

speech:
  enabled: true
  stt:
    model: qwen3-asr-flash-realtime
    language: zh
    sample_rate: 16000
  tts:
    api_key: "sk-..."     # 阿里云百炼 DashScope API Key
    model: cosyvoice-v3-flash
    default_voice: gentle_female
    sample_rate: 22050
```

### 2. 启动后端

```bat
run.bat
```

- 默认 `speech.enabled=false`：仅文本模式
- 启用语音：`pip install dashscope`，将 `speech.enabled` 改为 `true`，并在 `speech.tts.api_key` 填入 [阿里云百炼 DashScope API Key](https://help.aliyun.com/zh/model-studio/get-api-key)

访问网页：[http://127.0.0.1:8000](http://127.0.0.1:8000)

### 3. Android 客户端

1. 用 Android Studio 打开 [`android/`](android/) 目录
2. 确保手机与电脑在同一 WiFi
3. 在 App 设置中填写后端地址，例如 `http://192.168.1.100:8000/`
4. 授予麦克风权限，按住说话或输入文字

详见 [`android/README.md`](android/README.md)。

## 三、JSON API 契约

### POST `/api/chat`

**请求：**

```json
{
  "robot_id": "default",
  "session_id": "sess-abc123",
  "input": {
    "text": "",
    "audio": {
      "format": "wav",
      "encoding": "base64",
      "sample_rate": 16000,
      "data": "..."
    },
    "perception": {
      "facial_expression": "sad",
      "voice": { "tone": "低落", "intonation": "下沉", "speed": "慢" },
      "touch": "摸头",
      "identity": "小明",
      "gesture": { "type": "wave" },
      "posture": null
    }
  }
}
```

> `identity`（端侧身份识别到的人名）与 `gesture`（端侧手势）现已喂给大模型；`identity` 仅作感知上下文，记忆仍按 `robot_id` 隔离。表情接受端侧英文变体（`surprised`/`disgusted`/`fearful`），服务端自动归一化。

**响应：**

```json
{
  "output": {
    "text": "听起来你今天真的很累…",
    "facial_expression": "neutral",
    "facial_expression_label": "中性",
    "robot_state": "idle",
    "robot_state_label": "待机",
    "voice": { "tone": "温柔", "intonation": "平稳", "speed": "慢" },
    "audio": { "format": "wav", "encoding": "base64", "sample_rate": 22050, "data": "..." },
    "gesture": null,
    "posture": null
  },
  "stt": { "text": "今天好累", "voice": { "tone": "低落" } }
}
```

**辅助端点：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查，含 `speech_enabled` |
| GET | `/api/schema` | 表情枚举与语音属性说明 |
| POST | `/api/stt` | 仅语音识别 |
| POST | `/api/tts` | 语音合成（批量，返回完整 audio） |
| POST | `/api/tts/stream` | 语音合成（WebSocket 流式，NDJSON 边收边播） |
## 四、目录结构

```
AIRobot/
├── config.yaml
├── requirements.txt
├── requirements-speech.txt   # 语音可选依赖
├── run.bat
├── backend/
│   ├── main.py               # FastAPI 路由
│   ├── schemas.py            # JSON API 契约
│   ├── speech.py             # DashScope STT + TTS
│   ├── memory.py
│   ├── proactive.py
│   └── ...
├── frontend/
│   └── index.html            # 网页调试端
├── android/                  # Android 原生客户端
└── docs/
```

## 五、语音方案

| 能力 | 实现 |
|------|------|
| STT | **DashScope Qwen3-ASR**（在线 API，含 7 类情绪识别） |
| TTS | **DashScope CosyVoice v3-flash**（WebSocket 实时合成） |

`config.yaml` 中配置 `speech.tts.api_key`（或环境变量 `DASHSCOPE_API_KEY`）。语音属性通过 `VoiceProsody` 传递：`tone`（语气）、`intonation`（语调）、`speed`（语速）。音色 ID 见 `GET /api/schema` 的 `tts_voices`。

## 六、使用要点

- **网页版**：多模态感知模拟、记忆流转可视化、主动 tick
- **Android 版**：按住说话、7 类表情选择、自动播放机器人语音
- **情感双驱动**：记忆下沉由 importance 与 emotion_score 共同决定，见 `config.yaml`
- **不实现遗忘**：按需求保留所有记忆
- **端侧（XBot）对接**：端侧本地完成表情/身份/手势识别后，通过 `perception` 把 `facial_expression`（接受 `surprised/disgusted/fearful` 等别名）、`identity`、`gesture` 发给后端大模型。
- **互动动作状态**：机器人回复携带 `output.robot_state`——一个**限定为虚拟宠物 FSM 9 态**（idle/gazing/listening/thinking/happy/confused/sleepy/sleeping/waking）的互动动作状态，由大模型给出且服务端保证合法；端侧直接用它驱动虚拟形象。缺省时按 `facial_expression` 兜底（映射见 `docs/API.md` §4.4 / `GET /api/schema`）。持续感知与主动陪伴走 `POST /api/tick`。
