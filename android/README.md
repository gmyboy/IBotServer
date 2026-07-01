# Pophie Android 客户端

Kotlin + Jetpack Compose 原生应用，通过 JSON API 与后端通信。

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34
- 最低支持 Android 8.0 (API 26)

## 构建与运行

1. 用 Android Studio 打开本目录（`android/`）
2. 等待 Gradle 同步完成
3. 连接真机或启动模拟器（模拟器访问本机后端用 `http://10.0.2.2:8000/`）
4. 点击 Run

## 配置服务器

首次启动后，点击右上角齿轮进入设置，填写后端 Base URL：

```
http://<电脑局域网IP>:8000/
```

在 Windows 上可用 `ipconfig` 查看 IPv4 地址。确保后端 `config.yaml` 中 `server.host` 为 `0.0.0.0`，且防火墙放行 8000 端口。

## 功能

- 文字聊天 + 按住说话（需 `speech.enabled=true`）
- 7 类面部表情选择
- 自动播放机器人 TTS 回复
- 主动消息轮询（5 秒间隔）
- `gesture` / `posture` 已在数据模型预留，UI 未实现

## 权限

- `INTERNET`：访问后端 API
- `RECORD_AUDIO`：语音输入

## 包结构

```
com.pophie.app/
├── data/           # Retrofit API 与 JSON 模型
├── audio/          # 录音与播放
├── ui/             # Compose 界面
└── viewmodel/      # 聊天状态管理
```

## Voice SDK 与 VoiceDemo

除官方 App（`:app`）外，仓库还提供端侧语音 SDK 与调试 Demo：

| 模块 | 说明 |
|------|------|
| `:voice` | 端侧采集、VAD、声纹（零网络） |
| `:voice-server` | 桥接服务端：实时 STT、`chat/stream`、回复通知 + TTS 播放 |
| `:voicedemo` | SDK + 服务端联调 Demo |

**回复通知与服务端 TTS 架构**（非阻断采集、服务端合成、客户端播放）：见 [`docs/回复通知与服务端TTS架构.md`](../docs/回复通知与服务端TTS架构.md)。
