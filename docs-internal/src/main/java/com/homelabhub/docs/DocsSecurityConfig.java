package com.homelabhub.docs;

import com.homelabhub.auth.AuthConfiguration;
import com.homelabhub.auth.AuthProperties;
import com.homelabhub.auth.AuthSecurity;
import com.homelabhub.auth.LoginAudit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Docs has its own login (D-L3), the same code as the hub (hub-auth) with its own cookie name (DOCSSESSION). The webapp
 * preview gets a separate chain: it is reached by signed path, not by session (D15), and its responses set their own
 * sandbox CSP with {@code frame-ancestors 'self'}. Every other response keeps frame-ancestors 'none' and X-Frame-Options DENY.
 */
@Configuration
@EnableWebSecurity
@Import(AuthConfiguration.class)
class DocsSecurityConfig {

    /** Images are data: (pasted before upload) or our own; frames only our own preview paths. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
            + "font-src 'self'; connect-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    @Bean
    LoginAudit loginAudit(DocsAudit audit) {
        return (result, detail, ip, userAgent) -> audit.record("login", "password", result.name(), detail, ip, userAgent);
    }

    @Bean
    @Order(1)
    SecurityFilterChain previewChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/docs-preview/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.requireExplicitSave(true))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // The controller sets CSP, nosniff and Referrer-Policy itself; no X-Frame-Options so our own page may frame it.
                .headers(h -> h.frameOptions(f -> f.disable()).cacheControl(c -> c.disable()))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http, AuthProperties props, SecurityContextRepository repo,
                                    @Value("${hub.security.secure-cookies:false}") boolean secureCookies) throws Exception {
        return AuthSecurity.apply(http, props, repo, new AuthSecurity.Policy(CSP, null, secureCookies)).build();
    }
}
