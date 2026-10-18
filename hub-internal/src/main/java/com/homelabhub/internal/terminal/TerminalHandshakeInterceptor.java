package com.homelabhub.internal.terminal;

import com.homelabhub.auth.AuthProperties;
import com.homelabhub.auth.HostAllowlist;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Runs on the upgrade request, after Spring Security (which already requires the login for /api/admin/**) and the Host
 * header filter. Adds: the feature switch and the Origin check. Records who connected for the handler.
 */
@Component
class TerminalHandshakeInterceptor implements HandshakeInterceptor {

    static final String LOGIN_SESSION = "term.login";
    static final String CLIENT_IP = "term.ip";
    static final String USER_AGENT = "term.ua";

    private final TerminalFeature feature;
    private final TerminalAudit audit;
    private final HostAllowlist allowlist;

    TerminalHandshakeInterceptor(TerminalFeature feature, TerminalAudit audit, AuthProperties auth) {
        this.feature = feature;
        this.audit = audit;
        this.allowlist = new HostAllowlist(auth.security().allowedHosts());
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        HttpServletRequest req = servlet.getServletRequest();
        String ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        if (!feature.enabled()) {
            audit.record(TerminalAudit.Event.denied, null, ip, ua, "reason=disabled");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String origin = req.getHeader("Origin");
        if (!OriginCheck.allowed(origin, req.getHeader("Host"), allowlist)) {
            audit.record(TerminalAudit.Event.denied, null, ip, ua, "reason=bad_origin origin=" + (origin == null ? "none" : origin));
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        HttpSession login = req.getSession(false);
        if (login == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(LOGIN_SESSION, login.getId());
        attributes.put(CLIENT_IP, ip);
        attributes.put(USER_AGENT, ua);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // nothing
    }
}
