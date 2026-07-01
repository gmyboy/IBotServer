package com.pophie.voice.server;

import android.os.Handler;
import android.os.Looper;

import com.pophie.voice.GateMode;
import com.pophie.voice.VoiceListener;
import com.pophie.voice.VoiceSegment;
import com.pophie.voice.WavUtil;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 桥接 {@link com.pophie.voice.VoiceEngine} 与 Pophie 服务端：
 * <ul>
 *   <li>实时 STT（WebSocket，近场门控，与声纹无关）</li>
 *   <li>段结束 chat/stream（可选，可配合 OWNER_ONLY 门控）</li>
 *   <li>回复通知 + 服务端 TTS 音频播放（独立线程，不阻断采集）</li>
 *   <li>主动发言/提醒订阅 {@code /api/reply/notify}</li>
 * </ul>
 */
public final class VoiceServerBridge {

    private final VoiceServerConfig config;
    private final PophieApiClient api;
    private final RealtimeSttClient realtimeStt;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile VoiceServerListener listener;
    private volatile boolean realtimeEnabled = true;
    private volatile boolean chatEnabled;
    private volatile boolean logEnabled = true;
    private volatile GateMode gateMode = GateMode.REPORT;
    private volatile String sessionId = "";
    /** 实时 STT 最新 partial / final（WebSocket 线程写入）。 */
    private volatile String pendingSttPartial = "";
    private volatile String pendingSttFinal = "";
    private volatile long pendingSttPatchLogId = 0;

    private volatile boolean utteranceOpen;
    private volatile boolean vadSpeaking;
    private int sttStrongFrames;

    private final ReplyAudioPlayer replyPlayer = new ReplyAudioPlayer();
    private final ReplyNotifyClient replyNotify = new ReplyNotifyClient();
    /** 串行 chat/stream，避免多段并行时 reply 事件交错。 */
    private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor();
    /** 串行测试推送，避免连点重叠。 */
    private final ExecutorService notifyTestExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingTestPushes = new AtomicInteger(0);
    private static final int MAX_PENDING_TEST_PUSHES = 3;

    public VoiceServerBridge(VoiceServerConfig config) {
        this.config = config;
        this.api = new PophieApiClient(config);
        this.realtimeStt = new RealtimeSttClient();
        this.logEnabled = config.logEnabled;
        replyPlayer.setListener(new ReplyAudioPlayer.Listener() {
            @Override
            public void onPlayStart(int seq, String text) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyPlayStart(seq);
                });
            }

            @Override
            public void onPlayEnd(int seq) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyPlayEnd(seq);
                });
            }
        });
    }

    public void setListener(VoiceServerListener listener) {
        this.listener = listener;
    }

    public void setBaseUrl(String url) {
        api.setBaseUrl(url);
    }

    public String getBaseUrl() {
        return api.getBaseUrl();
    }

    public void setSessionId(String id) {
        sessionId = id == null ? "" : id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setRealtimeEnabled(boolean enabled) {
        realtimeEnabled = enabled;
    }

    public void setChatEnabled(boolean enabled) {
        chatEnabled = enabled;
    }

    public void setLogEnabled(boolean enabled) {
        logEnabled = enabled;
    }

    public void setGateMode(GateMode mode) {
        gateMode = mode == null ? GateMode.REPORT : mode;
    }

    /** 建立实时 STT WebSocket；若开启则同时订阅回复通知。 */
    public void connectRealtime() {
        if (!realtimeEnabled) return;
        postSttConnecting();
        connectReplyNotify();
        realtimeStt.connect(api.getBaseUrl(), new RealtimeSttClient.Listener() {
            @Override
            public void onConnected() {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onSttReady();
                });
            }

            @Override
            public void onPartial(String text) {
                if (text != null && !text.isEmpty()) {
                    pendingSttPartial = text;
                }
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onSttPartial(text);
                });
            }

            @Override
            public void onFinal(String text) {
                if (text != null && !text.isEmpty()) {
                    pendingSttFinal = text;
                    pendingSttPartial = text;
                    long patchId = pendingSttPatchLogId;
                    if (patchId > 0) {
                        pendingSttPatchLogId = 0;
                        final String finalText = text;
                        new Thread(() -> {
                            try {
                                api.patchVoiceSegmentStt(patchId, finalText);
                            } catch (Exception ignored) {
                            }
                        }).start();
                    }
                }
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onSttFinal(text);
                });
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onSttError(message);
                });
            }

            @Override
            public void onClosed() {
                utteranceOpen = false;
            }
        });
    }

    public void disconnect() {
        realtimeStt.close();
        replyNotify.close();
        replyPlayer.reset();
        utteranceOpen = false;
        sttStrongFrames = 0;
        pendingSttPartial = "";
        pendingSttFinal = "";
        pendingSttPatchLogId = 0;
    }

    /** 仅绑定设备（获取 user_id / robot_id），不建会话。 */
    public void bindDevice() {
        new Thread(() -> {
            try {
                if (config.deviceId == null || config.deviceId.isEmpty()) {
                    throw new IOException("device_id 未配置");
                }
                PophieApiClient.BindInfo info = api.bindDevice();
                connectReplyNotify();
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onDeviceBound(info, null);
                });
            } catch (Exception e) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onDeviceBound(null, e.getMessage());
                });
            }
        }).start();
    }

    public String getDeviceId() {
        return config.deviceId == null ? "" : config.deviceId;
    }

    public String getBoundUserId() {
        return api.getUserId();
    }

    public String getBoundRobotId() {
        return api.getRobotId();
    }

    /** 后台线程：健康检查 + 新建会话。 */
    public void testConnection() {
        new Thread(() -> {
            try {
                PophieApiClient.HealthInfo health = api.checkHealth();
                if (config.deviceId != null && !config.deviceId.isEmpty()) {
                    api.bindDevice();
                }
                PophieApiClient.SessionInfo session = api.newSession();
                sessionId = session.sessionId;
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) {
                        l.onConnectionTested(health.speechEnabled, session.sessionId, null);
                    }
                });
                connectReplyNotify();
            } catch (Exception e) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) {
                        l.onConnectionTested(false, null, e.getMessage());
                    }
                });
            }
        }).start();
    }

    /**
     * 挂到 {@link com.pophie.voice.VoiceEngine#setListener}。
     * 实时 STT 走 {@link VoiceListener#onAudioFrameSync}；声纹仍走原有回调。
     */
    public VoiceListener createVoiceListener() {
        return new VoiceListener() {
            @Override
            public void onSpeakingStateChanged(boolean speaking) {
                vadSpeaking = speaking;
                if (!speaking) {
                    sttStrongFrames = 0;
                    if (realtimeEnabled && utteranceOpen) {
                        realtimeStt.commitUtterance();
                        utteranceOpen = false;
                    }
                }
            }

            @Override
            public void onAudioFrameSync(short[] pcm16, int sampleRate) {
                if (!realtimeEnabled || !realtimeStt.isConnected()) return;
                double rms = WavUtil.rms(pcm16);
                if (rms < config.nearFieldMinRms) {
                    sttStrongFrames = 0;
                    return;
                }
                if (!utteranceOpen) {
                    sttStrongFrames++;
                    if (!vadSpeaking || sttStrongFrames < config.nearFieldStartFrames) return;
                    realtimeStt.beginUtterance(sampleRate);
                    utteranceOpen = true;
                }
                realtimeStt.feedPcm(pcm16);
            }

            @Override
            public void onSegmentEnd(VoiceSegment seg) {
                if (realtimeEnabled && utteranceOpen) {
                    realtimeStt.commitUtterance();
                    utteranceOpen = false;
                }
                sttStrongFrames = 0;
                uploadSegmentLog(seg);
                requestChatStream(seg);
            }
        };
    }

    /** 近场 RMS 是否达标（供 UI 显示电平）。 */
    public boolean isNearField(double rms) {
        return rms >= config.nearFieldMinRms;
    }

    public double getNearFieldMinRms() {
        return config.nearFieldMinRms;
    }

    private void uploadSegmentLog(VoiceSegment seg) {
        if (!logEnabled) return;
        if (seg.durationMs < config.minLogSegmentMs) return;
        final boolean isOwner = seg.speaker != null && seg.speaker.isOwner;
        final String gate = gateMode.name();
        new Thread(() -> {
            try {
                String sttText = resolveSttTextForUpload();
                if (sessionId.isEmpty()) {
                    PophieApiClient.SessionInfo session = api.newSession();
                    sessionId = session.sessionId;
                }
                PophieApiClient.SegmentLogResult result = api.uploadVoiceSegment(
                        sessionId, seg, sttText, config, gate);
                sessionId = result.sessionId;
                String loggedStt = (result.sttText != null && !result.sttText.isEmpty())
                        ? result.sttText : sttText;
                if (loggedStt == null || loggedStt.isEmpty()) {
                    pendingSttPatchLogId = result.id;
                } else {
                    pendingSttPatchLogId = 0;
                }
                final String finalStt = loggedStt != null ? loggedStt : "";
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onSegmentLogged(result.id, isOwner, finalStt);
                });
            } catch (Exception e) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onSegmentLogError(e.getMessage());
                });
            }
        }).start();
    }

    /**
     * 段结束往往早于 WebSocket final，短暂等待 final；超时则用 partial 兜底。
     */
    private String resolveSttTextForUpload() {
        if (!realtimeEnabled) {
            return takeSttText();
        }
        long deadline = System.currentTimeMillis() + config.sttUploadWaitMs;
        while (System.currentTimeMillis() < deadline) {
            String fin = pendingSttFinal;
            if (fin != null && !fin.isEmpty()) {
                clearSttText();
                return fin;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return takeSttText();
    }

    private String takeSttText() {
        String fin = pendingSttFinal;
        if (fin != null && !fin.isEmpty()) {
            clearSttText();
            return fin;
        }
        String partial = pendingSttPartial;
        clearSttText();
        return partial != null ? partial : "";
    }

    private void clearSttText() {
        pendingSttFinal = "";
        pendingSttPartial = "";
    }

    /** 订阅 /api/reply/notify（已连接则复用，避免连点断开）。 */
    public void connectReplyNotify() {
        if (!config.replyNotifyEnabled) return;
        ensureReplyNotifyConnected(5_000);
    }

    private boolean ensureReplyNotifyConnected(long readyTimeoutMs) {
        return replyNotify.connectIfNeeded(
                api.getBaseUrl(), api.getRobotId(), api.getUserId(), sessionId,
                createReplyNotifyHandler(), readyTimeoutMs);
    }

    private ReplyNotifyClient.EventHandler createReplyNotifyHandler() {
        return new ReplyNotifyClient.EventHandler() {
            @Override
            public void onReady() { }

            @Override
            public void onReplyEvent(String phase, String text, int seq, String source) {
                routeReplyEvent(phase, seq);
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyNotify(phase, text, source);
                });
            }

            @Override
            public void onTtsMeta(int seq, String format, int sampleRate) {
                replyPlayer.onTtsMeta(seq, format, sampleRate);
            }

            @Override
            public void onTtsChunk(int seq, byte[] audio) {
                replyPlayer.onTtsChunk(seq, audio);
            }

            @Override
            public void onError(String message) {
                replyPlayer.recover();
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyNotify("error", message, "notify");
                });
            }

            @Override
            public void onClosed() {
                replyPlayer.recover();
            }
        };
    }

    private PophieApiClient.ChatStreamHandler createChatStreamHandler(
            StringBuilder streamReply) {
        return new PophieApiClient.ChatStreamHandler() {
            @Override
            public void onReply(String phase, String text, int seq, String source) {
                routeReplyEvent(phase, seq);
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyNotify(phase, text, source);
                });
            }

            @Override
            public void onSpeak(String text, int seq) {
                streamReply.append(text);
                // 展示走 onReply(phase=speak)；此处仅累积完整回复供 onChatComplete 兜底
            }

            @Override
            public void onTtsMeta(int seq, String format, int sampleRate) {
                replyPlayer.onTtsMeta(seq, format, sampleRate);
            }

            @Override
            public void onTtsChunk(int seq, byte[] audio) {
                replyPlayer.onTtsChunk(seq, audio);
            }
        };
    }

    private void routeReplyEvent(String phase, int seq) {
        if ("start".equals(phase)) {
            replyPlayer.onReplyStart();
        } else if ("done".equals(phase)) {
            replyPlayer.onReplyDone();
        } else if (("speak_done".equals(phase) || "tts_error".equals(phase)) && seq > 0) {
            replyPlayer.onSpeakDone(seq);
        }
    }

    private void requestChatStream(VoiceSegment seg) {
        if (!chatEnabled) {
            main.post(() -> {
                VoiceServerListener l = listener;
                if (l != null) l.onChatSkipped("chat 未开启（请打开开关）");
            });
            return;
        }
        if (seg.durationMs < config.minChatSegmentMs) {
            main.post(() -> {
                VoiceServerListener l = listener;
                if (l != null) l.onChatSkipped("段太短 <" + config.minChatSegmentMs + "ms");
            });
            return;
        }
        if (gateMode == GateMode.OWNER_ONLY && (seg.speaker == null || !seg.speaker.isOwner)) {
            main.post(() -> {
                VoiceServerListener l = listener;
                if (l != null) l.onChatSkipped("非主人段");
            });
            return;
        }
        byte[] wav = seg.toWav();
        final StringBuilder streamReply = new StringBuilder();
        chatExecutor.execute(() -> {
            try {
                if (sessionId.isEmpty()) {
                    PophieApiClient.SessionInfo session = api.newSession();
                    sessionId = session.sessionId;
                }
                PophieApiClient.ChatStreamResult result = api.chatStream(
                        sessionId, wav, seg.sampleRate,
                        createChatStreamHandler(streamReply), config);
                sessionId = result.sessionId;
                String replyLine = result.replyText.isEmpty() ? "" : result.replyText;
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onChatComplete(replyLine);
                });
            } catch (Exception e) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onChatError(e.getMessage());
                });
            }
        });
    }

    private int testPushCounter;

    /** 联调：请求服务端推送测试回复（需 notify WebSocket 已 ready）。 */
    public void testReplyPush() {
        if (pendingTestPushes.incrementAndGet() > MAX_PENDING_TEST_PUSHES) {
            pendingTestPushes.decrementAndGet();
            main.post(() -> {
                VoiceServerListener l = listener;
                if (l != null) {
                    l.onReplyNotify("error", "推送排队已满（最多 " + MAX_PENDING_TEST_PUSHES
                            + " 条），请稍候", "test");
                }
            });
            return;
        }
        notifyTestExecutor.execute(() -> {
            try {
                if (!ensureReplyNotifyConnected(8_000)) {
                    throw new IOException("reply/notify 未连接，请先点「测连接」或稍候再试");
                }
                int n = ++testPushCounter;
                api.testReplyPush("测试语音 " + n);
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyNotify("test_sent", "已请求测试推送 #" + n, "test");
                });
            } catch (Exception e) {
                replyPlayer.recover();
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyNotify("error", e.getMessage(), "test");
                });
            } finally {
                pendingTestPushes.decrementAndGet();
            }
        });
    }

    private void postSttConnecting() {
        main.post(() -> {
            VoiceServerListener l = listener;
            if (l != null) l.onSttConnecting();
        });
    }
}
