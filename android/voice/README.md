# Pophie Voice SDK（`:voice`）

可复用的 **Java** Android 语音前端库：**降噪 + VAD 自动断句 + 实时 PCM 输出 + 说话人(主人)可信度**。
**零网络、零业务耦合**——把连续麦克风（或上层喂入的 PCM）变成"降噪、断好句、带说话人可信度"的实时帧流，
要不要发服务器、做 STT、按可信度取舍都由上层决定。

## 能力
- **降噪**：麦克风会话挂系统 `NoiseSuppressor` + `AcousticEchoCanceler`(回声) + `AGC`。
- **VAD 断句**：sherpa-onnx Silero VAD + 自有端点状态机（最短语音/尾点静音/最长段/防碎句/preRoll 防吞首字）。
- **说话人可信度**：sherpa-onnx 声纹 embedding + 端侧 1:1 主人校验（可扩白名单），cosine→0~1 校准、段内 EMA 平滑、margin/次优；**滑窗增量**（~0.3s 早出，随音频 refine，长段周期复核）。
- **实时输出**：`onAudioFrame` 实时吐 PCM16 + float，并附 `SpeakerInfo`。两种门控：`REPORT`（全出+标注）/ `OWNER_ONLY`（只出主人）。
- **provider 无关**：`MIC` 内置采集，或 `EXTERNAL_PCM` 由上层 `pushPcm()` 喂入（无麦克风也可用）。

## 依赖准备（sherpa-onnx）
本库依赖 sherpa-onnx（Silero VAD + 声纹 embedding）。**已默认采用本地 AAR：`voice/libs/sherpa-onnx.aar`（v1.13.3，约 54MB，含 arm64-v8a/armeabi-v7a/x86/x86_64 原生库；本仓库已下载好）**。
本 SDK 的 API 调用已对照 v1.13.3 的 `classes.jar` 逐一核验（`Vad`/`VadModelConfig`/`SileroVadModelConfig`/`TenVadModelConfig`/`SpeakerEmbeddingExtractor`/`SpeakerEmbeddingExtractorConfig`/`OnlineStream`）。

替代方式：
1. **重新下载本地 AAR**：`powershell -File voice/libs/download-sherpa-onnx.ps1 -Version 1.13.3`（`settings.gradle.kts` 已配 `flatDir`）。
2. **jitpack**：注释 `voice/build.gradle.kts` 的 `implementation(":sherpa-onnx@aar")`，改 `implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")`（`settings.gradle.kts` 已加 jitpack 源；jitpack 内部下的就是同一个 AAR）。

> 升级版本时若 `VadModelConfig` 构造变更，按新版调整 `vad/SileroVad.java`。

## 模型
- Silero VAD：`silero_vad.onnx`（~2MB）。
- 声纹：`SMALL_INT8`→campplus（较小较快）/`ACCURATE`→eres2net。
- 默认**首次使用自动下载**到 `filesDir/pophie-voice/`（`ModelManager`，地址见该类常量，必要时 `setSpeakerModelUrl` 覆盖）。
  也可 `VoiceConfig.useAssets(true)` 改为从 `assets/` 读取（记得把 `.onnx` 放进 `voicedemo`/宿主 assets，且 `noCompress` 已配）。

## 用法
```java
VoiceConfig cfg = new VoiceConfig.Builder()
    .sampleRate(16000)
    .gateMode(GateMode.REPORT)        // 或 OWNER_ONLY
    .ownerThreshold(0.55f)            // 真机标定
    .enableSystemDenoise(true).enableAec(true)
    .speakerModel(SpeakerModel.SMALL_INT8)
    .source(AudioSourceType.MIC)      // 或 EXTERNAL_PCM
    .build();

VoiceEngine engine = new VoiceEngine(context, cfg);
engine.setListener(new VoiceListener() {
    @Override public void onSpeakingStateChanged(boolean speaking) { }
    @Override public void onAudioFrame(short[] pcm16, float[] pcmFloat, int sr, SpeakerInfo spk) {
        // 实时 PCM + 当前说话人可信度（spk.confidence / spk.isOwner / spk.state）
    }
    @Override public void onSpeakerUpdated(SpeakerInfo spk) { }
    @Override public void onSegmentEnd(VoiceSegment seg) { /* seg.toWav() / seg.embedding */ }
    @Override public void onError(VoiceError e) { }
});
engine.start();                       // MIC 模式自动采集；EXTERNAL_PCM 用 engine.pushPcm(frame)
// ...
engine.stop();
```
**登记主人**（后台线程，可能下载模型）：
```java
new Thread(() -> {
    try { engine.enrollOwner(java.util.Collections.singletonList(pcm4s)); }
    catch (Exception e) { /* ... */ }
}).start();
```
权限：宿主需在运行时申请 `RECORD_AUDIO`（MIC 模式）。

## 上层接线示例（与本库解耦）
拿到 `onAudioFrame` 的主人 PCM 后，上层自行：攒成 WAV 发服务器 / 本地 STT / 其它。
`SpeakerInfo` 可映射到业务的"当前身份+置信度"。本库不含任何网络代码。

## 构建与验证（在 Android Studio / 有 Android SDK 的环境）
```
./gradlew :voice:assembleDebug            # 编译库（需先放好 sherpa-onnx 依赖）
./gradlew :voice:testDebugUnitTest        # Endpointer 纯逻辑单测
./gradlew :voicedemo:assembleDebug        # 调试 Demo
```
Demo（`:voicedemo`）：开始/停止、登记主人(4s)、清空声纹、切 REPORT/OWNER_ONLY；
实时看 speaking、`SpeakerInfo(conf/margin/state/主人)`、输出字节、段日志。

## 声纹匹配率注意（重要）
- **自动阈值（推荐，按人自适应，只记一次）**：用 `enrollOwnerFromMic(8000)` 让主人连续说 ~8s(几句话)，
  SDK 切多窗算 embedding 质心 + 主人内部自相似分布，自动定阈值 `μ-2σ`(钳制 [0.30,0.60]) 并持久化；
  运行时打分用这个个性化阈值。不同人/不同手机各自自适应，无需手调。`ownerThreshold()` 可读当前阈值。
- **登记与打分同一信号域**：声纹用**输出路(原始/系统降噪)**音频，**不**用高通分析路；登记走 `enrollOwnerFromMic`(与运行时同一 MicSource)。
- `embed()` 内部统一**去首尾静音**，登记/打分都只用有声段。
- `VoiceConfig.ownerThreshold` 仅作**未自动标定时的回退默认**(0.5)；自检见 `verifyOwnerFromMic()` / Demo「自检相似度」。

## 调参与局限
- `ownerThreshold`、cosine→概率校准（`SpeakerScorer.calibrate` 的 k）、`preRollMs`、`maxSilenceMs`、`minScoreRms` 需真机标定。
- 段首 ~0.3s 可信度偏临时（PENDING→refine）；过短语音不稳；远场/强噪退化；多人重叠 diarization 不做。
- 强降噪会损伤声纹：本库**双路**——输出走系统降噪，VAD/声纹喂高通轻处理信号（保音色）。
- 体积：`.so` release 仅 arm64，debug 含 x86_64。
