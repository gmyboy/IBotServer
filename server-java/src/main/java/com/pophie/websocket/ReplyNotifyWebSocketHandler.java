package com.pophie.websocket;

import com.pophie.service.ReplyNotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reply通知WebSocket Handler，支持两种模式：
 *
 * 1. 长连接模式（/api/reply/notify）：
 *    传统模式，客户端保持WebSocket长连接，服务端主动推送消息。
 *    适用于Web/APP前台等能保持长连接的场景。
 *
 * 2. 短连接拉取模式（/api/reply/pull 或 ?mode=pull）：
 *    MQTT唤醒后客户端连接此端点，服务端立即推送暂存消息，推送完毕后自动关闭连接。
 *    机器人等资源受限设备推荐使用此模式，平时不保持长连接。
 */
@Component
public class ReplyNotifyWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplyNotifyWebSocketHandler.class);

    private final ReplyNotifyService notifyService;

    public ReplyNotifyWebSocketHandler(ReplyNotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> q = parseQuery(session.getUri());
        String robotId = q.getOrDefault("robot_id", "default");
        String userId = q.getOrDefault("user_id", "default");
        String sessionId = q.get("session_id");

        String path = session.getUri() != null ? session.getUri().getPath() : "";
        boolean pullMode = path.endsWith("/pull") || "pull".equals(q.get("mode"));

        notifyService.register(session, robotId, userId, sessionId, pullMode);
        session.sendMessage(new TextMessage("{\"type\":\"ready\",\"pull\":" + pullMode + "}"));
        log.info("[reply/ws] connected robot={} user={} pull={}", robotId, userId, pullMode);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        notifyService.unregister(session);
        log.info("[reply/ws] closed {} code={}", session.getId(), status.getCode());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[reply/ws] transport error: {}", exception.getMessage());
        notifyService.unregister(session);
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception ignored) {}
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        if (uri == null || uri.getQuery() == null) return out;
        for (String part : uri.getQuery().split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                out.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
