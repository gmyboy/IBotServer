package com.pophie.voice.server;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pophie.voice.GateMode;
import com.pophie.voice.VoiceEngine;
import com.pophie.voice.VoiceListener;
import com.pophie.voice.VoiceSegment;
import com.pophie.voice.WavUtil;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VoiceServerBridge {

    private static final String TAG = "VoiceServerBridge";

    private final VoiceServerConfig config;
    private final PophieApiClient api;
    private final RealtimeSttClient realtimeStt;
    private final MqttWakeClient mqttWake;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile VoiceServerListener listener;
    private volatile boolean realtimeEnabled = true;
    private volatile boolean chatEnabled;
    private volatile boolean logEnabled = true;
    private volatile GateMode gateMode = GateMode.REPORT;
    private volatile String sessionId = "";
    private volatile String pendingSttPartial = "";
    private volatile String pendingSttFinal = "";
    private volatile long pendingSttPatchLogId = 0;

    private volatile boolean utteranceOpen;
    private volatile boolean vadSpeaking;
    private int sttStrongFrames;

    private final ReplyAudioPlayer replyPlayer = new ReplyAudioPlayer();
    private final ReplyNotifyClient replyNotify = new ReplyNotifyClient();
    private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService notifyTestExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mqttPullExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-pull");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pendingTestPushes = new AtomicInteger(0);
    private static final int MAX_PENDING_TEST_PUSHES = 3;

    private final Object sessionLock = new Object();
    private volatile VoiceEngine engineRef;
    private final AtomicInteger playbackCount = new AtomicInteger(0);

    private final Object mqttPullLock = new Object();
    private final AtomicInteger mqttPullInFlight = new AtomicInteger(0);
    private final AtomicBoolean mqttPullPending = new AtomicBoolean(false);
    private volatile boolean mqttMode = false;
    private volatile String currentMqttBroker = "";
    private volatile String currentMqttUsername = "";
    private volatile String currentMqttPassword = "";

    public VoiceServerBridge(VoiceServerConfig config) {
        this.config = config;
        this.api = new PophieApiClient(config);
        this.realtimeStt = new RealtimeSttClient();
        this.mqttWake = new MqttWakeClient();
        this.logEnabled = config.logEnabled;
        this.mqttMode = config.mqttBroker != null && !config.mqttBroker.trim().isEmpty();
        this.currentMqttBroker = config.mqttBroker == null ? "" : config.mqttBroker.trim();
        this.currentMqttUsername = config.mqttUsername == null ? "" : config.mqttUsername;
        this.currentMqttPassword = config.mqttPassword == null ? "" : config.mqttPassword;

        replyPlayer.setListener(new ReplyAudioPlayer.Listener() {
            @Override
            public void onPlayStart(int seq, String text) {
                playbackCount.incrementAndGet();
                VoiceEngine e = engineRef;
                if (e != null) e.setMuted(true);
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyPlayStart(seq);
                });
            }

            @Override
            public void onPlayEnd(int seq) {
                if (playbackCount.decrementAndGet() <= 0) {
                    VoiceEngine e = engineRef;
                    if (e != null) e.setMuted(false);
                }
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onReplyPlayEnd(seq);
                });
            }
        });

        mqttWake.setListener(new MqttWakeClient.WakeListener() {
            @Override
            public void onMqttConnected() {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onMqttStatus(true, null);
                });
            }

            @Override
            public void onMqttDisconnected(String reason) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onMqttStatus(false, reason);
                });
            }

            @Override
            public void onMqttError(String message) {
                main.post(() -> {
                    VoiceServerListener l = listener;
                    if (l != null) l.onMqttStatus(false, message);
                });
            }

            @Override
            public void onWake(String robotId, String userId, int msgCount) {
                triggerMqttPull();
            }
        });

        if (mqttMode) {
            mqttWake.configure(config.mqttBroker, config.robotId, config.userId,
                    config.mqttUsername, config.mqttPassword);
        }
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

    /**
     * 动态更新MQTT Broker配置。如果broker为空则关闭MQTT切回长连接模式；
     * 如果broker变化则断开旧连接重新连接。
     */
    public void updateMqttBroker(String brokerUrl, String username, String password) {
        String b = brokerUrl == null ? "" : brokerUrl.trim();
        String u = username == null ? "" : username;
        String p = password == null ? "" : password;
        boolean wasEnabled = mqttMode;
        boolean nowEnabled = !b.isEmpty();
        if (wasEnabled && !nowEnabled) {
            mqttWake.stop();
            mqttMode = false;
            currentMqttBroker = "";
            currentMqttUsername = "";
            currentMqttPassword = "";
            Log.i(TAG, "MQTT disabled, switching to long-connect mode");
            return;
        }
        if (!nowEnabled) return;
        boolean brokerChanged = !b.equals(currentMqttBroker) || !u.equals(currentMqttUsername) || !p.equals(currentMqttPassword);
        currentMqttBroker = b;
        currentMqttUsername = u;
        currentMqttPassword = p;
        mqttMode = true;
        mqttWake.configure(b, api.getRobotId(), api.getUserId(), u, p);
        if (brokerChanged && wasEnabled) {
            mqttWake.stop();
        }
        String rid = api.getRobotId();
        String uid = api.getUserId();
        if (rid != null && !rid.isEmpty() && !"default".equals(rid)) {
            mqttWake.start();
        }
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

    public boolean isMqttEnabled() {
        return mqttMode;
    }

    public boolean isMqttConnected() {
        return mqttWake.isConnected();
    }

    public void setVoiceEngine(VoiceEngine engine) {
        this.engineRef = engine;
    }

    private String ensureSession() throws IOException {
        if (!sessionId.isEmpty()) return sessionId;
        synchronized (sessionLock) {
            if (!sessionId.isEmpty()) return sessionId;
            PophieApiClient.SessionInfo session = api.newSession();
            sessionId = session.sessionId;
            return sessionId;
        }
    }

    public void shutdown() {
        disconnect();
        chatExecutor.shutdownNow();
        logExecutor.shutdownNow();
        notifyTestExecutor.shutdownNow();
        mqttPullExecutor.shutdownNow();
        mqttWake.stop();
    }

    public void connectRealtime() {
        if (!realtimeEnabled) return;
        postSttConnecting();
        if (!mqttMode) {
            connectReplyNotify();
        }
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
                        logExecutor.execute(() -> {
                            try {
                                api.patchVoiceSegmentStt(patchId, finalText);
                            } catch (Exception ignored) {
                            }
                        });
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
        playbackCount.set(0);
        VoiceEngine e = engineRef;
        if (e != null) e.setMuted(false);
        utteranceOpen = false;
        sttStrongFrames = 0;
        pendingSttPartial = "";
        pendingSttFinal = "";
        pendingSttPatchLogId = 0;
    }

    public void bindDevice() {
        new Thread(() -> {
            try {
                if (config.deviceId == null || config.deviceId.isEmpty()) {
                    throw new IOException("device_id 未配置");
                }
                PophieApiClient.BindInfo info = api.bindDevice();
                if (mqttMode) {
                    mqttWake.configure(currentMqttBroker, info.robotId, info.userId,
                            currentMqttUsername, currentMqttPassword);
                    mqttWake.updateRobotUser(info.robotId, info.userId);
                    mqttWake.start();
                } else {
                    connectReplyNotify();
                }
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

    public void fetchUserProfile(ProfileCallback callback) {
        String uid = api.getUserId();
        if (uid == null || uid.isEmpty() || "demo".equals(uid)) {
            main.post(() -> callback.onError("请先绑定设备"));
            return;
        }
        new Thread(() -> {
            try {
                PophieApiClient.UserProfileResult result = api.getUserProfile(uid);
                main.post(() -> callback.onUserProfile(result));
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public void updateUserProfile(String nickname, String gender, String birthday, String avatarUrl,
                                   ProfileCallback callback) {
        String uid = api.getUserId();
        if (uid == null || uid.isEmpty() || "demo".equals(uid)) {
            main.post(() -> callback.onError("请先绑定设备"));
            return;
        }
        new Thread(() -> {
            try {
                PophieApiClient.UserProfileResult result = api.updateUserProfile(uid, nickname, gender, birthday, avatarUrl);
                main.post(() -> callback.onUserProfile(result));
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public void fetchRobotConfig(ProfileCallback callback) {
        String rid = api.getRobotId();
        if (rid == null || rid.isEmpty() || "default".equals(rid)) {
            main.post(() -> callback.onError("请先绑定设备"));
            return;
        }
        new Thread(() -> {
            try {
                PophieApiClient.RobotConfigResult result = api.getRobotConfig(rid);
                main.post(() -> callback.onRobotConfig(result));
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public void updateRobotConfig(String displayName, String persona, String voiceId, String voiceStyle,
                                   String greeting, String avatarUrl, ProfileCallback callback) {
        String rid = api.getRobotId();
        if (rid == null || rid.isEmpty() || "default".equals(rid)) {
            main.post(() -> callback.onError("请先绑定设备"));
            return;
        }
        new Thread(() -> {
            try {
                PophieApiClient.RobotConfigResult result = api.updateRobotConfig(rid, displayName, persona,
                        voiceId, voiceStyle, greeting, avatarUrl);
                main.post(() -> callback.onRobotConfig(result));
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public interface ProfileCallback {
        default void onUserProfile(PophieApiClient.UserProfileResult profile) {}
        default void onRobotConfig(PophieApiClient.RobotConfigResult config) {}
        void onError(String error);
    }

    public void testConnection() {
        new Thread(() -> {
            try {
                PophieApiClient.HealthInfo health = api.checkHealth();
                if (config.deviceId != null && !config.deviceId.isEmpty()) {
                    api.bindDevice();
                }
                PophieApiClient.SessionInfo session = api.newSession();
                sessionId = session.sessionId;
                if (mqttMode) {
                    mqttWake.configure(currentMqttBroker, session.robotId, session.userId,
                            currentMqttUsername, currentMqttPassword);
                    mqttWake.updateRobotUser(session.robotId, session.userId);
                    mqttWake.start();
                } else {
                    connectReplyNotify();
                }
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
        logExecutor.execute(() -> {
            try {
                String sttText = resolveSttTextForUpload();
                String sid = ensureSession();
                PophieApiClient.SegmentLogResult result = api.uploadVoiceSegment(
                        sid, seg, sttText, config, gate);
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
        });
    }

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

    public void connectReplyNotify() {
        if (!config.replyNotifyEnabled) return;
        if (mqttMode) return;
        ensureReplyNotifyConnected(ReplyNotifyClient.Mode.LONG_NOTIFY, 5_000);
    }

    private void triggerMqttPull() {
        synchronized (mqttPullLock) {
            if (mqttPullInFlight.get() > 0) {
                mqttPullPending.set(true);
                Log.d(TAG, "mqtt pull already in flight, mark pending");
                return;
            }
            mqttPullInFlight.set(1);
        }
        mqttPullExecutor.execute(this::runPullLoop);
    }

    private void runPullLoop() {
        boolean more;
        do {
            mqttPullPending.set(false);
            more = false;
            boolean connected = false;
            try {
                Log.i(TAG, "mqtt wake: connecting pull websocket");
                connected = ensureReplyNotifyConnected(ReplyNotifyClient.Mode.PULL, 8_000);
                if (!connected) {
                    Log.w(TAG, "mqtt pull connect failed");
                } else {
                    long deadline = System.currentTimeMillis() + 30_000;
                    while (!replyNotify.isPullComplete() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    Log.i(TAG, "mqtt pull complete, closing");
                }
            } catch (Exception e) {
                Log.w(TAG, "mqtt pull error: " + e.getMessage());
            } finally {
                replyNotify.close();
            }
            synchronized (mqttPullLock) {
                more = mqttPullPending.get();
                if (more) {
                    mqttPullPending.set(false);
                } else {
                    mqttPullInFlight.set(0);
                }
            }
        } while (more);
    }

    private boolean ensureReplyNotifyConnected(ReplyNotifyClient.Mode mode, long readyTimeoutMs) {
        return replyNotify.connectIfNeeded(
                api.getBaseUrl(), api.getRobotId(), api.getUserId(), sessionId,
                mode, createReplyNotifyHandler(), readyTimeoutMs);
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
                String sid = ensureSession();
                PophieApiClient.ChatStreamResult result = api.chatStream(
                        sid, wav, seg.sampleRate,
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
                if (mqttMode) {
                    synchronized (mqttPullLock) {
                        if (mqttPullInFlight.get() > 0) {
                            throw new IOException("正在拉取消息，请稍候再试");
                        }
                        mqttPullInFlight.set(1);
                    }
                    try {
                        boolean ok = ensureReplyNotifyConnected(ReplyNotifyClient.Mode.PULL, 5_000);
                        if (!ok) {
                            throw new IOException("pull连接失败，请检查MQTT和网络");
                        }
                        int n = ++testPushCounter;
                        api.testReplyPush("测试语音 " + n);
                        main.post(() -> {
                            VoiceServerListener l = listener;
                            if (l != null) l.onReplyNotify("test_sent", "已请求测试推送 #" + n, "test");
                        });
                        long deadline = System.currentTimeMillis() + 15_000;
                        while (!replyNotify.isPullComplete() && System.currentTimeMillis() < deadline) {
                            try { Thread.sleep(200); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt(); break;
                            }
                        }
                    } finally {
                        replyNotify.close();
                        boolean retrigger;
                        synchronized (mqttPullLock) {
                            retrigger = mqttPullPending.get();
                            if (retrigger) {
                                mqttPullPending.set(false);
                            } else {
                                mqttPullInFlight.set(0);
                            }
                        }
                        if (retrigger) {
                            mqttPullExecutor.execute(this::runPullLoop);
                        }
                    }
                } else {
                    if (!ensureReplyNotifyConnected(ReplyNotifyClient.Mode.LONG_NOTIFY, 8_000)) {
                        throw new IOException("reply/notify 未连接，请先点「测连接」或稍候再试");
                    }
                    int n = ++testPushCounter;
                    api.testReplyPush("测试语音 " + n);
                    main.post(() -> {
                        VoiceServerListener l = listener;
                        if (l != null) l.onReplyNotify("test_sent", "已请求测试推送 #" + n, "test");
                    });
                }
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
