package com.pophie.voice.server;

import android.os.Handler;
import android.os.Looper;

import com.pophie.voice.GateMode;
import com.pophie.voice.VoiceListener;
import com.pophie.voice.VoiceSegment;
import com.pophie.voice.WavUtil;

/**
 * 桥接 {@link com.pophie.voice.VoiceEngine} 与 Pophie 服务端：
 * <ul>
 *   <li>实时 STT（WebSocket，近场门控，与声纹无关）</li>
 *   <li>段结束 chat/stream（可选，可配合 OWNER_ONLY 门控）</li>
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

    public VoiceServerBridge(VoiceServerConfig config) {
        this.config = config;
        this.api = new PophieApiClient(config);
        this.realtimeStt = new RealtimeSttClient();
        this.logEnabled = config.logEnabled;
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

    /** 建立实时 STT WebSocket。 */
    public void connectRealtime() {
        if (!realtimeEnabled) return;
        postSttConnecting();
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
        utteranceOpen = false;
        sttStrongFrames = 0;
        pendingSttPartial = "";
        pendingSttFinal = "";
        pendingSttPatchLogId = 0;
    }

    /** 后台线程：健康检查 + 新建会话。 */
    public void testConnection() {
        new Thread(() -> {
            try {
                PophieApiClient.HealthInfo health = api.checkHealth();
                PophieApiClient.SessionInfo session = api.newSession();
                sessionId = session.sessionId;
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) {
                        l.onConnectionTested(health.speechEnabled, session.sessionId, null);
                    }
                });
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

    private void requestChatStream(VoiceSegment seg) {
        if (!chatEnabled) return;
        if (seg.durationMs < config.minChatSegmentMs) return;
        if (gateMode == GateMode.OWNER_ONLY && (seg.speaker == null || !seg.speaker.isOwner)) {
            main.post(() -> {
                VoiceServerListener l = listener;
                if (l != null) l.onChatSkipped("非主人段");
            });
            return;
        }
        byte[] wav = seg.toWav();
        final StringBuilder streamReply = new StringBuilder();
        new Thread(() -> {
            try {
                if (sessionId.isEmpty()) {
                    PophieApiClient.SessionInfo session = api.newSession();
                    sessionId = session.sessionId;
                }
                PophieApiClient.ChatStreamResult result = api.chatStream(
                        sessionId, wav, seg.sampleRate, chunk -> main.post(() -> {
                            streamReply.append(chunk);
                            VoiceServerListener l = listener;
                            if (l != null) l.onChatSpeakChunk(chunk);
                        }));
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
        }).start();
    }

    private void postSttConnecting() {
        main.post(() -> {
            VoiceServerListener l = listener;
            if (l != null) l.onSttConnecting();
        });
    }
}
