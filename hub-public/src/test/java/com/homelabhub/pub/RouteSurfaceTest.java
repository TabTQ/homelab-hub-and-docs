package com.homelabhub.pub;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RouteSurfaceTest {

    @Autowired
    RequestMappingHandlerMapping mapping;

    @Test
    void onlyPublicRoutesAreExposed() {
        Set<String> patterns = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Class<?> type = handler.getBeanType();
            if (type.getPackageName().equals("com.homelabhub.pub")) {
                info.getPatternValues().forEach(patterns::add);
            }
        });
        assertThat(patterns).containsExactlyInAnyOrder("/api/public/portfolio", "/api/public/health");
    }
}