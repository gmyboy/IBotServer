package com.pophie.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pophie.service.SpeechService;
import com.pophie.service.SttRealtimeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket {@code /api/stt/stream}：客户端推 PCM，服务端实时回 partial/final。
 *
 * <p>消息（JSON）：
 * <ul>
 *   <li>客户端 → {@code start} / {@code audio} / {@code commit} / {@code close}</li>
 *   <li>服务端 → {@code ready} / {@code partial} / {@code final} / {@code error}</li>
 * </ul>
 */
@Component
public class SttStreamWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SttStreamWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SpeechService speech;
    private final Map<String, SttRealtimeSession> sessions = new ConcurrentHashMap<>();

    public SttStreamWebSocketHandler(SpeechService speech) {
        this.speech = speech;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!speech.isEnabled()) {
            sendJson(session, Map.of("type", "error", "message", "语音功能未启用"));
            closeQuietly(session);
            return;
        }
        SttRealtimeSession stt = new SttRealtimeSession(speech, json -> sendRaw(session, json));
        sessions.put(session.getId(), stt);
        log.info("[stt/ws] connected {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SttRealtimeSession stt = sessions.get(session.getId());
        if (stt == null) return;
        try {
            JsonNode node = MAPPER.readTree(message.getPayload());
            String type = node.path("type").asText("");
            switch (type) {
                case "start" -> {
                    int sr = node.path("sample_rate").asInt(16000);
                    stt.start(sr);
                }
                case "audio" -> {
                    String data = node.path("data").asText("");
                    if (!data.isEmpty()) {
                        stt.appendPcm(Base64.getDecoder().decode(data));
                    }
                }
                case "commit" -> stt.commit();
                case "close" -> {
                    stt.close();
                    closeQuietly(session);
                }
                default -> sendJson(session, Map.of("type", "error", "message", "未知类型: " + type));
            }
        } catch (Exception e) {
            log.warn("[stt/ws] handle error: {}", e.getMessage());
            sendJson(session, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SttRealtimeSession stt = sessions.remove(session.getId());
        if (stt != null) stt.close();
        log.info("[stt/ws] closed {} {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[stt/ws] transport error {}: {}", session.getId(), exception.getMessage());
        SttRealtimeSession stt = sessions.remove(session.getId());
        if (stt != null) stt.close();
        closeQuietly(session);
    }

    private void sendRaw(WebSocketSession session, String json) {
        sendJson(session, null, json);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> obj) {
        try {
            sendJson(session, obj, MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            log.warn("[stt/ws] serialize error: {}", e.getMessage());
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> ignored, String json) {
        if (!session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.warn("[stt/ws] send error: {}", e.getMessage());
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
