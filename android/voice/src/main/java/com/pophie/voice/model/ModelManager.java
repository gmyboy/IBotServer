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
import java.util.ArrayList;
import java.util.List;

/**
 * 模型解析与（按需）下载。
 * - useAssets=true：模型已打进 assets，返回 assets 内相对路径（配合 AssetManager 使用）。
 * - useAssets=false：确保模型存在于 filesDir/pophie-voice，缺失则从 sherpa-onnx release 下载。
 */
public final class ModelManager {

    private static final String TAG = "PophieVoiceModel";

    private static final String VAD_FILE = "silero_vad.onnx";
    private static final String VAD_GH =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private static final String SPK_BASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/";
    private static final String SPK_SMALL_FILE = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx";
    private static final String SPK_ACCURATE_FILE = "3dspeaker_speech_eres2net_sv_zh-cn_16k-common.onnx";

    private final Context context;
    private final VoiceConfig config;
    private final File dir;
    private volatile ModelDownloadListener downloadListener;

    private String speakerFile;
    private final List<String> speakerUrls = new ArrayList<>();

    public ModelManager(Context context, VoiceConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.dir = config.modelDir != null ? new File(config.modelDir)
                : new File(this.context.getFilesDir(), "pophie-voice");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "模型目录创建失败: " + dir.getAbsolutePath());
        }
        boolean small = config.speakerModel == SpeakerModel.SMALL_INT8;
        this.speakerFile = small ? SPK_SMALL_FILE : SPK_ACCURATE_FILE;
        addSpeakerUrls(this.speakerFile);
    }

    public void setDownloadListener(ModelDownloadListener listener) {
        this.downloadListener = listener;
    }

    /** 覆盖说话人模型下载地址与文件名（如官方文件名变更或自带模型）。 */
    public void setSpeakerModelUrl(String url, String fileName) {
        speakerUrls.clear();
        speakerFile = fileName;
        speakerUrls.add(url);
    }

    private void addSpeakerUrls(String file) {
        speakerUrls.clear();
        speakerUrls.add(SPK_BASE + file);
        speakerUrls.add("https://ghfast.top/" + SPK_BASE + file);
        speakerUrls.add("https://mirror.ghproxy.com/" + SPK_BASE + file);
    }

    /** assets 模式返回相对名；否则确保下载并返回绝对路径。 */
    public String vadPath() throws Exception {
        if (config.useAssets) return VAD_FILE;
        return ensure(VAD_FILE, vadUrls()).getAbsolutePath();
    }

    public String speakerPath() throws Exception {
        if (config.useAssets) return speakerFile;
        return ensure(speakerFile, speakerUrls).getAbsolutePath();
    }

    private static List<String> vadUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(VAD_GH);
        urls.add("https://ghfast.top/" + VAD_GH);
        urls.add("https://mirror.ghproxy.com/" + VAD_GH);
        return urls;
    }

    private File ensure(String fileName, List<String> urls) throws Exception {
        File f = new File(dir, fileName);
        if (f.exists() && f.length() > 1024) return f;
        File tmp = new File(dir, fileName + ".tmp");
        if (tmp.exists()) tmp.delete();

        Exception last = null;
        for (String url : urls) {
            try {
                download(fileName, url, tmp);
                if (!tmp.renameTo(f)) {
                    throw new Exception("模型重命名失败: " + fileName);
                }
                Log.i(TAG, "模型就绪 " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
                return f;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "下载失败 " + url + ": " + e.getMessage());
                if (tmp.exists()) tmp.delete();
            }
        }
        throw last != null ? last : new Exception("无可用下载地址: " + fileName);
    }

    private void download(String fileName, String url, File tmp) throws Exception {
        Log.i(TAG, "下载模型 " + fileName + " <- " + url);
        ModelDownloadListener listener = downloadListener;
        if (listener != null) listener.onDownloadStart(fileName);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "PophieVoice/1.0");
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            long lastNotify = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (listener != null && total - lastNotify >= (1 << 18)) {
                    listener.onDownloadProgress(fileName, total);
                    lastNotify = total;
                }
            }
            out.flush();
            if (total < 1024) throw new Exception("下载内容过小: " + total);
            if (listener != null) {
                listener.onDownloadProgress(fileName, total);
                listener.onDownloadDone(fileName);
            }
        } finally {
            conn.disconnect();
        }
    }
}
