package com.homelabhub.pub;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The page must be revalidated on every visit, or a returning visitor keeps an old copy that loads the old scripts. */
class SecurityHeadersFilterTest {

    private static MockHttpServletResponse get(String path) throws Exception {
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", path), response, chain);
        return response;
    }

    @Test
    void theHtmlDocumentIsAlwaysRevalidated() throws Exception {
        assertThat(get("/").getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(get("/index.html").getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void hashedAssetsAndTheDataEndpointKeepTheirOwnCaching() throws Exception {
        assertThat(get("/assets/index-Bu250877.js").getHeader("Cache-Control")).isNull();
        assertThat(get("/assets/index-n76dQrcq.css").getHeader("Cache-Control")).isNull();
        assertThat(get("/api/public/portfolio").getHeader("Cache-Control")).isNull();
    }

    @Test
    void theSecurityHeadersAreStillSetOnEveryResponse() throws Exception {
        var r = get("/");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("Content-Security-Policy")).contains("default-src 'self'");
    }
}
