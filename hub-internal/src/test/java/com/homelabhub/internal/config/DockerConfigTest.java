package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.config.DashboardConfig.DockerHostSpec;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** dashboard.yaml `dockerHosts:` (docs/design-history/docker-feature-plan.md): same SSH rules as terminals, plus `protect`. */
class DockerConfigTest {

    static final String FP = TerminalConfigTest.FP;

    private final ConfigParser parser = new ConfigParser(name -> true);

    static String yaml(String body) {
        return "version: 1\ngroups: []\n" + body;
    }

    static String entry(String overrides) {
        return "dockerHosts:\n  - {id: pi, label: Raspberry Pi, host: 127.0.0.1, user: hubdocker, key: hub_docker_ed25519, hostKey: \"" + FP + "\""
                + overrides + "}\n";
    }

    private String errors(ConfigParser.Result r) {
        return r.errors().stream().map(i -> i.path() + "@" + i.line() + ": " + i.message()).collect(Collectors.joining("\n"));
    }

    @Test
    void aValidEntryParses() {
        ConfigParser.Result r = parser.parse(yaml(entry(", port: 2222, protect: [hub-internal, pihole]")));
        assertThat(r.ok()).as(errors(r)).isTrue();
        assertThat(r.config().dockerHosts()).containsExactly(new DockerHostSpec("pi", "Raspberry Pi", "127.0.0.1", 2222, "hubdocker",
                "hub_docker_ed25519", FP, List.of("hub-internal", "pihole")));
        assertThat(r.config().dockerHosts().get(0).ssh().user()).isEqualTo("hubdocker");
    }

    @Test
    void absentIsFineAndDefaultsApply() {
        assertThat(parser.parse(yaml("")).config().dockerHosts()).isEmpty();
        ConfigParser.Result r = parser.parse(yaml(entry("")));
        assertThat(r.config().dockerHosts().get(0).port()).isEqualTo(22);
        assertThat(r.config().dockerHosts().get(0).protect()).isEmpty();
    }

    @Test
    void theDefaultFileHasACommentedExample() throws Exception {
        String text = new String(getClass().getResourceAsStream("/default-dashboard.yaml").readAllBytes());
        ConfigParser.Result r = parser.parse(text);
        assertThat(r.ok()).isTrue();
        assertThat(r.config().dockerHosts()).isEmpty();
        assertThat(text).contains("# dockerHosts:");
    }

    @Test
    void badValuesAreErrorsWithPaths() {
        String[][] cases = {
                {", id: Pi", "dockerHosts[0].id"},
                {", key: ../id_rsa", "dockerHosts[0].key"},
                {", hostKey: nope", "dockerHosts[0].hostKey"},
                {", user: Root!", "dockerHosts[0].user"},
                {", protect: [\"bad name!\"]", "dockerHosts[0].protect[0]"},
                {", protect: hub", "dockerHosts[0].protect"},
                {", extra: 1", "dockerHosts[0].extra"},
        };
        for (String[] c : cases) {
            ConfigParser.Result r = parser.parse(yaml(entry(c[0])));
            assertThat(r.issues()).as(c[0]).anySatisfy(i -> assertThat(i.path()).isEqualTo(c[1]));
        }
    }

    @Test
    void duplicateIdsAndTooManyAreRefused() {
        String one = "  - {id: a, label: A, host: h, user: u, key: k, hostKey: \"" + FP + "\"}\n";
        ConfigParser.Result dup = parser.parse(yaml("dockerHosts:\n" + one + one));
        assertThat(dup.errors()).anySatisfy(i -> assertThat(i.message()).contains("already uses the id 'a'"));
        StringBuilder many = new StringBuilder("dockerHosts:\n");
        for (int i = 0; i < 21; i++) {
            many.append("  - {id: h").append(i).append(", label: A, host: h, user: u, key: k, hostKey: \"").append(FP).append("\"}\n");
        }
        assertThat(parser.parse(yaml(many.toString())).errors()).anySatisfy(i -> assertThat(i.message()).contains("At most 20"));
    }
}
