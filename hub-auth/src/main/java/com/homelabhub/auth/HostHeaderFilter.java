package com.homelabhub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refuses requests whose Host header does not name us (DNS-rebinding defence, plan 5.3). Runs first. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostHeaderFilter extends OncePerRequestFilter {

    private final HostAllowlist allowlist;
    private final byte[] refusal;

    /** {@code variable} names the setting in the refusal message: HUB_ALLOWED_HOSTS for the hub, DOCS_ALLOWED_HOSTS for Docs. */
    public HostHeaderFilter(AuthProperties props, @Value("${hub.security.allowed-hosts-variable:HUB_ALLOWED_HOSTS}") String variable) {
        this.allowlist = new HostAllowlist(props.security().allowedHosts());
        this.refusal = ("{\"error\":\"Invalid Host header. Add the name to " + variable + " if it is yours.\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        // A missing Host header cannot come from a browser (DNS rebinding always names its own domain) but does come
        // from HTTP/1.0 scripts and embedded devices, which are legitimate legacy callers. Only a WRONG name is refused.
        if (host != null && !allowlist.allows(host)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getOutputStream().write(refusal);
            return;
        }
        chain.doFilter(request, response);
    }
}
