package com.pophie.voice.source;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 内置麦克风采集：AudioRecord 16k/mono/PCM16，后台线程按固定帧长产出帧；
 * 挂载系统 NoiseSuppressor/AEC/AGC（输出路降噪）。
 */
public final class MicSource implements AudioSource {

    private static final String TAG = "PophieVoiceMic";

    private final Context context;
    private final int sampleRate;
    private final int frameSamples;
    private final boolean denoise;
    private final boolean aec;
    private final boolean agc;

    private AudioRecord recorder;
    private AudioFxController fx;
    private volatile boolean running = false;
    private Thread thread;
    private int sessionId = 0;

    public MicSource(Context context, int sampleRate, int frameSamples,
                     boolean denoise, boolean aec, boolean agc) {
        this.context = context.getApplicationContext();
        this.sampleRate = sampleRate;
        this.frameSamples = frameSamples;
        this.denoise = denoise;
        this.aec = aec;
        this.agc = agc;
    }

    @SuppressLint("MissingPermission") // 由上层在 start() 前确保 RECORD_AUDIO
    @Override
    public boolean start(FrameCallback callback) {
        if (running) return true;
        int minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return false;
        int bufBytes = Math.max(minBuf * 2, frameSamples * 2 * 4);

        try {
            // VOICE_COMMUNICATION 会启用平台前处理（含 AEC），更适合人声采集
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                // 回退到 MIC
                recorder.release();
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            }
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord 创建失败: " + t.getMessage());
            return false;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            return false;
        }

        sessionId = recorder.getAudioSessionId();
        fx = new AudioFxController();
        fx.attach(sessionId, denoise, aec, agc);

        running = true;
        recorder.startRecording();
        thread = new Thread(() -> readLoop(callback), "pophie-mic");
        thread.start();
        return true;
    }

    private void readLoop(FrameCallback callback) {
        short[] frame = new short[frameSamples];
        while (running) {
            int off = 0;
            while (off < frameSamples && running) {
                int read = recorder.read(frame, off, frameSamples - off);
                if (read <= 0) break;
                off += read;
            }
            if (off == frameSamples) {
                short[] copy = new short[frameSamples];
                System.arraycopy(frame, 0, copy, 0, frameSamples);
                callback.onFrame(copy);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {}
            thread = null;
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (fx != null) { fx.release(); fx = null; }
        RouteHelper.restoreAfterRecording(context);
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int audioSessionId() { return sessionId; }
}
