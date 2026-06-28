package com.pophie.voice.source;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

/**
 * 在 AudioRecord 会话上挂载系统音效：NoiseSuppressor(降噪) / AEC(回声消除) / AGC(自动增益)。
 * 效果因机型而异；不支持时静默跳过。
 */
public final class AudioFxController {

    private static final String TAG = "PophieVoiceFx";

    private NoiseSuppressor ns;
    private AcousticEchoCanceler aec;
    private AutomaticGainControl agc;

    public void attach(int sessionId, boolean denoise, boolean aecOn, boolean agcOn) {
        try {
            if (denoise && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "NoiseSuppressor 不可用: " + t.getMessage());
        }
        try {
            if (aecOn && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) aec.setEnabled(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "AEC 不可用: " + t.getMessage());
        }
        try {
            if (agcOn && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) agc.setEnabled(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "AGC 不可用: " + t.getMessage());
        }
        Log.d(TAG, "audiofx attached ns=" + (ns != null) + " aec=" + (aec != null) + " agc=" + (agc != null));
    }

    public void release() {
        if (ns != null) { try { ns.release(); } catch (Throwable ignored) {} ns = null; }
        if (aec != null) { try { aec.release(); } catch (Throwable ignored) {} aec = null; }
        if (agc != null) { try { agc.release(); } catch (Throwable ignored) {} agc = null; }
    }
}
