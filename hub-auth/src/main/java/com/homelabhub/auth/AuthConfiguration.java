package com.homelabhub.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * The shared login: import it from the app's security configuration, provide a {@link LoginAudit} bean, and build the
 * filter chain with {@link AuthSecurity}. Settings stay under hub.auth.* and hub.security.* in every app.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@Import({AuthController.class, HostHeaderFilter.class})
public class AuthConfiguration {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * We authenticate through AuthController, never through Spring's user store. Declaring an empty one stops Boot from
     * creating a default "user" with a random password and printing it to the log.
     */
    @Bean
    org.springframework.security.core.userdetails.UserDetailsService noUsers() {
        return new org.springframework.security.provisioning.InMemoryUserDetailsManager();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    LoginRateLimiter loginRateLimiter(Clock clock) {
        return new LoginRateLimiter(clock);
    }
}
