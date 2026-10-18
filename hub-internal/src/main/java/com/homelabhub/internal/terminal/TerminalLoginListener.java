package com.homelabhub.internal.terminal;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Component;

/**
 * When a dashboard login ends (sign out, or the 12 h session expiry) its terminals are closed at once (plan R9). Listening
 * to the servlet session itself covers every way a login can end without touching AuthController.
 */
@Component
class TerminalLoginListener implements HttpSessionListener {

    private final TerminalSessionManager sessions;

    TerminalLoginListener(TerminalSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        sessions.closeForLoginSession(se.getSession().getId());
    }
}
