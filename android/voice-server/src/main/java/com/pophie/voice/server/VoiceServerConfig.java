package com.pophie.voice.server;

/** voice-server 模块配置。 */
public final class VoiceServerConfig {

    public final String baseUrl;
    public final String robotId;
    public final String userId;
    /** 近场 RMS 门限，低于此不送实时 STT。 */
    public final double nearFieldMinRms;
    /** 连续超门限帧数（约 20ms/帧）后才开始 utterance。 */
    public final int nearFieldStartFrames;
    /** 段长低于此不发 chat/stream。 */
    public final long minChatSegmentMs;

    /** 段长低于此不上传流水。 */
    public final long minLogSegmentMs;
    /** 是否默认上传语音段流水。 */
    public final boolean logEnabled;
    /** 流水是否附带 WAV。 */
    public final boolean includeAudioInLog;
    /** 流水是否附带 embedding。 */
    public final boolean includeEmbeddingInLog;
    /** 客户端无 STT 文本时是否请求服务端补 STT。 */
    public final boolean serverSttIfEmpty;
    /** 上传流水前等待实时 STT final 的最长时间（ms）。 */
    public final long sttUploadWaitMs;

    private VoiceServerConfig(Builder b) {
        this.baseUrl = b.baseUrl;
        this.robotId = b.robotId;
        this.userId = b.userId;
        this.nearFieldMinRms = b.nearFieldMinRms;
        this.nearFieldStartFrames = b.nearFieldStartFrames;
        this.minChatSegmentMs = b.minChatSegmentMs;
        this.minLogSegmentMs = b.minLogSegmentMs;
        this.logEnabled = b.logEnabled;
        this.includeAudioInLog = b.includeAudioInLog;
        this.includeEmbeddingInLog = b.includeEmbeddingInLog;
        this.serverSttIfEmpty = b.serverSttIfEmpty;
        this.sttUploadWaitMs = b.sttUploadWaitMs;
    }

    public static final class Builder {
        private String baseUrl = "http://192.168.23.156:9900/";
        private String robotId = "default";
        private String userId = "demo";
        private double nearFieldMinRms = 900.0;
        private int nearFieldStartFrames = 3;
        private long minChatSegmentMs = 300;
        private long minLogSegmentMs = 0;
        private boolean logEnabled = true;
        private boolean includeAudioInLog = true;
        private boolean includeEmbeddingInLog = false;
        private boolean serverSttIfEmpty = true;
        private long sttUploadWaitMs = 1500;

        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder robotId(String v) { this.robotId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder nearFieldMinRms(double v) { this.nearFieldMinRms = v; return this; }
        public Builder nearFieldStartFrames(int v) { this.nearFieldStartFrames = v; return this; }
        public Builder minChatSegmentMs(long v) { this.minChatSegmentMs = v; return this; }
        public Builder minLogSegmentMs(long v) { this.minLogSegmentMs = v; return this; }
        public Builder logEnabled(boolean v) { this.logEnabled = v; return this; }
        public Builder includeAudioInLog(boolean v) { this.includeAudioInLog = v; return this; }
        public Builder includeEmbeddingInLog(boolean v) { this.includeEmbeddingInLog = v; return this; }
        public Builder serverSttIfEmpty(boolean v) { this.serverSttIfEmpty = v; return this; }
        public Builder sttUploadWaitMs(long v) { this.sttUploadWaitMs = v; return this; }

        public VoiceServerConfig build() { return new VoiceServerConfig(this); }
    }
}
