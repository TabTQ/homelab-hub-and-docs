package com.homelabhub.pub;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: https:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
                + "form-action 'none'; frame-ancestors 'none'");
        if (isHtmlDocument(request.getRequestURI())) {
            // The page itself is a plain file with only a last-modified date, so browsers would otherwise keep an old copy for hours and
            // keep loading the old scripts after a deploy. no-cache = ask every time; the server answers 304 when nothing changed.
            response.setHeader("Cache-Control", "no-cache");
        }
        filterChain.doFilter(request, response);
    }

    /** The HTML document only: the hashed files under /assets keep their caching, and the data endpoint sets its own header. */
    static boolean isHtmlDocument(String path) {
        return "/".equals(path) || path.endsWith(".html");
    }
}
