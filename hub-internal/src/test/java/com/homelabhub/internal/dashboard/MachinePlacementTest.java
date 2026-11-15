package com.homelabhub.internal.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.config.ConfigParser;
import com.homelabhub.internal.config.DashboardConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MachinePlacementTest {

    private final ConfigParser parser = new ConfigParser(name -> true);

    private Map<String, String> place(String yaml) {
        ConfigParser.Result r = parser.parse(yaml);
        assertThat(r.ok()).as(r.issues().toString()).isTrue();
        return MachinePlacement.place(r.config());
    }

    private static final String MACHINES = """
            machines:
              - id: "a"
                name: "A"
                match:
                  - "*.ts.net"
                  - "*.lab.example.test"
              - id: "b"
                name: "B"
                addresses:
                  - "192.0.2.5"
                  - "Box.Example.Test"
                  - "[2001:db8::1]"
              - id: "c"
                name: "C"
                in: "b"
                addresses:
                  - "192.0.2.6"
                power:
                  - "wake-c"
                toggle: "toggle-c"
            actions:
              - id: wake-c
                label: Wake C
                type: wol
                mac: "02:00:00:00:00:01"
              - id: toggle-c
                label: Toggle C
                type: proxmox-guest
                node: pve
                kind: lxc
                vmid: 100
              - id: other
                label: Other
                type: proxmox-node
                node: pve
                command: reboot
            """;

    private Map<String, String> items(String itemsYaml) {
        return place("version: 1\ngroups:\n  - name: \"G\"\n    items:\n" + itemsYaml + MACHINES);
    }

    @Test
    void explicitOnBeatsAnAddressMatch() {
        var p = items("""
                      - name: "X"
                        href: "http://192.0.2.5:80/"
                        on: "a"
                """);
        assertThat(p).containsEntry("g0.0", "a");
    }

    @Test
    void anAddressOnALaterMachineBeatsAGlobOnAnEarlierOne() {
        var p = items("""
                      - name: "X"
                        href: "https://Box.Example.Test/x"
                """);
        assertThat(p).containsEntry("g0.0", "b");
        var q = items("""
                      - name: "Y"
                        href: "https://box.example.test.ts.net/"
                """);
        assertThat(q).containsEntry("g0.0", "a");
    }

    @Test
    void anAddressAndAGlobOnTheSameHostPickTheAddressMachineEvenWhenTheGlobMachineIsFirst() {
        var r = parser.parse("""
                version: 1
                groups:
                  - name: "G"
                    items:
                      - name: "X"
                        href: "https://svc.lab.example.test/"
                machines:
                  - id: "glob"
                    name: "Glob"
                    match:
                      - "*.example.test"
                  - id: "exact"
                    name: "Exact"
                    addresses:
                      - "SVC.lab.example.test"
                """);
        assertThat(r.ok()).isTrue();
        assertThat(MachinePlacement.place(r.config())).containsEntry("g0.0", "exact");
    }

    @Test
    void aTailnetHostnameMatchesTheGlob() {
        var p = items("""
                      - name: "X"
                        href: "https://wiki.sphynx.ts.net/"
                      - name: "Y"
                        href: "https://ts.net/"
                """);
        assertThat(p).containsEntry("g0.0", "a").doesNotContainKey("g0.1");
    }

    @Test
    void anItemWithoutAnHrefIsPlacedByItsStatusTargetThenItsWidgetUrl() {
        var p = items("""
                      - name: "X"
                        status:
                          icmp: 192.0.2.5
                      - name: "Y"
                        status:
                          tcp: "192.0.2.6:22"
                      - name: "Z"
                        href: "/local"
                        widget: {type: filebrowser, url: "http://192.0.2.6:8080", username: u, password: "${secret:P}"}
                """);
        assertThat(p).containsEntry("g0.0", "b").containsEntry("g0.1", "c").containsEntry("g0.2", "c");
    }

    @Test
    void anUnplacedItemHasNoMachine() {
        var p = items("""
                      - name: "X"
                        href: "http://192.0.2.77/"
                      - name: "Y"
                        href: "/relative"
                      - name: "Z"
                """);
        assertThat(p).isEmpty();
    }

    @Test
    void actionTilesFollowPowerAndToggle() {
        var p = items("""
                      - name: "W"
                        action: wake-c
                      - name: "T"
                        action: toggle-c
                      - name: "O"
                        action: other
                """);
        assertThat(p).containsEntry("g0.0", "c").containsEntry("g0.1", "c").doesNotContainKey("g0.2");
    }

    @Test
    void ipv6BracketsPortsAndCaseAreHandled() {
        var p = items("""
                      - name: "X"
                        href: "http://[2001:DB8::1]:8080/path"
                """);
        assertThat(p).containsEntry("g0.0", "b");
    }

    @Test
    void noMachinesMeansNothingIsPlaced() {
        assertThat(place("version: 1\ngroups:\n  - name: \"G\"\n    items:\n      - name: \"X\"\n        href: \"http://192.0.2.5/\"\n")).isEmpty();
    }

    @Test
    void hostExtractionCoversTheShapesInConfigs() {
        assertThat(MachinePlacement.hostOf("https://User:pw@Host.Example:8443/a?b#c")).isEqualTo("host.example");
        assertThat(MachinePlacement.hostOf("192.0.2.5:3000")).isEqualTo("192.0.2.5");
        assertThat(MachinePlacement.hostOf("192.0.2.5")).isEqualTo("192.0.2.5");
        assertThat(MachinePlacement.hostOf("pi.local.")).isEqualTo("pi.local");
        assertThat(MachinePlacement.hostOf("[::1]:22")).isEqualTo("::1");
        assertThat(MachinePlacement.hostOf("/docs")).isNull();
        assertThat(MachinePlacement.hostOf(null)).isNull();
        assertThat(MachinePlacement.globMatches("*.ts.net", "A.B.TS.NET")).isTrue();
        assertThat(MachinePlacement.globMatches("host-*", "host-1")).isTrue();
        assertThat(MachinePlacement.globMatches("a.b", "axb")).isFalse();
    }

    @Test
    void aSanitisedRealisticConfigIsPlacedByTheRules() throws IOException {
        String yaml = new String(getClass().getResourceAsStream("/fixtures/realistic-dashboard.yaml").readAllBytes(), StandardCharsets.UTF_8);
        ConfigParser.Result r = new ConfigParser(name -> true).parse(yaml);
        assertThat(r.ok()).as(r.issues().toString()).isTrue();
        assertThat(r.issues()).isEmpty();
        DashboardConfig c = r.config();
        assertThat(c.machines()).extracting(DashboardConfig.Machine::id).containsExactly("pi", "pve", "apps", "media", "tailnet");
        Map<String, String> byName = new LinkedHashMap<>();
        collect(c.groups(), MachinePlacement.place(c), byName);
        assertThat(byName).containsEntry("Hypervisor (Tailnet)", "tailnet")
                .containsEntry("Backup Server", "pve")
                .containsEntry("Graphs", "pi")
                .containsEntry("Metrics", "pi")
                .containsEntry("Wiki", "apps")
                .containsEntry("Notes", "tailnet")
                .containsEntry("Hostname only", "apps")
                .containsEntry("Files", "media")
                .containsEntry("Wake Hypervisor", "pve")
                .containsEntry("Shutdown Hypervisor", "pve")
                .containsEntry("Toggle Apps", "apps")
                .containsEntry("Toggle Media", "media")
                .doesNotContainKey("Loose")
                .doesNotContainKey("Toggle Spare");
    }

    private static void collect(List<? extends DashboardConfig.Entry> es, Map<String, String> p, Map<String, String> out) {
        for (DashboardConfig.Entry e : es) {
            if (e instanceof DashboardConfig.Group g) {
                collect(g.entries(), p, out);
            } else if (p.containsKey(e.id())) {
                out.put(e.name(), p.get(e.id()));
            }
        }
    }
}
