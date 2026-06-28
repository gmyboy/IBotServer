package com.pophie.voice.source;

import android.content.Context;
import android.media.AudioManager;

/** 录音结束后恢复音频路由（退出蓝牙 SCO / 通话模式）。Java 版，自包含,不依赖 app。 */
final class RouteHelper {

    private RouteHelper() {}

    static void restoreAfterRecording(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            try {
                if (am.isBluetoothScoOn()) {
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                }
            } catch (Throwable ignored) {}
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        } catch (Throwable ignored) {}
    }
}
