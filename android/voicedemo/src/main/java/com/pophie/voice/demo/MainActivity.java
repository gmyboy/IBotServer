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

    private TextView status, speaker, meter, log, enrollHint, verifyResult;
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
        enrollHint.setText("登记主人时请朗读这句话：\n「" + ENROLL_TEXT + "」");
        verifyResult = findViewById(R.id.verifyResult);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnEnroll = findViewById(R.id.btnEnroll);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnMode = findViewById(R.id.btnMode);
        Button btnVerify = findViewById(R.id.btnVerify);
        btnVerify.setOnClickListener(v -> verifyOwner());

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
                        enrollHint.setText("⚠ 请先登记主人，朗读：\n「" + ENROLL_TEXT + "」");
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
        // 提示用户朗读固定句子
        enrollHint.setText("请朗读这句话（即将录音 4 秒）：\n「" + ENROLL_TEXT + "」");
        status.setText("登记：准备录音，请朗读提示语…");
        toast("请朗读提示语，准备录音");
        new Thread(() -> {
            try {
                engine.stop();      // 避免与运行时麦克风冲突
                Thread.sleep(800);  // 给用户一点准备时间
                ui.post(() -> status.setText("登记录音中(4s)…请朗读提示语"));
                // 走与运行时一致的采集路径登记，提升匹配率
                int n = engine.enrollOwnerFromMic(4000);
                ui.post(() -> {
                    toast("主人登记完成（" + (n / SR) + "s）");
                    status.setText("状态：已登记主人，可点【开始】或【自检】");
                    enrollHint.setText("✓ 已登记主人。\n登记用语：「" + ENROLL_TEXT + "」");
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
                ui.post(() -> {
                    if (c < 0) {
                        verifyResult.setText("自检：尚未登记主人");
                    } else {
                        verifyResult.setText(String.format(java.util.Locale.ROOT,
                                "自检 cosine=%.3f（当前阈值 0.5，≥阈值即判主人）", c));
                    }
                    status.setText("状态：自检完成");
                });
            } catch (Throwable t) {
                Log.e(TAG, "verify failed", t);
                ui.post(() -> toast("自检失败：" + t.getMessage()));
            }
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
