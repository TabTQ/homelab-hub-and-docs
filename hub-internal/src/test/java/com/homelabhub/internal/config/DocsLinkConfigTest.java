package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** dashboard.yaml `docs: {url}` (docs plan D-L2): the top bar's Docs link. */
class DocsLinkConfigTest {

    private final ConfigParser parser = new ConfigParser(name -> false);

    private ConfigParser.Result parse(String docs) {
        return parser.parse("version: 1\ngroups: []\n" + docs);
    }

    private static String errors(ConfigParser.Result r) {
        return r.errors().stream().map(i -> i.path() + ": " + i.message()).toList().toString();
    }

    @Test
    void noDocsKeyMeansNoLink() {
        ConfigParser.Result r = parse("");
        assertThat(r.ok()).isTrue();
        assertThat(r.config().docsUrl()).isNull();
    }

    @Test
    void anHttpsUrlIsTheLink() {
        ConfigParser.Result r = parse("docs: {url: https://docs.example-tailnet.ts.net}\n");
        assertThat(r.ok()).as(errors(r)).isTrue();
        assertThat(r.config().docsUrl()).isEqualTo("https://docs.example-tailnet.ts.net");
        assertThat(parse("docs:\n  url: http://192.168.10.50:3020\n").config().docsUrl()).isEqualTo("http://192.168.10.50:3020");
    }

    @Test
    void badSchemesCredentialsAndUnknownKeysAreReported() {
        assertThat(errors(parse("docs: {url: \"javascript:alert(1)\"}\n"))).contains("docs.url").contains("http://");
        assertThat(errors(parse("docs: {url: \"ftp://docs.example\"}\n"))).contains("docs.url");
        assertThat(errors(parse("docs: {url: \"https://me:pw@docs.example\"}\n"))).contains("user name or password");
        assertThat(errors(parse("docs: {url: /docs}\n"))).contains("docs.url");
        assertThat(errors(parse("docs: {}\n"))).contains("docs.url");
        assertThat(errors(parse("docs: https://docs.example\n"))).contains("docs");
        ConfigParser.Result extra = parse("docs: {url: https://docs.example, token: x}\n");
        assertThat(extra.issues().stream().map(Issue::message).toList().toString()).contains("token");
    }

    @Test
    void thePortfolioUrlIsOptionalAndMustBeAPlainHttpUrl() {
        assertThat(parse("docs: {url: https://docs.example}\n").config().portfolioUrl()).isNull();
        ConfigParser.Result r = parse("docs: {url: https://docs.example, portfolioUrl: https://portfolio.example.com}\n");
        assertThat(r.ok()).as(errors(r)).isTrue();
        assertThat(r.config().portfolioUrl()).isEqualTo("https://portfolio.example.com");
        assertThat(errors(parse("docs: {url: https://docs.example, portfolioUrl: \"javascript:alert(1)\"}\n"))).contains("portfolioUrl");
        assertThat(errors(parse("docs: {url: https://docs.example, portfolioUrl: \"https://me:pw@portfolio.example\"}\n"))).contains("user name or password");
    }
}
