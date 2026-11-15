package com.homelabhub.internal.member;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On the member port only the SPA files, /api/member/** and the allowlisted budget routes exist. Everything else is a 404,
 * including the admin login, the legacy open routes and the actuator. It decides by the local port of the connection and
 * runs right after the Host check and before Spring Security, so a bug in a permission rule cannot expose anything here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class MemberPortFilter extends OncePerRequestFilter {

    private static final byte[] NOT_FOUND = "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);

    private final MemberPort memberPort;

    MemberPortFilter(MemberPort memberPort) {
        this.memberPort = memberPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !memberPort.isMemberPort(request.getLocalPort());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (MemberAllowlist.match(request.getMethod(), MemberAllowlist.cleanPath(request)).allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        response.getOutputStream().write(NOT_FOUND);
    }
}
