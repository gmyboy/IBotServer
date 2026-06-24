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
3. 连接真机或启动模拟器（模拟器访问本机后端用 `http://192.168.23.160:8080/`）
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
- 首次激活后机器人主动自我介绍（欢迎语 + TTS）
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
