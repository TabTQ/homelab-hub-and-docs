package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** dashboard.yaml `terminals:` (plan 5.2 / task 1.2). */
class TerminalConfigTest {

    static final String FP = "SHA256:" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/AbCdE".substring(0, 43);

    private final ConfigParser parser = new ConfigParser(name -> true);

    static String yaml(String terminals) {
        return "version: 1\ngroups: []\n" + terminals;
    }

    static String entry(String overrides) {
        return "terminals:\n  - {id: pi, label: Raspberry Pi, host: 127.0.0.1, user: hubshell, key: hub_terminal_ed25519, hostKey: \"" + FP + "\""
                + overrides + "}\n";
    }

    private ConfigParser.Result parse(String yaml) {
        return parser.parse(yaml);
    }

    private String errors(ConfigParser.Result r) {
        return r.errors().stream().map(i -> i.path() + "@" + i.line() + ": " + i.message()).collect(Collectors.joining("\n"));
    }

    @Test
    void aValidEntryParsesWithDefaultPort() {
        ConfigParser.Result r = parse(yaml(entry("")));
        assertThat(r.ok()).as(errors(r)).isTrue();
        assertThat(r.config().terminals()).containsExactly(
                new TerminalSpec("pi", "Raspberry Pi", "127.0.0.1", 22, "hubshell", "hub_terminal_ed25519", FP));
    }

    @Test
    void absentOrEmptyIsFine() {
        assertThat(parse(yaml("")).config().terminals()).isEmpty();
        assertThat(parse(yaml("terminals: []\n")).config().terminals()).isEmpty();
        assertThat(parse(yaml("terminals:\n")).ok()).isTrue();
    }

    @Test
    void theExampleInTheDefaultFileIsCommentedOut() throws Exception {
        String text = new String(getClass().getResourceAsStream("/default-dashboard.yaml").readAllBytes());
        ConfigParser.Result r = parse(text);
        assertThat(r.ok()).isTrue();
        assertThat(r.config().terminals()).isEmpty();
        assertThat(text).contains("# terminals:");
    }

    @Test
    void badValuesAreErrorsWithLineNumbers() {
        String[][] cases = {
                {", id: Pi", "terminals[0].id"},
                {", host: '-oProxyCommand=x'", "terminals[0].host"},
                {", port: 0", "terminals[0].port"},
                {", port: 70000", "terminals[0].port"},
                {", user: Root", "terminals[0].user"},
                {", user: 'a b'", "terminals[0].user"},
                {", key: ../../etc/passwd", "terminals[0].key"},
                {", key: /etc/passwd", "terminals[0].key"},
                {", key: 'a\\b'", "terminals[0].key"},
                {", key: '..'", "terminals[0].key"},
                {", hostKey: 'SHA256:short'", "terminals[0].hostKey"},
                {", hostKey: 'MD5:aa:bb'", "terminals[0].hostKey"},
        };
        for (String[] c : cases) {
            // YAML flow maps keep the last duplicate key, so the override wins
            ConfigParser.Result r = parse(yaml(entry(c[0])));
            assertThat(r.ok()).as(c[0]).isFalse();
            assertThat(r.errors()).as(c[0]).anySatisfy(i -> {
                assertThat(i.path()).isEqualTo(c[1]);
                assertThat(i.line()).isEqualTo(4);
            });
        }
    }

    @Test
    void requiredFieldsAndDuplicates() {
        ConfigParser.Result r = parse(yaml("terminals:\n  - {id: pi}\n"));
        assertThat(r.errors()).extracting(Issue::path).contains("terminals[0].label", "terminals[0].host", "terminals[0].user",
                "terminals[0].key", "terminals[0].hostKey");
        String two = entry("") + "  - {id: pi, label: B, host: 10.0.0.2, user: hubshell, key: k, hostKey: \"" + FP + "\"}\n";
        ConfigParser.Result dup = parse(yaml(two));
        assertThat(dup.errors()).anySatisfy(i -> assertThat(i.message()).contains("already uses the id 'pi'"));
    }

    @Test
    void notAListOrTooManyAreErrors() {
        assertThat(parse(yaml("terminals: {id: pi}\n")).errors()).anySatisfy(i -> assertThat(i.path()).isEqualTo("terminals"));
        StringBuilder many = new StringBuilder("terminals:\n");
        for (int i = 0; i < 11; i++) {
            many.append("  - {id: t").append(i).append(", label: T, host: 10.0.0.1, user: u, key: k, hostKey: \"").append(FP).append("\"}\n");
        }
        assertThat(parse(yaml(many.toString())).errors()).anySatisfy(i -> assertThat(i.message()).contains("At most 10"));
    }

    @Test
    void unknownKeysAreWarningsLikeEverywhereElse() {
        ConfigParser.Result r = parse(yaml(entry(", password: x")));
        // "password" is caught by the plaintext-credential rule as well
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.message()).contains("Unknown key 'password'"));
        ConfigParser.Result r2 = parse(yaml(entry(", colour: blue")));
        assertThat(r2.ok()).isTrue();
        assertThat(r2.issues()).anySatisfy(i -> assertThat(i.message()).contains("Unknown key 'colour'"));
    }
}
