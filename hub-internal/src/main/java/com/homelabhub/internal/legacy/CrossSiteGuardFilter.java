package com.homelabhub.internal.legacy;

import com.homelabhub.internal.audit.AuditLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Plan 9.5 mitigation for the open legacy routes: browsers label cross-site requests with
 * {@code Sec-Fetch-Site: cross-site}; curl, n8n and other server-side callers do not send the header, so they are
 * unaffected. This stops "a web page open in your browser fires GET /node/pve/shutdown" without adding a login.
 * A link clicked on another PORT of the same host is "same-site" and still works.
 */
@Component
public class CrossSiteGuardFilter extends OncePerRequestFilter {

    private final LegacyProperties props;
    private final AuditLog audit;

    public CrossSiteGuardFilter(LegacyProperties props, AuditLog audit) {
        this.props = props;
        this.audit = audit;
    }

    static boolean isLegacyPath(String path) {
        return path.equals("/wake") || path.startsWith("/wake/") || path.equals("/toggle") || path.startsWith("/toggle/")
                || path.startsWith("/node/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.blockCrossSite() || !isLegacyPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && site.trim().equalsIgnoreCase("cross-site")) {
            audit.record(AuditLog.Source.legacy, "cross-site-blocked", request.getMethod() + " " + request.getRequestURI(),
                    AuditLog.Result.denied, "Sec-Fetch-Site: cross-site", request.getRemoteAddr(), request.getHeader("User-Agent"));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getOutputStream().write(
                    "{\"error\":\"Forbidden\",\"message\":\"Cross-site browser requests are not allowed\",\"details\":null}"
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
