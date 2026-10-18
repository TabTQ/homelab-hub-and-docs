package com.homelabhub.internal.auth;

import com.homelabhub.auth.AuthConfiguration;
import com.homelabhub.auth.AuthProperties;
import com.homelabhub.auth.AuthSecurity;
import com.homelabhub.auth.LoginAudit;
import com.homelabhub.internal.audit.AuditLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The hub's login comes from hub-auth (shared with Docs). What stays here: the legacy routes and the hub's CSP. */
@Configuration
@EnableWebSecurity
@Import(AuthConfiguration.class)
class SecurityConfig {

    /** The wol-server compatibility routes stay open and CSRF-exempt by decision D15 (guarded elsewhere). */
    static final RequestMatcher LEGACY = new OrRequestMatcher(
            new AntPathRequestMatcher("/wake"), new AntPathRequestMatcher("/wake/**"),
            new AntPathRequestMatcher("/toggle"), new AntPathRequestMatcher("/toggle/**"),
            new AntPathRequestMatcher("/node/**"));

    /** Same-origin scripts only. Inline styles are needed by React style attributes and CodeMirror. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Bean
    LoginAudit loginAudit(AuditLog audit) {
        return (result, detail, ip, userAgent) ->
                audit.record(AuditLog.Source.ui, "login", "password", AuditLog.Result.valueOf(result.name()), detail, ip, userAgent);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthProperties props, SecurityContextRepository repo,
                                    @org.springframework.beans.factory.annotation.Value("${hub.security.secure-cookies:false}") boolean secureCookies) throws Exception {
        return AuthSecurity.apply(http, props, repo, new AuthSecurity.Policy(CSP, LEGACY, secureCookies)).build();
    }
}
