package com.pophie.voice.demo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.pophie.voice.GateMode;
import com.pophie.voice.SpeakerInfo;
import com.pophie.voice.VoiceConfig;
import com.pophie.voice.VoiceEngine;
import com.pophie.voice.VoiceError;
import com.pophie.voice.VoiceListener;
import com.pophie.voice.VoiceSegment;

/** 语音 SDK 调试 Demo。 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceDemo";
    private static final int SR = 16000;

    /** 登记主人时让用户朗读的固定提示语。 */
    private static final String ENROLL_TEXT = "你好，我是你的主人，以后主要由我来跟你说话，记住我的声音就好";

    private VoiceEngine engine;
    private GateMode mode = GateMode.REPORT;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, speaker, meter, log, enrollHint, verifyResult, cohortInfo;
    private volatile boolean enrolling = false;
    private long bytes = 0;
    private final StringBuilder logBuf = new StringBuilder();

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

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnEnroll = findViewById(R.id.btnEnroll);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnMode = findViewById(R.id.btnMode);
        Button btnVerify = findViewById(R.id.btnVerify);
        Button btnCohort = findViewById(R.id.btnCohort);
        btnVerify.setOnClickListener(v -> verifyOwner());
        btnCohort.setOnClickListener(v -> addCohort());
        refreshCohortInfo();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        buildEngine();

        btnStart.setOnClickListener(v -> {
            if (enrolling) { toast("正在登记主人，请稍候"); return; }
            status.setText("状态：检查是否已登记主人…");
            // isOwnerEnrolled() 可能触发模型下载，放后台线程
            new Thread(() -> {
                boolean enrolled = engine.isOwnerEnrolled();
                ui.post(() -> {
                    if (!enrolled) {
                        toast("尚未登记主人，请先点【登记主人(4s)】并朗读提示语");
                        status.setText("状态：未登记主人");
                        enrollHint.setText("⚠ 请先登记主人（连续说约 8 秒）：\n「" + ENROLL_TEXT + "」");
                        return;
                    }
                    bytes = 0;
                    engine.start();
                    status.setText("状态：运行中");
                });
            }).start();
        });
        btnStop.setOnClickListener(v -> {
            engine.stop();
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
            engine.stop();
            buildEngine();
            toast("已切换为 " + mode + "，请重新开始");
            status.setText("状态：未启动");
        });
    }

    private void buildEngine() {
        VoiceConfig cfg = new VoiceConfig.Builder()
                .sampleRate(SR)
                .gateMode(mode)
                .enableSystemDenoise(true)
                .enableAec(true)
                .build();
        engine = new VoiceEngine(this, cfg);
        engine.setListener(new VoiceListener() {
            @Override
            public void onSpeakingStateChanged(boolean speaking) {
                status.setText("状态：" + (speaking ? "有人说话" : "静音"));
            }

            @Override
            public void onAudioFrame(short[] pcm16, float[] pcmFloat, int sampleRate, SpeakerInfo spk) {
                bytes += (long) pcm16.length * 2;
                meter.setText("输出字节：" + bytes);
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
                String line = String.format(java.util.Locale.ROOT,
                        "段 %dms 说话人=%s conf=%.2f owner=%s\n",
                        seg.durationMs,
                        seg.speaker != null && seg.speaker.name != null ? seg.speaker.name : seg.speaker.state.toString(),
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
                engine.stop();      // 避免与运行时麦克风冲突
                Thread.sleep(800);  // 给用户一点准备时间
                ui.post(() -> status.setText("登记录音中(8s)…请连续说话"));
                // 走与运行时一致的采集路径登记 + 自动标定阈值
                double thr = engine.enrollOwnerFromMic(8000);
                ui.post(() -> {
                    toast(String.format(java.util.Locale.ROOT, "登记完成，自动阈值=%.3f", thr));
                    status.setText("状态：已登记主人，可点【开始】或【自检】");
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

    /** 自检：录 2.5s，显示与主人的原始 cosine，便于标定阈值。 */
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

    /** 录入一条背景人声(其他人)，构建 cohort。 */
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
    }
}
