package com.homelabhub.docspub;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One page per hostname (D2, D5). The visitor's Host header picks the page from hosts.json; an unknown host gets an empty
 * 404 that says nothing about which pages exist. Only the health check (used by compose) answers on any host.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HostRouter extends OncePerRequestFilter {

    static final String PAGE = "com.homelabhub.docspub.page";

    private final DocsStore store;

    public HostRouter(DocsStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/api/public/health".equals(request.getRequestURI()) || "/robots.txt".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<DocsStore.Page> page = store.pageFor(DocsStore.normalizeHost(request.getHeader("Host")));
        if (page.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            return;
        }
        request.setAttribute(PAGE, page.get());
        chain.doFilter(request, response);
    }
}
