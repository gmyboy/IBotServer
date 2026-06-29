package com.pophie.voice;

/** SDK 配置（不可变，经 Builder 构造）。 */
public final class VoiceConfig {

    // 采集
    public final int sampleRate;
    public final int frameMs;
    public final AudioSourceType source;

    // VAD / 断句
    public final float vadThreshold;
    public final int minSpeechMs;
    public final int maxSilenceMs;
    public final int maxSegmentMs;
    public final int preRollMs;          // 段首前置缓冲，防吞首字
    public final int vadWindowSize;      // Silero v5: 16k→512

    // 声纹
    public final SpeakerModel speakerModel;
    public final float ownerThreshold;   // cosine 阈值
    public final int decisionWindowMs;   // 早出窗口（累计到此即首次打分）
    public final int rescorePeriodMs;    // 长段复核周期
    public final double minScoreRms;     // 低于此能量跳过打分→UNKNOWN
    public final float emaAlpha;         // 可信度 EMA 平滑系数

    // 降噪 / 回声
    public final boolean enableSystemDenoise;
    public final boolean enableAec;
    public final boolean enableAgc;

    // 主人判定稳健性
    public final int minDecisionVoicedMs;  // 累计有声达到此值才给"确定"判定
    public final int confirmRounds;         // 连续 N 次一致才翻转 owner 门控（迟滞防抖）

    // 输出门控
    public final GateMode gateMode;

    // 运行时 / 模型
    public final int numThreads;
    public final boolean useAssets;      // true=从 assets 读模型；false=下载到 filesDir
    public final String modelDir;        // 覆盖模型目录（null=默认 filesDir/pophie-voice）

    private VoiceConfig(Builder b) {
        this.sampleRate = b.sampleRate;
        this.frameMs = b.frameMs;
        this.source = b.source;
        this.vadThreshold = b.vadThreshold;
        this.minSpeechMs = b.minSpeechMs;
        this.maxSilenceMs = b.maxSilenceMs;
        this.maxSegmentMs = b.maxSegmentMs;
        this.preRollMs = b.preRollMs;
        this.vadWindowSize = b.vadWindowSize;
        this.speakerModel = b.speakerModel;
        this.ownerThreshold = b.ownerThreshold;
        this.decisionWindowMs = b.decisionWindowMs;
        this.rescorePeriodMs = b.rescorePeriodMs;
        this.minScoreRms = b.minScoreRms;
        this.emaAlpha = b.emaAlpha;
        this.enableSystemDenoise = b.enableSystemDenoise;
        this.enableAec = b.enableAec;
        this.enableAgc = b.enableAgc;
        this.minDecisionVoicedMs = b.minDecisionVoicedMs;
        this.confirmRounds = b.confirmRounds;
        this.gateMode = b.gateMode;
        this.numThreads = b.numThreads;
        this.useAssets = b.useAssets;
        this.modelDir = b.modelDir;
    }

    public int frameSamples() {
        return sampleRate * frameMs / 1000;
    }

    public static final class Builder {
        private int sampleRate = 16000;
        private int frameMs = 20;
        private AudioSourceType source = AudioSourceType.MIC;

        private float vadThreshold = 0.5f;
        private int minSpeechMs = 200;
        private int maxSilenceMs = 700;
        private int maxSegmentMs = 15000;
        private int preRollMs = 200;
        private int vadWindowSize = 512;

        private SpeakerModel speakerModel = SpeakerModel.SMALL_INT8;
        private float ownerThreshold = 0.35f;      // 无 cohort 时的回退阈值(campplus 跨句同人约 0.4~0.55)；真机自检微调或上 cohort
        private int decisionWindowMs = 600;        // 首次打分窗口（太短 embedding 不稳）
        private int rescorePeriodMs = 1000;
        private double minScoreRms = 300.0;
        private float emaAlpha = 0.5f;

        private boolean enableSystemDenoise = true;
        private boolean enableAec = true;
        private boolean enableAgc = false;     // 声纹验证默认关 AGC（增益泵动损伤特征）
        private int minDecisionVoicedMs = 1000;
        private int confirmRounds = 2;

        private GateMode gateMode = GateMode.REPORT;

        private int numThreads = 1;
        private boolean useAssets = false;
        private String modelDir = null;

        public Builder sampleRate(int v) { this.sampleRate = v; return this; }
        public Builder frameMs(int v) { this.frameMs = v; return this; }
        public Builder source(AudioSourceType v) { this.source = v; return this; }
        public Builder vadThreshold(float v) { this.vadThreshold = v; return this; }
        public Builder minSpeechMs(int v) { this.minSpeechMs = v; return this; }
        public Builder maxSilenceMs(int v) { this.maxSilenceMs = v; return this; }
        public Builder maxSegmentMs(int v) { this.maxSegmentMs = v; return this; }
        public Builder preRollMs(int v) { this.preRollMs = v; return this; }
        public Builder vadWindowSize(int v) { this.vadWindowSize = v; return this; }
        public Builder speakerModel(SpeakerModel v) { this.speakerModel = v; return this; }
        public Builder ownerThreshold(float v) { this.ownerThreshold = v; return this; }
        public Builder decisionWindowMs(int v) { this.decisionWindowMs = v; return this; }
        public Builder rescorePeriodMs(int v) { this.rescorePeriodMs = v; return this; }
        public Builder minScoreRms(double v) { this.minScoreRms = v; return this; }
        public Builder emaAlpha(float v) { this.emaAlpha = v; return this; }
        public Builder enableSystemDenoise(boolean v) { this.enableSystemDenoise = v; return this; }
        public Builder enableAec(boolean v) { this.enableAec = v; return this; }
        public Builder enableAgc(boolean v) { this.enableAgc = v; return this; }
        public Builder minDecisionVoicedMs(int v) { this.minDecisionVoicedMs = v; return this; }
        public Builder confirmRounds(int v) { this.confirmRounds = v; return this; }
        public Builder gateMode(GateMode v) { this.gateMode = v; return this; }
        public Builder numThreads(int v) { this.numThreads = v; return this; }
        public Builder useAssets(boolean v) { this.useAssets = v; return this; }
        public Builder modelDir(String v) { this.modelDir = v; return this; }

        public VoiceConfig build() { return new VoiceConfig(this); }
    }
}
