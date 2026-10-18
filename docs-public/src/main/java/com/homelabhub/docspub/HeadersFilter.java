package com.homelabhub.docspub;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Headers on every response, 404s included (plan 5.5, D7). Bundle and asset routes send their own CSP. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HeadersFilter extends OncePerRequestFilter {

    static final String PAGE_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "font-src 'self'; frame-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String uri = request.getRequestURI();
        if (!uri.startsWith("/app/") && !uri.startsWith("/a/")) {
            // Those two routes send their own sandbox policy; two CSP headers would both apply.
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", PAGE_CSP);
        }
        chain.doFilter(request, response);
    }
}
