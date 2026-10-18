package com.homelabhub.internal.terminal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Plain WebSocket (no STOMP, no SockJS) at /api/admin/terminal/ws. */
@Configuration
@EnableWebSocket
class TerminalWebSocketConfig implements WebSocketConfigurer {

    static final String PATH = "/api/admin/terminal/ws";

    private final TerminalWebSocketHandler handler;
    private final TerminalHandshakeInterceptor interceptor;

    TerminalWebSocketConfig(TerminalWebSocketHandler handler, TerminalHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's default same-origin check compares the scheme too, which fails behind `tailscale serve` (https outside,
        // http inside). TerminalHandshakeInterceptor does the Origin check instead (OriginCheck); "*" only turns Spring's off.
        registry.addHandler(handler, PATH).addInterceptors(interceptor).setAllowedOriginPatterns("*");
    }
}
