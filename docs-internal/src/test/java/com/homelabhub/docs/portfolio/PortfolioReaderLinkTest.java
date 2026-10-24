package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** A homelab device links to a public page through extra.url, and nothing but an http(s) address is ever passed on. */
class PortfolioReaderLinkTest {

    @Test
    void passesAnHttpsOrHttpAddressThrough() {
        assertThat(PortfolioReader.deviceUrl(Map.of("url", "https://pve.example.org/"))).isEqualTo("https://pve.example.org/");
        assertThat(PortfolioReader.deviceUrl(Map.of("url", "http://pve.example.org/"))).isEqualTo("http://pve.example.org/");
    }

    @Test
    void hasNoLinkWhenThereIsNoUrl() {
        assertThat(PortfolioReader.deviceUrl(Map.of())).isNull();
        assertThat(PortfolioReader.deviceUrl(PortfolioReader.jsonMap(null))).isNull();
        assertThat(PortfolioReader.deviceUrl(PortfolioReader.jsonMap("{\"network\":\"vmbr0\"}"))).isNull();
    }

    @Test
    void dropsAnythingThatIsNotAWebAddress() {
        assertThat(PortfolioReader.deviceUrl(Map.of("url", "javascript:alert(1)"))).isNull();
        assertThat(PortfolioReader.deviceUrl(Map.of("url", "data:text/html,x"))).isNull();
        assertThat(PortfolioReader.deviceUrl(Map.of("url", 42))).isNull();
        assertThat(PortfolioReader.deviceUrl(PortfolioReader.jsonMap("not json"))).isNull();
    }
}
