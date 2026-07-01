package com.pophie.configuration;

import com.pophie.websocket.SttStreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SttStreamWebSocketHandler sttStreamWebSocketHandler;

    public WebSocketConfig(SttStreamWebSocketHandler sttStreamWebSocketHandler) {
        this.sttStreamWebSocketHandler = sttStreamWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sttStreamWebSocketHandler, "/api/stt/stream")
                .setAllowedOrigins("*");
    }
}
