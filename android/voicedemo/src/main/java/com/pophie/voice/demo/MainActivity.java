package com.pophie.voice.demo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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

import java.util.Collections;

/** 语音 SDK 调试 Demo。 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceDemo";
    private static final int SR = 16000;

    private VoiceEngine engine;
    private GateMode mode = GateMode.REPORT;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, speaker, meter, log;
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

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnEnroll = findViewById(R.id.btnEnroll);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnMode = findViewById(R.id.btnMode);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        buildEngine();

        btnStart.setOnClickListener(v -> {
            bytes = 0;
            engine.start();
            status.setText("状态：运行中");
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
                        + String.format(java.util.Locale.ROOT, " conf=%.2f margin=%.2f", spk.confidence, spk.margin)
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
        toast("开始录制 4 秒主人语音…");
        new Thread(() -> {
            try {
                short[] pcm = record(4000);
                engine.enrollOwner(Collections.singletonList(pcm));
                ui.post(() -> toast("主人登记完成（" + (pcm.length / SR) + "s）"));
            } catch (Throwable t) {
                Log.e(TAG, "enroll failed", t);
                ui.post(() -> toast("登记失败：" + t.getMessage()));
            }
        }).start();
    }

    @SuppressLint("MissingPermission")
    private short[] record(int ms) {
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, SR));
        int total = SR * ms / 1000;
        short[] out = new short[total];
        r.startRecording();
        int off = 0;
        while (off < total) {
            int n = r.read(out, off, total - off);
            if (n <= 0) break;
            off += n;
        }
        r.stop();
        r.release();
        if (off == total) return out;
        short[] trimmed = new short[off];
        System.arraycopy(out, 0, trimmed, 0, off);
        return trimmed;
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
