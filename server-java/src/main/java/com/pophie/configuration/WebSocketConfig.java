package com.pophie.configuration;

import com.pophie.websocket.ReplyNotifyWebSocketHandler;
import com.pophie.websocket.SttStreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SttStreamWebSocketHandler sttStreamWebSocketHandler;
    private final ReplyNotifyWebSocketHandler replyNotifyWebSocketHandler;

    public WebSocketConfig(SttStreamWebSocketHandler sttStreamWebSocketHandler,
                           ReplyNotifyWebSocketHandler replyNotifyWebSocketHandler) {
        this.sttStreamWebSocketHandler = sttStreamWebSocketHandler;
        this.replyNotifyWebSocketHandler = replyNotifyWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sttStreamWebSocketHandler, "/api/stt/stream")
                .setAllowedOrigins("*");
        registry.addHandler(replyNotifyWebSocketHandler, "/api/reply/notify")
                .setAllowedOrigins("*");
    }
}
