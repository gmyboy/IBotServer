package com.pophie.voice;

/**
 * 当前说话人判定结果。confidence 已校准到 0~1。
 */
public final class SpeakerInfo {

    /** 判定状态。 */
    public enum State {
        /** 还在累计音频、尚未给出可靠判定（段首极短时）。 */
        PENDING,
        /** 已判定为某登记说话人。 */
        DECIDED,
        /** 未匹配到任何登记说话人（低于阈值）。 */
        UNKNOWN,
    }

    /** 命中的说话人名（UNKNOWN/PENDING 时可能为 null）。 */
    public final String name;
    /** 校准后的可信度 0~1。 */
    public final float confidence;
    /** 与次优说话人的可信度差（越大越确定）；无次优时为 confidence。 */
    public final float margin;
    /** 次优说话人名，可能为 null。 */
    public final String runnerUp;
    /** 是否为主人。 */
    public final boolean isOwner;
    /** 判定状态。 */
    public final State state;

    public SpeakerInfo(String name, float confidence, float margin,
                       String runnerUp, boolean isOwner, State state) {
        this.name = name;
        this.confidence = confidence;
        this.margin = margin;
        this.runnerUp = runnerUp;
        this.isOwner = isOwner;
        this.state = state;
    }

    public static SpeakerInfo pending() {
        return new SpeakerInfo(null, 0f, 0f, null, false, State.PENDING);
    }

    public static SpeakerInfo unknown(float confidence) {
        return new SpeakerInfo(null, confidence, 0f, null, false, State.UNKNOWN);
    }

    @Override
    public String toString() {
        return "SpeakerInfo{name=" + name + ", conf=" + String.format(java.util.Locale.ROOT, "%.2f", confidence)
                + ", margin=" + String.format(java.util.Locale.ROOT, "%.2f", margin)
                + ", runnerUp=" + runnerUp + ", owner=" + isOwner + ", state=" + state + "}";
    }
}
