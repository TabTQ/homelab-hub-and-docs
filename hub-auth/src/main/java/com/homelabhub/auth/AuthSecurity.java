package com.homelabhub.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Builds the filter chain both apps use: session login, CSRF through the XSRF-TOKEN cookie, strict headers, JSON 401/403,
 * and the three auth modes. Each app passes its own CSP and, optionally, routes that stay open and CSRF-exempt.
 */
public final class AuthSecurity {

    private AuthSecurity() {}

    /**
     * @param csp           the Content-Security-Policy for every response of this chain
     * @param openRoutes    routes that need no login and no CSRF token (the hub's legacy wol-server routes); may be null
     * @param secureCookies marks the CSRF cookie Secure (the session cookie is set through server.servlet.session.cookie.secure)
     */
    public record Policy(String csp, RequestMatcher openRoutes, boolean secureCookies) {}

    public static HttpSecurity apply(HttpSecurity http, AuthProperties props, SecurityContextRepository repo, Policy policy) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setSecure(policy.secureCookies() ? Boolean.TRUE : null); // null = follow the request (Secure only when it arrived over https)
        CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();
        plain.setCsrfRequestAttributeName(null); // the SPA reads the cookie itself; no BREACH masking needed on same-origin JSON
        RequestMatcher open = policy.openRoutes();

        http
            .securityContext(c -> c.securityContextRepository(repo))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .csrf(csrf -> {
                csrf.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(plain);
                if (open != null) {
                    csrf.ignoringRequestMatchers(open, new AntPathRequestMatcher("/api/auth/login"));
                } else {
                    csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/auth/login"));
                }
            })
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable) // /api/auth/logout is a plain endpoint
            .headers(h -> h
                    .frameOptions(f -> f.deny())
                    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .contentTypeOptions(Customizer.withDefaults())
                    .contentSecurityPolicy(c -> c.policyDirectives(policy.csp())))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> json(res, HttpStatus.UNAUTHORIZED, "{\"error\":\"unauthenticated\"}"))
                    .accessDeniedHandler((req, res, ex) -> json(res, HttpStatus.FORBIDDEN, "{\"error\":\"forbidden\"}")));

        http.authorizeHttpRequests(a -> {
            if (open != null) {
                a.requestMatchers(open).permitAll();
            }
            a.requestMatchers("/actuator/health", "/actuator/health/**", "/api/auth/**").permitAll();
            switch (props.auth().mode()) {
                case NONE -> a.anyRequest().permitAll();
                case EDIT -> a.requestMatchers("/api/admin/**").authenticated().anyRequest().permitAll();
                // ALL: everything under /api needs the session. Static SPA files stay public: they contain no data.
                case ALL -> a.requestMatchers("/api/**").authenticated().anyRequest().permitAll();
            }
        });
        return http;
    }

    public static void json(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, String body) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType("application/json");
        res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
