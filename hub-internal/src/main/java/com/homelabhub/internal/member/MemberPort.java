package com.homelabhub.internal.member;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The optional second port for members (hub.member.port, 0 = off). Only {@link MemberPortFilter} decides what it serves. */
@Configuration
public class MemberPort {

    private final int port;

    MemberPort(@Value("${hub.member.port:0}") int port) {
        this.port = Math.max(0, port);
    }

    public int port() {
        return port;
    }

    public boolean isMemberPort(int localPort) {
        return port > 0 && localPort == port;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> memberConnector(@Value("${server.port:3010}") int mainPort,
                                                                             com.homelabhub.auth.AuthProperties auth) {
        return factory -> {
            if (port <= 0) {
                return;
            }
            // With logins switched off the budget would be readable by anyone on the home network.
            if (auth.auth().mode() == com.homelabhub.auth.AuthProperties.Mode.NONE) {
                throw new IllegalStateException("HUB_MEMBER_PORT cannot be used with HUB_AUTH_MODE=none");
            }
            if (port == mainPort) {
                throw new IllegalStateException("HUB_MEMBER_PORT must differ from HUB_PORT");
            }
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(port);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
