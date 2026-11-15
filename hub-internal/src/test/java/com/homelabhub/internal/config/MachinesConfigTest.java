package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** dashboard.yaml `machines:` and `on:` (round 5). */
class MachinesConfigTest {

    private final ConfigParser parser = new ConfigParser(name -> true);

    private static final String BASE = """
            version: 1
            groups:
              - name: "G"
                items:
                  - name: "Svc"
                    href: "http://192.0.2.5/"
            actions:
              - id: wake
                label: Wake
                type: wol
                mac: "02:00:00:00:00:01"
            """;

    private ConfigParser.Result parse(String machinesYaml) {
        return parser.parse(BASE + machinesYaml);
    }

    private static List<Issue> issues(ConfigParser.Result r, Issue.Level level) {
        return r.issues().stream().filter(i -> i.level() == level).toList();
    }

    /** Line (1-based) of the LAST line of BASE + machines text containing the needle. */
    private static int lineOf(String machinesYaml, String needle) {
        List<String> lines = (BASE + machinesYaml).lines().toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        throw new AssertionError("no line with " + needle);
    }

    private void assertError(String machinesYaml, String path, String needleLine, String messagePart) {
        var r = parse(machinesYaml);
        assertThat(r.ok()).as("expected an error").isFalse();
        assertThat(r.errors()).anySatisfy(i -> {
            assertThat(i.path()).isEqualTo(path);
            assertThat(i.line()).isEqualTo(lineOf(machinesYaml, needleLine));
            assertThat(i.message()).contains(messagePart);
        });
    }

    @Test
    void aConfigWithoutMachinesHasAnEmptyListAndNoIssues() {
        var r = parser.parse(BASE);
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).isEmpty();
        assertThat(r.config().machines()).isEmpty();
    }

    @Test
    void aFullMachinesSectionParses() {
        var r = parse("""
                machines:
                  - id: "pve"
                    name: "Proxmox host"
                    addresses:
                      - "192.0.2.20"
                    power:
                      - "wake"
                  - id: "apps"
                    name: "Apps"
                    in: "pve"
                    match:
                      - "*.ts.net"
                    toggle: "wake"
                """);
        assertThat(r.ok()).as(r.issues().toString()).isTrue();
        assertThat(r.issues()).isEmpty();
        var m = r.config().machines();
        assertThat(m).hasSize(2);
        assertThat(m.get(0)).isEqualTo(new DashboardConfig.Machine("pve", "Proxmox host", null, List.of("192.0.2.20"), List.of(), List.of("wake"), null));
        assertThat(m.get(1).parent()).isEqualTo("pve");
        assertThat(m.get(1).match()).containsExactly("*.ts.net");
        assertThat(m.get(1).toggle()).isEqualTo("wake");
    }

    @Test
    void aDuplicateMachineIdIsAnError() {
        assertError("""
                machines:
                  - id: "a"
                    name: "A"
                  - id: "a"
                    name: "B"
                """, "machines[1].id", "- id: \"a\"", "Another machine already uses the id 'a'.");
    }

    @Test
    void aBadMachineIdIsAnError() {
        assertError("""
                machines:
                  - id: "Not Ok"
                    name: "A"
                """, "machines[0].id", "id: \"Not Ok\"", "'id' may only use lower-case letters, digits and -.");
    }

    @Test
    void theNameIsRequired() {
        var r = parse("machines:\n  - id: \"a\"\n");
        assertThat(r.errors()).anySatisfy(i -> {
            assertThat(i.path()).isEqualTo("machines[0].name");
            assertThat(i.message()).isEqualTo("'name' is required.");
        });
    }

    @Test
    void inMustNameAnotherKnownTopLevelMachine() {
        String unknown = """
                machines:
                  - id: "a"
                    name: "A"
                    in: "ghost"
                """;
        assertError(unknown, "machines[0].in", "in: \"ghost\"", "There is no machine with the id 'ghost'.");
        String self = """
                machines:
                  - id: "a"
                    name: "A"
                    in: "a"
                """;
        assertError(self, "machines[0].in", "in: \"a\"", "A machine cannot be inside itself.");
        String nested = """
                machines:
                  - id: "a"
                    name: "A"
                    in: "b"
                  - id: "b"
                    name: "B"
                    in: "c"
                  - id: "c"
                    name: "C"
                """;
        assertError(nested, "machines[0].in", "in: \"b\"", "'b' is itself inside another machine. Only one level of nesting is allowed.");
    }

    @Test
    void powerAndToggleMustNameKnownActions() {
        String power = """
                machines:
                  - id: "a"
                    name: "A"
                    power:
                      - "wake"
                      - "nope"
                """;
        assertError(power, "machines[0].power[1]", "- \"nope\"", "There is no action with the id 'nope'.");
        String toggle = """
                machines:
                  - id: "a"
                    name: "A"
                    toggle: "nope"
                """;
        assertError(toggle, "machines[0].toggle", "toggle: \"nope\"", "There is no action with the id 'nope'.");
    }

    @Test
    void onMustNameAKnownMachine() {
        String y = BASE.replace("href: \"http://192.0.2.5/\"", "href: \"http://192.0.2.5/\"\n        on: \"ghost\"")
                + "machines:\n  - id: \"a\"\n    name: \"A\"\n";
        var r = parser.parse(y);
        assertThat(r.errors()).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("groups[0].items[0].on");
            assertThat(i.line()).isEqualTo(y.lines().toList().indexOf("        on: \"ghost\"") + 1);
            assertThat(i.message()).isEqualTo("There is no machine with the id 'ghost'.");
        });
    }

    @Test
    void onWithoutAMachinesSectionIsOnlyAWarning() {
        String y = BASE.replace("href: \"http://192.0.2.5/\"", "href: \"http://192.0.2.5/\"\n        on: \"a\"");
        var r = parser.parse(y);
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).singleElement().satisfies(i -> {
            assertThat(i.level()).isEqualTo(Issue.Level.WARNING);
            assertThat(i.path()).isEqualTo("groups[0].items[0].on");
            assertThat(i.line()).isEqualTo(y.lines().toList().indexOf("        on: \"a\"") + 1);
            assertThat(i.message()).isEqualTo("'on' is ignored because there is no 'machines:' section.");
        });
    }

    @Test
    void oneAddressOnTwoMachinesIsAWarning() {
        String m = """
                machines:
                  - id: "a"
                    name: "A"
                    addresses:
                      - "192.0.2.5"
                  - id: "b"
                    name: "B"
                    addresses:
                      - "192.0.2.5"
                """;
        var r = parse(m);
        assertThat(r.ok()).isTrue();
        assertThat(issues(r, Issue.Level.WARNING)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("machines[1].addresses");
            assertThat(i.line()).isEqualTo((BASE + m).lines().toList().lastIndexOf("    addresses:") + 1);
            assertThat(i.message()).isEqualTo("The address '192.0.2.5' is also listed on machine 'a'.");
        });
    }

    @Test
    void unknownKeysInAMachineAreReportedLikeElsewhere() {
        String m = """
                machines:
                  - id: "a"
                    name: "A"
                    colour: "red"
                """;
        var r = parse(m);
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).singleElement().satisfies(i -> {
            assertThat(i.level()).isEqualTo(Issue.Level.WARNING);
            assertThat(i.path()).isEqualTo("machines[0].colour");
            assertThat(i.line()).isEqualTo(lineOf(m, "colour"));
            assertThat(i.message()).isEqualTo("Unknown key 'colour' is ignored.");
        });
    }

    @Test
    void badAddressesAndGlobsAreErrors() {
        assertError("machines:\n  - id: \"a\"\n    name: \"A\"\n    addresses:\n      - \"bad host!\"\n",
                "machines[0].addresses[0]", "bad host!", "'bad host!' is not a valid entry for 'addresses'.");
        assertError("machines:\n  - id: \"a\"\n    name: \"A\"\n    match:\n      - \"a/b\"\n",
                "machines[0].match[0]", "a/b", "is not a valid entry for 'match'.");
        assertError("machines:\n  - id: \"a\"\n    name: \"A\"\n    addresses: \"x\"\n",
                "machines[0].addresses", "addresses:", "'addresses' must be a list.");
    }

    @Test
    void machinesMustBeAList() {
        assertError("machines:\n  id: \"a\"\n", "machines", "id: \"a\"", "'machines' must be a list.");
    }
}
