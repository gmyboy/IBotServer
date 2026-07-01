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
import java.util.Map;

/**
 * WebSocket {@code /api/reply/notify}：服务端推送回复通知 + TTS 音频（不阻断用户说话）。
 * 连接参数：robot_id、user_id、session_id（可选）。
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
        notifyService.register(session, robotId, userId, sessionId);
        session.sendMessage(new TextMessage("{\"type\":\"ready\"}"));
        log.info("[reply/ws] connected robot={} user={}", robotId, userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端可发 ping；暂无其他上行协议
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        notifyService.unregister(session);
        log.info("[reply/ws] closed {}", status);
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (uri == null || uri.getQuery() == null) return out;
        for (String part : uri.getQuery().split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) out.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }
}
