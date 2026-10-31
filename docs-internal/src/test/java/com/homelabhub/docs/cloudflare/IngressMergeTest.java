package com.homelabhub.docs.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The tunnel-config merge: the PUT replaces everything, so what is not ours must come back exactly as it was. */
class IngressMergeTest {

    static final String SVC = "http://docs-public:3011";
    static final RouteHosts HOSTS = new RouteHosts("example.test", List.of("portfolio"));

    static final String BASE = """
            {"ingress":[
              {"hostname":"portfolio.example.test","service":"http://hub-public:3011"},
              {"hostname":"*.example.test","service":"http://docs-public:3011"},
              {"service":"http_status:404"}],
             "warp-routing":{"enabled":false},"originRequest":{"connectTimeout":"10s"}}""";

    static JsonNode cfg(String json) {
        return FakeCloudflare.parse(json);
    }

    static IngressMerge.Result merge(JsonNode c, String... desired) {
        return IngressMerge.merge(c, Set.of(desired), SVC, HOSTS::valid);
    }

    static List<String> hosts(JsonNode c) {
        List<String> out = new ArrayList<>();
        c.path("ingress").forEach(r -> out.add(r.path("hostname").asText("<catch-all>")));
        return out;
    }

    @Test
    void addsBeforeTheWildcardAndKeepsTheCatchAllLast() {
        IngressMerge.Result r = merge(cfg(BASE), "doc1.example.test");
        assertThat(hosts(r.config())).containsExactly("portfolio.example.test", "doc1.example.test", "*.example.test", "<catch-all>");
        assertThat(r.added()).containsExactly("doc1.example.test");
        assertThat(r.config().path("ingress").get(1).path("service").asText()).isEqualTo(SVC);
    }

    @Test
    void keepsEveryOtherRuleAndSettingUntouched() {
        JsonNode in = cfg(BASE);
        IngressMerge.Result r = merge(in, "doc1.example.test");
        assertThat(r.config().path("warp-routing")).isEqualTo(in.path("warp-routing"));
        assertThat(r.config().path("originRequest")).isEqualTo(in.path("originRequest"));
        assertThat(r.config().path("ingress").get(0)).isEqualTo(in.path("ingress").get(0)); // the portfolio rule
        assertThat(in).isEqualTo(cfg(BASE)); // the input is not modified
    }

    @Test
    void isIdempotent() {
        IngressMerge.Result once = merge(cfg(BASE), "a.example.test", "b.example.test");
        IngressMerge.Result twice = IngressMerge.merge(once.config(), Set.of("a.example.test", "b.example.test"), SVC, HOSTS::valid);
        assertThat(twice.changed()).isFalse();
        assertThat(twice.config()).isEqualTo(once.config());
    }

    @Test
    void removesOnlyItsOwnRulesWhenNoLongerWanted() {
        IngressMerge.Result with = merge(cfg(BASE), "a.example.test", "b.example.test");
        IngressMerge.Result without = IngressMerge.merge(with.config(), Set.of("b.example.test"), SVC, HOSTS::valid);
        assertThat(without.removed()).containsExactly("a.example.test");
        assertThat(hosts(without.config())).containsExactly("portfolio.example.test", "b.example.test", "*.example.test", "<catch-all>");
    }

    @Test
    void neverRemovesForeignWildcardReservedOrDecoratedRules() {
        JsonNode c = cfg("""
                {"ingress":[
                  {"hostname":"portfolio.example.test","service":"http://hub-public:3011"},
                  {"hostname":"other.example.test","service":"http://elsewhere:80"},
                  {"hostname":"www.example.test","service":"http://docs-public:3011"},
                  {"hostname":"deco.example.test","service":"http://docs-public:3011","originRequest":{"noTLSVerify":true}},
                  {"hostname":"sub.deep.example.test","service":"http://docs-public:3011"},
                  {"hostname":"*.example.test","service":"http://docs-public:3011"},
                  {"service":"http_status:404"}]}""");
        IngressMerge.Result r = merge(c); // nothing wanted
        assertThat(r.changed()).isFalse();
        assertThat(r.config()).isEqualTo(c);
    }

    @Test
    void anExistingRuleForAnotherServiceIsAConflictNotOverwritten() {
        IngressMerge.Result r = merge(cfg(BASE), "portfolio.example.test");
        assertThat(r.conflicts()).containsExactly("portfolio.example.test");
        assertThat(r.changed()).isFalse();
        assertThat(r.config().path("ingress").get(0).path("service").asText()).isEqualTo("http://hub-public:3011");
    }

    @Test
    void refusesAConfigWithoutAFinalCatchAll() {
        assertThatThrownBy(() -> merge(cfg("{\"ingress\":[{\"hostname\":\"a.example.test\",\"service\":\"http://x\"}]}"), "b.example.test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> merge(cfg("{}"), "b.example.test")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void worksWithoutAWildcardRule() {
        JsonNode c = cfg("{\"ingress\":[{\"hostname\":\"portfolio.example.test\",\"service\":\"http://hub-public:3011\"},{\"service\":\"http_status:404\"}]}");
        IngressMerge.Result r = merge(c, "a.example.test");
        assertThat(hosts(r.config())).containsExactly("portfolio.example.test", "a.example.test", "<catch-all>");
    }
}
