package com.pophie.voice.model;

/** 模型下载进度（在后台线程回调）。 */
public interface ModelDownloadListener {

    void onDownloadStart(String fileName);

    void onDownloadProgress(String fileName, long bytes);

    void onDownloadDone(String fileName);
}
