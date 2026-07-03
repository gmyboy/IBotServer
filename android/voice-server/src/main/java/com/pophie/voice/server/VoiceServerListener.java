package com.pophie.voice.server;

/** voice-server 事件回调（均在主线程）。 */
public interface VoiceServerListener {

    default void onSttConnecting() {}

    default void onSttReady() {}

    default void onSttPartial(String text) {}

    default void onSttFinal(String text) {}

    default void onSttError(String message) {}

    default void onChatSpeakChunk(String chunk) {}

    /** 服务端通知即将/正在回复（不阻断采集）。 */
    default void onReplyNotify(String phase, String text, String source) {}

    default void onReplyPlayStart(int seq) {}

    default void onReplyPlayEnd(int seq) {}

    default void onChatComplete(String replyText) {}

    default void onChatError(String message) {}

    default void onChatSkipped(String reason) {}

    /**
     * @param speechEnabled 服务端 speech 是否可用
     * @param sessionId 新建或当前会话 id；失败时为 null
     * @param error 失败原因；成功时为 null
     */
    default void onConnectionTested(boolean speechEnabled, String sessionId, String error) {}

    /** 设备绑定完成（{@link VoiceServerBridge#bindDevice()}）。 */
    default void onDeviceBound(PophieApiClient.BindInfo info, String error) {}

    /** 语音段流水已上传。 */
    default void onSegmentLogged(long logId, boolean isOwner, String sttText) {}

    default void onSegmentLogError(String message) {}

    /** MQTT连接状态变化。connected=true时error为null；false时error为断开原因 */
    default void onMqttStatus(boolean connected, String error) {}
}
