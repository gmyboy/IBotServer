package com.pophie.voice.model;

import android.content.Context;
import android.util.Log;

import com.pophie.voice.SpeakerModel;
import com.pophie.voice.VoiceConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 模型解析与（按需）下载。
 * - useAssets=true：模型已打进 assets，返回 assets 内相对路径（配合 AssetManager 使用）。
 * - useAssets=false：确保模型存在于 filesDir/pophie-voice，缺失则从 sherpa-onnx release 下载。
 *
 * 注：下列模型文件名/URL 以 sherpa-onnx 官方 release 为准，必要时调用 setSpeakerModelUrl 覆盖。
 */
public final class ModelManager {

    private static final String TAG = "PophieVoiceModel";

    private static final String VAD_FILE = "silero_vad.onnx";
    private static final String VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    // 说话人模型（默认值，可覆盖）。SMALL=campplus（较小较快），ACCURATE=eres2net。
    private static final String SPK_BASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/";
    private static final String SPK_SMALL_FILE = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx";
    private static final String SPK_ACCURATE_FILE = "3dspeaker_speech_eres2net_sv_zh-cn_16k-common.onnx";

    private final Context context;
    private final VoiceConfig config;
    private final File dir;

    private String speakerFile;
    private String speakerUrl;

    public ModelManager(Context context, VoiceConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.dir = config.modelDir != null ? new File(config.modelDir)
                : new File(this.context.getFilesDir(), "pophie-voice");
        if (!dir.exists()) dir.mkdirs();
        boolean small = config.speakerModel == SpeakerModel.SMALL_INT8;
        this.speakerFile = small ? SPK_SMALL_FILE : SPK_ACCURATE_FILE;
        this.speakerUrl = SPK_BASE + this.speakerFile;
    }

    /** 覆盖说话人模型下载地址与文件名（如官方文件名变更或自带模型）。 */
    public void setSpeakerModelUrl(String url, String fileName) {
        this.speakerUrl = url;
        this.speakerFile = fileName;
    }

    /** assets 模式返回相对名；否则确保下载并返回绝对路径。 */
    public String vadPath() throws Exception {
        if (config.useAssets) return VAD_FILE;
        return ensure(VAD_FILE, VAD_URL).getAbsolutePath();
    }

    public String speakerPath() throws Exception {
        if (config.useAssets) return speakerFile;
        return ensure(speakerFile, speakerUrl).getAbsolutePath();
    }

    private File ensure(String fileName, String url) throws Exception {
        File f = new File(dir, fileName);
        if (f.exists() && f.length() > 1024) return f;
        Log.i(TAG, "下载模型 " + fileName + " <- " + url);
        File tmp = new File(dir, fileName + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            if (total < 1024) throw new Exception("下载内容过小: " + total);
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(f)) throw new Exception("模型重命名失败: " + fileName);
        Log.i(TAG, "模型就绪 " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
        return f;
    }
}
