package com.pophie.voice.demo;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
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
import com.pophie.voice.model.ModelDownloadListener;
import com.pophie.voice.server.PophieApiClient;
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
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_MQTT_BROKER = "mqtt_broker";

    private static final String ENROLL_TEXT = "你好，我是你的主人，以后主要由我来跟你说话，记住我的声音就好";

    private VoiceEngine engine;
    private GateMode mode = GateMode.REPORT;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private VoiceServerBridge serverBridge;

    private TextView status, speaker, meter, log, enrollHint, verifyResult, cohortInfo;
    private TextView serverStatus, sttResult, replyResult, deviceBindInfo;
    private TextView tvUserInfo, tvRobotInfo, timingInfo, mqttStatus;
    private EditText serverUrl, mqttBrokerUrl;
    private EditText etUserNickname, etUserGender, etUserBirthday;
    private EditText etRobotName, etRobotPersona, etRobotGreeting;
    private SwitchMaterial switchRealtimeStt;
    private SwitchMaterial switchChat;
    private static final int REQ_RECORD_AUDIO = 1;

    private enum PendingAudioAction { NONE, ENROLL, VERIFY, COHORT, START }

    private PendingAudioAction pendingAudioAction = PendingAudioAction.NONE;
    private volatile boolean enrolling = false;
    private long bytes = 0;
    private final StringBuilder logBuf = new StringBuilder();
    private final StringBuilder chatReplyBuf = new StringBuilder();

    private volatile long segEndTs;
    private volatile long sttFinalTs;
    private volatile long replyStartTs;
    private volatile long firstSpeakTs;
    private volatile long chatCompleteTs;
    private volatile long replyDoneTs;
    private volatile long replyPlayEndTs;
    private volatile long segLoggedTs;
    private volatile int currentSegSeq;
    private volatile boolean sttFinalPending;

    private void resetTiming() {
        segEndTs = 0;
        sttFinalTs = 0;
        replyStartTs = 0;
        firstSpeakTs = 0;
        chatCompleteTs = 0;
        replyDoneTs = 0;
        replyPlayEndTs = 0;
        segLoggedTs = 0;
        sttFinalPending = false;
    }

    private void ms(StringBuilder sb, String label, long ts) {
        if (ts > 0 && segEndTs > 0) {
            sb.append(label).append('=').append(ts - segEndTs).append("ms  ");
        }
    }

    private void updateTimingUi(String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏱ 段#").append(currentSegSeq).append(' ');
        ms(sb, "STT", sttFinalTs);
        ms(sb, "log上传", segLoggedTs);
        ms(sb, "reply开始", replyStartTs);
        ms(sb, "首字", firstSpeakTs);
        ms(sb, "chat完成", chatCompleteTs);
        ms(sb, "reply完成", replyDoneTs);
        ms(sb, "播放完", replyPlayEndTs);
        if (extra != null && !extra.isEmpty()) sb.append('[').append(extra).append(']');
        final String text = sb.toString();
        Log.d(TAG, text);
        ui.post(() -> {
            if (timingInfo != null) timingInfo.setText(text);
        });
    }

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
        mqttBrokerUrl = findViewById(R.id.mqttBrokerUrl);
        mqttStatus = findViewById(R.id.mqttStatus);
        switchRealtimeStt = findViewById(R.id.switchRealtimeStt);
        switchChat = findViewById(R.id.switchChat);
        serverStatus = findViewById(R.id.serverStatus);
        sttResult = findViewById(R.id.sttResult);
        replyResult = findViewById(R.id.replyResult);
        deviceBindInfo = findViewById(R.id.deviceBindInfo);
        tvUserInfo = findViewById(R.id.tvUserInfo);
        tvRobotInfo = findViewById(R.id.tvRobotInfo);
        timingInfo = findViewById(R.id.timingInfo);
        etUserNickname = findViewById(R.id.etUserNickname);
        etUserGender = findViewById(R.id.etUserGender);
        etUserBirthday = findViewById(R.id.etUserBirthday);
        etRobotName = findViewById(R.id.etRobotName);
        etRobotPersona = findViewById(R.id.etRobotPersona);
        etRobotGreeting = findViewById(R.id.etRobotGreeting);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_SERVER_URL, "http://192.168.23.156:9901/");
        String savedMqtt = prefs.getString(KEY_MQTT_BROKER, "");
        String sessionId = prefs.getString(KEY_SESSION_ID, "");

        serverUrl.setText(savedUrl);
        mqttBrokerUrl.setText(savedMqtt);
        initServerBridge(savedUrl, savedMqtt, sessionId);
        updateDeviceBindInfoLine();
        updateMqttStatusLine();

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
        findViewById(R.id.btnBindDevice).setOnClickListener(v -> bindDevice());
        findViewById(R.id.btnTestReply).setOnClickListener(v -> testReplyPush());
        findViewById(R.id.btnSaveUser).setOnClickListener(v -> saveUserProfile());
        findViewById(R.id.btnLoadUser).setOnClickListener(v -> loadUserProfile());
        findViewById(R.id.btnSaveRobot).setOnClickListener(v -> saveRobotConfig());
        findViewById(R.id.btnLoadRobot).setOnClickListener(v -> loadRobotConfig());

        serverUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) persistServerConfig();
        });
        mqttBrokerUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) persistServerConfig();
        });

        switchRealtimeStt.setOnCheckedChangeListener((btn, checked) -> {
            if (serverBridge != null) serverBridge.setRealtimeEnabled(checked);
        });
        switchChat.setOnCheckedChangeListener((btn, checked) -> {
            if (serverBridge != null) serverBridge.setChatEnabled(checked);
        });

        if (!hasRecordAudio()) {
            pendingAudioAction = PendingAudioAction.NONE;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
        } else {
            prewarmVoiceEngine();
        }

        buildEngine();
        refreshCohortInfo();

        btnStart.setOnClickListener(v -> {
            if (enrolling) { toast("正在登记主人，请稍候"); return; }
            runWithRecordAudio(PendingAudioAction.START, this::startEngineAfterEnrollCheck);
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

    private boolean hasRecordAudio() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void runWithRecordAudio(PendingAudioAction action, Runnable task) {
        if (!hasRecordAudio()) {
            pendingAudioAction = action;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
            toast("请允许录音权限");
            return;
        }
        pendingAudioAction = PendingAudioAction.NONE;
        task.run();
    }

    private void prewarmVoiceEngine() {
        if (engine == null || enrolling) return;
        new Thread(() -> {
            try {
                engine.ensureScorer();
                ui.post(this::refreshCohortInfo);
            } catch (Throwable t) {
                Log.w(TAG, "prewarm scorer failed", t);
            }
        }, "voice-prewarm").start();
    }

    private void startEngineAfterEnrollCheck() {
        persistServerConfig();
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
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prewarmVoiceEngine();
            PendingAudioAction action = pendingAudioAction;
            pendingAudioAction = PendingAudioAction.NONE;
            switch (action) {
                case ENROLL:
                    enrollOwner();
                    break;
                case VERIFY:
                    verifyOwner();
                    break;
                case COHORT:
                    addCohort();
                    break;
                case START:
                    startEngineAfterEnrollCheck();
                    break;
                default:
                    break;
            }
        } else {
            pendingAudioAction = PendingAudioAction.NONE;
            toast("需要录音权限才能登记主人");
            status.setText("状态：缺少录音权限");
        }
    }

    private void initServerBridge(String baseUrl, String mqttBroker, String sessionId) {
        String deviceId = ensureDeviceId();
        VoiceServerConfig config = new VoiceServerConfig.Builder()
                .baseUrl(baseUrl)
                .deviceId(deviceId)
                .mqttBroker(mqttBroker)
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
                long now = System.currentTimeMillis();
                if (segEndTs > 0) {
                    sttFinalTs = now;
                    updateTimingUi("stt final");
                } else {
                    sttFinalPending = true;
                }
                appendSttLog("final→" + line);
            }

            @Override
            public void onSttError(String message) {
                sttResult.setText("STT：错误 " + message);
                toast("实时 STT：" + message);
            }

            @Override
            public void onMqttStatus(boolean connected, String error) {
                updateMqttStatusLine(connected, error);
            }

            @Override
            public void onReplyNotify(String phase, String text, String source) {
                long now = System.currentTimeMillis();
                if ("start".equals(phase)) {
                    chatReplyBuf.setLength(0);
                    replyStartTs = now;
                    replyResult.setText("回复：生成中…");
                    updateTimingUi("reply start");
                } else if ("speak".equals(phase) && text != null && !text.isEmpty()) {
                    if (firstSpeakTs == 0) firstSpeakTs = now;
                    chatReplyBuf.append(text);
                    replyResult.setText("回复：" + chatReplyBuf + " 🔊");
                    updateTimingUi("speaking");
                } else if ("done".equals(phase)) {
                    replyDoneTs = now;
                    updateTimingUi("reply done");
                    String tsStr = segEndTs > 0 ? "  [" + (replyDoneTs - segEndTs) + "ms]" : "";
                    appendSttLog("notify[" + source + "]→" + chatReplyBuf + tsStr);
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
                replyPlayEndTs = System.currentTimeMillis();
                updateTimingUi("play end");
            }

            @Override
            public void onChatComplete(String replyText) {
                if (chatReplyBuf.length() == 0 && replyText != null && !replyText.isEmpty()) {
                    chatReplyBuf.append(replyText);
                }
                String line = chatReplyBuf.length() == 0 ? "（静默）" : chatReplyBuf.toString();
                replyResult.setText("回复：" + line + " …");
                chatCompleteTs = System.currentTimeMillis();
                updateTimingUi("chat complete");
                String tsStr = segEndTs > 0 ? "  [" + (chatCompleteTs - segEndTs) + "ms]" : "";
                appendSttLog("chat→" + line + tsStr);
                persistSessionId(serverBridge.getSessionId());
            }

            @Override
            public void onChatError(String message) {
                replyResult.setText("回复：失败 " + message);
                long errTs = System.currentTimeMillis();
                updateTimingUi("error: " + message);
                String tsStr = segEndTs > 0 ? "  [" + (errTs - segEndTs) + "ms]" : "";
                appendSttLog("chat ✗ " + message + tsStr);
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
                updateDeviceBindInfoLine();
                String speech = speechEnabled ? "speech=开" : "speech=关";
                serverStatus.setText("服务：OK  " + speech + "  session=" + sid);
                toast(speechEnabled ? "连接正常" : "speech 未启用");
            }

            @Override
            public void onDeviceBound(PophieApiClient.BindInfo info, String error) {
                if (error != null) {
                    deviceBindInfo.setText("设备：绑定失败 " + error);
                    toast("绑定失败：" + error);
                    return;
                }
                updateDeviceBindInfoLine(info);
                String tag = info.newUser ? "（新用户）" : info.newDevice ? "（新设备）" : "";
                toast("绑定成功" + tag);
            }

            @Override
            public void onSegmentLogged(long logId, boolean isOwner, String sttText) {
                String owner = isOwner ? "主人" : "非主人";
                segLoggedTs = System.currentTimeMillis();
                updateTimingUi("log uploaded");
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

    private String ensureDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_DEVICE_ID, "");
        if (saved != null && !saved.isEmpty()) return saved;
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String id = (androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId))
                ? "android-" + androidId
                : "dev-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        return id;
    }

    private void persistServerConfig() {
        String url = serverUrl.getText().toString().trim();
        String mqtt = mqttBrokerUrl.getText().toString().trim();
        serverBridge.setBaseUrl(url);
        serverBridge.updateMqttBroker(mqtt, "", "");
        updateMqttStatusLine();
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(KEY_SERVER_URL, url);
        editor.putString(KEY_MQTT_BROKER, mqtt);
        editor.apply();
    }

    private void updateMqttStatusLine() {
        if (serverBridge == null) return;
        if (!serverBridge.isMqttEnabled()) {
            mqttStatus.setText("MQTT：未配置（使用WebSocket长连接模式）");
            return;
        }
        if (serverBridge.isMqttConnected()) {
            mqttStatus.setText("MQTT：已连接 ✓（唤醒短连接模式）");
            mqttStatus.setTextColor(0xFF2E7D32);
        } else {
            mqttStatus.setText("MQTT：连接中…（唤醒短连接模式）");
            mqttStatus.setTextColor(0xFFEF6C00);
        }
    }

    private void updateMqttStatusLine(boolean connected, String error) {
        ui.post(() -> {
            if (!serverBridge.isMqttEnabled()) {
                mqttStatus.setText("MQTT：未配置（使用WebSocket长连接模式）");
                mqttStatus.setTextColor(0xFF757575);
                return;
            }
            if (connected) {
                mqttStatus.setText("MQTT：已连接 ✓（唤醒短连接模式）");
                mqttStatus.setTextColor(0xFF2E7D32);
            } else {
                String msg = "MQTT：断开";
                if (error != null && !error.isEmpty()) {
                    msg += " - " + error;
                }
                mqttStatus.setText(msg);
                mqttStatus.setTextColor(0xFFC62828);
            }
        });
    }

    private void persistSessionId(String id) {
        serverBridge.setSessionId(id == null ? "" : id);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_SESSION_ID, serverBridge.getSessionId())
                .apply();
    }

    private void bindDevice() {
        persistServerConfig();
        deviceBindInfo.setText("设备：绑定中…");
        serverBridge.bindDevice();
    }

    private void updateDeviceBindInfoLine() {
        if (serverBridge == null) return;
        updateDeviceBindInfoLine(null);
    }

    private void updateDeviceBindInfoLine(PophieApiClient.BindInfo info) {
        String did = serverBridge.getDeviceId();
        String uid = info != null ? info.userId : serverBridge.getBoundUserId();
        String rid = info != null ? info.robotId : serverBridge.getBoundRobotId();
        if (did == null || did.isEmpty()) {
            deviceBindInfo.setText("设备：未配置 device_id");
            return;
        }
        if ("demo".equals(uid) && "default".equals(rid)) {
            deviceBindInfo.setText("设备：" + did + "  ·  未绑定（请点「绑定设备」）");
        } else {
            deviceBindInfo.setText("设备：" + did + "\n用户：" + uid + "  ·  机器人：" + rid);
            loadUserProfile();
            loadRobotConfig();
        }
    }

    private void loadUserProfile() {
        tvUserInfo.setText("用户信息：加载中…");
        serverBridge.fetchUserProfile(new VoiceServerBridge.ProfileCallback() {
            @Override
            public void onUserProfile(PophieApiClient.UserProfileResult profile) {
                if (profile.nickname != null && !profile.nickname.isEmpty()) {
                    etUserNickname.setText(profile.nickname);
                }
                if (profile.gender != null) etUserGender.setText(profile.gender);
                if (profile.birthday != null) etUserBirthday.setText(profile.birthday);
                String info = "用户：" + profile.userId;
                if (profile.nickname != null && !profile.nickname.isEmpty()) {
                    info += " 昵称：" + profile.nickname;
                }
                if (profile.voiceEnrolled) {
                    info += " [声纹已录入]";
                }
                tvUserInfo.setText(info);
            }

            @Override
            public void onError(String error) {
                tvUserInfo.setText("用户信息：" + error);
            }
        });
    }

    private void saveUserProfile() {
        String nickname = etUserNickname.getText().toString().trim();
        String gender = etUserGender.getText().toString().trim();
        String birthday = etUserBirthday.getText().toString().trim();
        if (nickname.isEmpty()) {
            toast("请输入昵称");
            return;
        }
        tvUserInfo.setText("用户信息：保存中…");
        serverBridge.updateUserProfile(nickname, gender.isEmpty() ? null : gender,
                birthday.isEmpty() ? null : birthday, null,
                new VoiceServerBridge.ProfileCallback() {
                    @Override
                    public void onUserProfile(PophieApiClient.UserProfileResult profile) {
                        toast("用户信息已保存");
                        String info = "用户：" + profile.userId + " 昵称：" + profile.nickname;
                        if (profile.voiceEnrolled) info += " [声纹已录入]";
                        tvUserInfo.setText(info);
                    }

                    @Override
                    public void onError(String error) {
                        tvUserInfo.setText("用户信息：保存失败 " + error);
                        toast("保存失败：" + error);
                    }
                });
    }

    private void loadRobotConfig() {
        tvRobotInfo.setText("机器人配置：加载中…");
        serverBridge.fetchRobotConfig(new VoiceServerBridge.ProfileCallback() {
            @Override
            public void onRobotConfig(PophieApiClient.RobotConfigResult config) {
                if (config.displayName != null && !config.displayName.isEmpty()) {
                    etRobotName.setText(config.displayName);
                }
                if (config.persona != null) etRobotPersona.setText(config.persona);
                if (config.greeting != null) etRobotGreeting.setText(config.greeting);
                String info = "机器人：" + config.robotId;
                if (config.displayName != null && !config.displayName.isEmpty()) {
                    info += " 名称：" + config.displayName;
                }
                if (config.voiceId != null && !config.voiceId.isEmpty()) {
                    info += " 音色：" + config.voiceId;
                }
                tvRobotInfo.setText(info);
            }

            @Override
            public void onError(String error) {
                tvRobotInfo.setText("机器人配置：" + error);
            }
        });
    }

    private void saveRobotConfig() {
        String name = etRobotName.getText().toString().trim();
        String persona = etRobotPersona.getText().toString().trim();
        String greeting = etRobotGreeting.getText().toString().trim();
        if (name.isEmpty()) {
            toast("请输入机器人名称");
            return;
        }
        tvRobotInfo.setText("机器人配置：保存中…");
        serverBridge.updateRobotConfig(name, persona.isEmpty() ? null : persona,
                null, null, greeting.isEmpty() ? null : greeting, null,
                new VoiceServerBridge.ProfileCallback() {
                    @Override
                    public void onRobotConfig(PophieApiClient.RobotConfigResult config) {
                        toast("机器人配置已保存");
                        String info = "机器人：" + config.robotId + " 名称：" + config.displayName;
                        tvRobotInfo.setText(info);
                    }

                    @Override
                    public void onError(String error) {
                        tvRobotInfo.setText("机器人配置：保存失败 " + error);
                        toast("保存失败：" + error);
                    }
                });
    }

    private void testServerConnection() {
        persistServerConfig();
        serverStatus.setText("服务：检测中…");
        serverBridge.connectReplyNotify();
        serverBridge.testConnection();
    }

    private void testReplyPush() {
        persistServerConfig();
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
        serverBridge.setVoiceEngine(engine);
        engine.setModelDownloadListener(new ModelDownloadListener() {
            @Override
            public void onDownloadStart(String fileName) {
                ui.post(() -> status.setText("登记：下载模型 " + fileName + "…"));
            }

            @Override
            public void onDownloadProgress(String fileName, long bytes) {
                ui.post(() -> status.setText(String.format(java.util.Locale.ROOT,
                        "登记：下载 %s %.1fMB…", fileName, bytes / (1024.0 * 1024.0))));
            }

            @Override
            public void onDownloadDone(String fileName) {
                ui.post(() -> status.setText("登记：模型就绪 " + fileName));
            }
        });
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
                boolean sttAlreadyDone = sttFinalPending;
                resetTiming();
                segEndTs = System.currentTimeMillis();
                currentSegSeq++;
                if (sttAlreadyDone) {
                    sttFinalTs = segEndTs;
                }
                if (switchChat.isChecked() && seg.durationMs >= 300) {
                    chatReplyBuf.setLength(0);
                    replyResult.setText("回复：…");
                }
                serverListener.onSegmentEnd(seg);
                updateTimingUi("seg end");
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
        if (enrolling) return;
        runWithRecordAudio(PendingAudioAction.ENROLL, this::doEnrollOwner);
    }

    private void doEnrollOwner() {
        enrolling = true;
        enrollHint.setText("请连续说约 8 秒（几句话，可把下面这句读两遍）：\n「" + ENROLL_TEXT + "」");
        status.setText("登记：准备中…");
        toast("首次需下载声纹模型，请稍候");
        new Thread(() -> {
            try {
                engine.stop();
                serverBridge.disconnect();
                Thread.sleep(300);
                ui.post(() -> status.setText("登记：检查/加载声纹模型…"));
                engine.ensureScorer();
                ui.post(() -> {
                    status.setText("登记录音中(8s)…请连续说话");
                    toast("开始录音，请连续说话");
                });
                short[] pcm = engine.captureMicMilliseconds(8000);
                double thr = engine.enrollOwnerFromPcm(pcm);
                ui.post(() -> {
                    boolean as = engine.asnormActive();
                    String kind = as ? "AS-Norm" : "cosine";
                    toast(String.format(java.util.Locale.ROOT, "登记完成 %s 阈值=%.3f", kind, thr));
                    status.setText("状态：已登记主人，可点【开始】");
                    enrollHint.setText("✓ 已登记主人。标定=" + kind + " 阈值="
                            + String.format(java.util.Locale.ROOT, "%.3f", thr)
                            + (as ? "" : "（录 ≥3 人 cohort 可升级 AS-Norm）")
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
        runWithRecordAudio(PendingAudioAction.VERIFY, this::doVerifyOwner);
    }

    private void doVerifyOwner() {
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
        runWithRecordAudio(PendingAudioAction.COHORT, this::doAddCohort);
    }

    private void doAddCohort() {
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
            else if (n >= 3 && owner) extra = "（可【重新登记主人】启用 AS-Norm；录满 ≥8 人更准）";
            else if (n >= 3) extra = "（cohort≥3，登记主人将用 AS-Norm；推荐录满 ≥8 人）";
            else extra = "（无 cohort 时用 cosine 自标定；录 ≥3 个其他人可升级 AS-Norm）";
            ui.post(() -> cohortInfo.setText("cohort：" + n + extra));
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serverBridge != null) {
            serverBridge.disconnect();
            serverBridge.shutdown();
        }
        if (engine != null) engine.release();
    }
}
