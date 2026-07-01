package com.pophie.voice.demo;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.pophie.voice.GateMode;
import com.pophie.voice.SpeakerInfo;
import com.pophie.voice.VoiceConfig;
import com.pophie.voice.VoiceEngine;
import com.pophie.voice.VoiceError;
import com.pophie.voice.VoiceListener;
import com.pophie.voice.VoiceSegment;
import com.pophie.voice.WavUtil;
import com.pophie.voice.server.VoiceServerBridge;
import com.pophie.voice.server.VoiceServerConfig;
import com.pophie.voice.server.VoiceServerListener;

/**
 * 语音 SDK Demo：端侧 {@link VoiceEngine} + {@link VoiceServerBridge}（实时 STT / chat/stream）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceDemo";
    private static final int SR = 16000;
    private static final String PREFS = "voice_demo";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SESSION_ID = "session_id";

    private static final String ENROLL_TEXT = "你好，我是你的主人，以后主要由我来跟你说话，记住我的声音就好";

    private VoiceEngine engine;
    private GateMode mode = GateMode.REPORT;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private VoiceServerBridge serverBridge;

    private TextView status, speaker, meter, log, enrollHint, verifyResult, cohortInfo;
    private TextView serverStatus, sttResult, replyResult;
    private EditText serverUrl;
    private SwitchMaterial switchRealtimeStt;
    private SwitchMaterial switchChat;
    private volatile boolean enrolling = false;
    private long bytes = 0;
    private final StringBuilder logBuf = new StringBuilder();
    private final StringBuilder chatReplyBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        speaker = findViewById(R.id.speaker);
        meter = findViewById(R.id.meter);
        log = findViewById(R.id.log);
        enrollHint = findViewById(R.id.enrollHint);
        enrollHint.setText("登记主人：请连续说约 8 秒（几句话，可把下面这句读两遍），系统会自动算阈值，只需一次：\n「" + ENROLL_TEXT + "」");
        verifyResult = findViewById(R.id.verifyResult);
        cohortInfo = findViewById(R.id.cohortInfo);
        serverUrl = findViewById(R.id.serverUrl);
        switchRealtimeStt = findViewById(R.id.switchRealtimeStt);
        switchChat = findViewById(R.id.switchChat);
        serverStatus = findViewById(R.id.serverStatus);
        sttResult = findViewById(R.id.sttResult);
        replyResult = findViewById(R.id.replyResult);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_SERVER_URL, "http://192.168.23.156:9901/");
        String sessionId = prefs.getString(KEY_SESSION_ID, "");

        serverUrl.setText(savedUrl);
        initServerBridge(savedUrl, sessionId);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnEnroll = findViewById(R.id.btnEnroll);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnMode = findViewById(R.id.btnMode);
        Button btnVerify = findViewById(R.id.btnVerify);
        Button btnCohort = findViewById(R.id.btnCohort);
        Button btnTestServer = findViewById(R.id.btnTestServer);
        btnVerify.setOnClickListener(v -> verifyOwner());
        btnCohort.setOnClickListener(v -> addCohort());
        btnTestServer.setOnClickListener(v -> testServerConnection());
        findViewById(R.id.btnTestReply).setOnClickListener(v -> testReplyPush());

        serverUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) persistServerUrl();
        });

        switchRealtimeStt.setOnCheckedChangeListener((btn, checked) -> {
            if (serverBridge != null) serverBridge.setRealtimeEnabled(checked);
        });
        switchChat.setOnCheckedChangeListener((btn, checked) -> {
            if (serverBridge != null) serverBridge.setChatEnabled(checked);
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        buildEngine();
        refreshCohortInfo();

        btnStart.setOnClickListener(v -> {
            if (enrolling) { toast("正在登记主人，请稍候"); return; }
            persistServerUrl();
            status.setText("状态：检查是否已登记主人…");
            new Thread(() -> {
                boolean enrolled = engine.isOwnerEnrolled();
                ui.post(() -> {
                    if (!enrolled) {
                        toast("尚未登记主人，请先点【登记主人】");
                        status.setText("状态：未登记主人");
                        enrollHint.setText("⚠ 请先登记主人（连续说约 8 秒）：\n「" + ENROLL_TEXT + "」");
                        return;
                    }
                    bytes = 0;
                    syncServerFlags();
                    serverBridge.connectReplyNotify();
                    if (switchRealtimeStt.isChecked()) {
                        serverBridge.connectRealtime();
                    }
                    engine.start();
                    status.setText("状态：运行中");
                });
            }).start();
        });
        btnStop.setOnClickListener(v -> {
            engine.stop();
            serverBridge.disconnect();
            status.setText("状态：已停止");
        });
        btnEnroll.setOnClickListener(v -> enrollOwner());
        btnClear.setOnClickListener(v -> {
            new Thread(() -> {
                engine.clearSpeakers();
                ui.post(() -> toast("已清空声纹"));
            }).start();
        });
        btnMode.setOnClickListener(v -> {
            mode = (mode == GateMode.REPORT) ? GateMode.OWNER_ONLY : GateMode.REPORT;
            ((Button) v).setText("门控：" + mode + "（点击切换）");
            serverBridge.setGateMode(mode);
            engine.stop();
            serverBridge.disconnect();
            buildEngine();
            toast("已切换为 " + mode + "，请重新开始");
            status.setText("状态：未启动");
        });
    }

    private void initServerBridge(String baseUrl, String sessionId) {
        VoiceServerConfig config = new VoiceServerConfig.Builder()
                .baseUrl(baseUrl)
                .build();
        serverBridge = new VoiceServerBridge(config);
        serverBridge.setSessionId(sessionId);
        serverBridge.setGateMode(mode);
        serverBridge.setListener(new VoiceServerListener() {
            @Override
            public void onSttConnecting() {
                sttResult.setText("STT：连接中…");
            }

            @Override
            public void onSttReady() {
                sttResult.setText("STT：等待说话…");
            }

            @Override
            public void onSttPartial(String text) {
                sttResult.setText("STT：" + text);
            }

            @Override
            public void onSttFinal(String text) {
                String line = text.isEmpty() ? "（空）" : text;
                sttResult.setText("STT：" + line + " ✓");
                appendSttLog("final→" + line);
            }

            @Override
            public void onSttError(String message) {
                sttResult.setText("STT：错误 " + message);
                toast("实时 STT：" + message);
            }

            @Override
            public void onReplyNotify(String phase, String text, String source) {
                if ("start".equals(phase)) {
                    chatReplyBuf.setLength(0);
                    replyResult.setText("回复：生成中…");
                } else if ("speak".equals(phase) && text != null && !text.isEmpty()) {
                    chatReplyBuf.append(text);
                    replyResult.setText("回复：" + chatReplyBuf + " 🔊");
                } else if ("done".equals(phase)) {
                    appendSttLog("notify[" + source + "]→" + chatReplyBuf);
                }
            }

            @Override
            public void onReplyPlayStart(int seq) {
                replyResult.setText("回复：" + chatReplyBuf + " ▶播放中");
            }

            @Override
            public void onReplyPlayEnd(int seq) {
                String line = chatReplyBuf.length() == 0 ? "（静默）" : chatReplyBuf.toString();
                replyResult.setText("回复：" + line);
            }

            @Override
            public void onChatComplete(String replyText) {
                if (chatReplyBuf.length() == 0 && replyText != null && !replyText.isEmpty()) {
                    chatReplyBuf.append(replyText);
                }
                String line = chatReplyBuf.length() == 0 ? "（静默）" : chatReplyBuf.toString();
                replyResult.setText("回复：" + line + " …");
                appendSttLog("chat→" + line);
                persistSessionId(serverBridge.getSessionId());
            }

            @Override
            public void onChatError(String message) {
                replyResult.setText("回复：失败 " + message);
                appendSttLog("chat ✗ " + message);
            }

            @Override
            public void onChatSkipped(String reason) {
                appendSttLog("chat 跳过：" + reason);
            }

            @Override
            public void onConnectionTested(boolean speechEnabled, String sid, String error) {
                if (error != null) {
                    serverStatus.setText("服务：失败 " + error);
                    toast("连接失败：" + error);
                    return;
                }
                persistSessionId(sid);
                String speech = speechEnabled ? "speech=开" : "speech=关";
                serverStatus.setText("服务：OK  " + speech + "  session=" + sid);
                toast(speechEnabled ? "连接正常" : "speech 未启用");
            }

            @Override
            public void onSegmentLogged(long logId, boolean isOwner, String sttText) {
                String owner = isOwner ? "主人" : "非主人";
                appendSttLog("流水#" + logId + " " + owner
                        + (sttText != null && !sttText.isEmpty() ? " →" + sttText : ""));
            }

            @Override
            public void onSegmentLogError(String message) {
                appendSttLog("流水✗ " + message);
            }
        });
        syncServerFlags();
    }

    private void syncServerFlags() {
        serverBridge.setRealtimeEnabled(switchRealtimeStt.isChecked());
        serverBridge.setChatEnabled(switchChat.isChecked());
    }

    private void persistServerUrl() {
        String url = serverUrl.getText().toString().trim();
        serverBridge.setBaseUrl(url);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER_URL, url).apply();
    }

    private void persistSessionId(String id) {
        serverBridge.setSessionId(id == null ? "" : id);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_SESSION_ID, serverBridge.getSessionId())
                .apply();
    }

    private void testServerConnection() {
        persistServerUrl();
        serverStatus.setText("服务：检测中…");
        serverBridge.connectReplyNotify();
        serverBridge.testConnection();
    }

    private void testReplyPush() {
        persistServerUrl();
        replyResult.setText("回复：等待测试推送…");
        serverBridge.testReplyPush();
    }

    private void appendSttLog(String line) {
        logBuf.insert(0, line + "\n");
        if (logBuf.length() > 4000) logBuf.setLength(4000);
        log.setText(logBuf.toString());
    }

    private void buildEngine() {
        VoiceConfig cfg = new VoiceConfig.Builder()
                .sampleRate(SR)
                .gateMode(mode)
                .enableSystemDenoise(true)
                .enableAec(true)
                .vadThreshold(0.68f)
                .maxSilenceMs(400)
                .minSpeechMs(120)
                .build();
        engine = new VoiceEngine(this, cfg);
        VoiceListener serverListener = serverBridge.createVoiceListener();
        engine.setListener(new VoiceListener() {
            @Override
            public void onSpeakingStateChanged(boolean speaking) {
                serverListener.onSpeakingStateChanged(speaking);
                status.setText("状态：" + (speaking ? "有人说话（近场 VAD）" : "静音"));
            }

            @Override
            public void onAudioFrameSync(short[] pcm16, int sampleRate) {
                serverListener.onAudioFrameSync(pcm16, sampleRate);
            }

            @Override
            public void onAudioFrame(short[] pcm16, float[] pcmFloat, int sampleRate, SpeakerInfo spk) {
                bytes += (long) pcm16.length * 2;
                double rms = WavUtil.rms(pcm16);
                String near = serverBridge.isNearField(rms) ? " ✓近场" : "";
                meter.setText(String.format(java.util.Locale.ROOT,
                        "输出字节：%d  RMS=%.0f%s", bytes, rms, near));
            }

            @Override
            public void onSpeakerUpdated(SpeakerInfo spk) {
                speaker.setText("说话人：" + spk.state
                        + (spk.name != null ? " " + spk.name : "")
                        + String.format(java.util.Locale.ROOT, " conf=%.2f raw=%.2f margin=%.2f",
                                spk.confidence, spk.rawScore, spk.margin)
                        + (spk.isOwner ? " [主人]" : ""));
            }

            @Override
            public void onSegmentEnd(VoiceSegment seg) {
                if (switchChat.isChecked() && seg.durationMs >= 300) {
                    chatReplyBuf.setLength(0);
                    replyResult.setText("回复：…");
                }
                serverListener.onSegmentEnd(seg);
                String spkLabel = "—";
                if (seg.speaker != null) {
                    spkLabel = seg.speaker.name != null ? seg.speaker.name : seg.speaker.state.toString();
                }
                String line = String.format(java.util.Locale.ROOT,
                        "段 %dms 说话人=%s conf=%.2f owner=%s\n",
                        seg.durationMs,
                        spkLabel,
                        seg.speaker != null ? seg.speaker.confidence : 0f,
                        seg.speaker != null && seg.speaker.isOwner);
                logBuf.insert(0, line);
                if (logBuf.length() > 4000) logBuf.setLength(4000);
                log.setText(logBuf.toString());
            }

            @Override
            public void onError(VoiceError e) {
                toast("错误：" + e);
                status.setText("状态：错误 " + e.code);
            }
        });
    }

    private void enrollOwner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toast("缺少录音权限");
            return;
        }
        if (enrolling) return;
        enrolling = true;
        enrollHint.setText("请连续说约 8 秒（几句话，可把下面这句读两遍）：\n「" + ENROLL_TEXT + "」");
        status.setText("登记：准备录音，请连续说话…");
        toast("请连续说约 8 秒，准备录音");
        new Thread(() -> {
            try {
                engine.stop();
                serverBridge.disconnect();
                Thread.sleep(800);
                ui.post(() -> status.setText("登记录音中(8s)…请连续说话"));
                double thr = engine.enrollOwnerFromMic(8000);
                ui.post(() -> {
                    toast(String.format(java.util.Locale.ROOT, "登记完成，自动阈值=%.3f", thr));
                    status.setText("状态：已登记主人，可点【开始】");
                    enrollHint.setText("✓ 已登记主人。自动阈值=" + String.format(java.util.Locale.ROOT, "%.3f", thr)
                            + "\n登记用语：「" + ENROLL_TEXT + "」");
                    verifyResult.setText("自检：—（可点【自检相似度】验证）");
                    refreshCohortInfo();
                });
            } catch (Throwable t) {
                Log.e(TAG, "enroll failed", t);
                ui.post(() -> {
                    toast("登记失败：" + t.getMessage());
                    status.setText("状态：登记失败");
                });
            } finally {
                enrolling = false;
            }
        }).start();
    }

    private void verifyOwner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toast("缺少录音权限");
            return;
        }
        if (enrolling) { toast("正在登记，请稍候"); return; }
        status.setText("自检：录音 2.5s，请正常说一句话…");
        toast("请正常说一句话(2.5s)");
        new Thread(() -> {
            try {
                engine.stop();
                serverBridge.disconnect();
                float c = engine.verifyOwnerFromMic(2500);
                float th = engine.ownerSelfThreshold();
                boolean as = engine.asnormActive();
                ui.post(() -> {
                    if (Float.isNaN(c)) {
                        verifyResult.setText("自检：尚未登记主人");
                    } else {
                        boolean pass = c >= th;
                        String kind = as ? "AS-Norm分" : "cosine";
                        verifyResult.setText(String.format(java.util.Locale.ROOT,
                                "自检 %s=%.3f  阈值=%.3f  → %s", kind, c, th, pass ? "判为主人 ✓" : "判为非主人 ✗"));
                    }
                    status.setText("状态：自检完成");
                });
            } catch (Throwable t) {
                Log.e(TAG, "verify failed", t);
                ui.post(() -> toast("自检失败：" + t.getMessage()));
            }
        }).start();
    }

    private void addCohort() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toast("缺少录音权限");
            return;
        }
        if (enrolling) { toast("正在登记，请稍候"); return; }
        status.setText("录入背景人声：录音 2.5s，请让【其他人】说一句…");
        toast("请让其他人说一句(2.5s)");
        new Thread(() -> {
            try {
                engine.stop();
                serverBridge.disconnect();
                int n = engine.addCohortFromMic(2500);
                ui.post(() -> {
                    toast("已录入背景人声，cohort=" + n);
                    refreshCohortInfo();
                });
            } catch (Throwable t) {
                Log.e(TAG, "cohort failed", t);
                ui.post(() -> toast("录入失败：" + t.getMessage()));
            }
        }).start();
    }

    private void refreshCohortInfo() {
        if (engine == null) return;
        new Thread(() -> {
            int n = engine.cohortSize();
            boolean as = engine.asnormActive();
            boolean owner = engine.isOwnerEnrolled();
            String extra;
            if (as) extra = "（AS-Norm 已生效）";
            else if (n >= 8 && owner) extra = "（cohort 足够，请【重新登记主人】以启用 AS-Norm）";
            else extra = "（需 ≥8 个不同人，且在录完 cohort 后登记主人才生效）";
            ui.post(() -> cohortInfo.setText("cohort：" + n + extra));
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stop();
        if (serverBridge != null) serverBridge.disconnect();
    }
}
