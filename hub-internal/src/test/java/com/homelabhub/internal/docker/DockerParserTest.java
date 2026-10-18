package com.homelabhub.internal.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Output as the docker CLI prints it with the hub's --format strings. */
class DockerParserTest {

    static final String PS = """
            {"id":"bbb0000000000000000000000000000000000000000000000000000000000002","name":"jellyfin","image":"jellyfin/jellyfin:latest","state":"running","status":"Up 3 hours (healthy)","ports":"0.0.0.0:8096->8096/tcp","created":"2026-09-30 10:00:00 +0000 UTC","runningFor":"2 days ago","project":"media","service":"jellyfin","workdir":"/home/hubdocker/stacks/media","configFiles":"/home/hubdocker/stacks/media/compose.yaml","self":""}
            {"id":"aaa0000000000000000000000000000000000000000000000000000000000001","name":"hub-internal","image":"homelab-hub-internal:local","state":"running","status":"Up 5 hours (unhealthy)","ports":"","created":"x","runningFor":"5 hours ago","project":"deploy","service":"hub-internal","workdir":"/home/me/homelab-hub/deploy","configFiles":"/home/me/homelab-hub/deploy/docker-compose.yml,/home/me/extra.yml","self":"true"}
            WARNING: something the CLI printed
            {"id":"ccc0000000000000000000000000000000000000000000000000000000000003","name":"old","image":"alpine","state":"exited","status":"Exited (0) 3 weeks ago","ports":"","created":"x","runningFor":"3 weeks ago","project":"","service":"","workdir":"","configFiles":"","self":""}
            """;

    @Test
    void containers() {
        List<DockerParser.Container> cs = DockerParser.containers(PS);
        assertThat(cs).extracting(DockerParser.Container::name).containsExactly("hub-internal", "jellyfin", "old");
        DockerParser.Container hub = cs.get(0);
        assertThat(hub.self()).isTrue();
        assertThat(hub.health()).isEqualTo("unhealthy");
        assertThat(hub.configFiles()).containsExactly("/home/me/homelab-hub/deploy/docker-compose.yml", "/home/me/extra.yml");
        assertThat(cs.get(1).health()).isEqualTo("healthy");
        assertThat(DockerService.managedStack(cs.get(1))).isTrue();
        assertThat(DockerService.managedStack(hub)).isFalse();
        DockerParser.Container old = cs.get(2);
        assertThat(old.state()).isEqualTo("exited");
        assertThat(old.project()).isNull();
        assertThat(old.health()).isNull();
        assertThat(old.configFiles()).isEmpty();
        assertThat(DockerParser.containers("")).isEmpty();
    }

    @Test
    void stats() {
        String out = """
                {"BlockIO":"1.2MB / 0B","CPUPerc":"12.50%","Container":"aaa","ID":"aaa0000000000000","MemPerc":"3.10%","MemUsage":"120MiB / 3.7GiB","Name":"jellyfin","NetIO":"1kB / 2kB","PIDs":"14"}
                {"BlockIO":"--","CPUPerc":"--","ID":"ccc","MemPerc":"--","MemUsage":"0B / 0B","Name":"old","NetIO":"--","PIDs":"0"}
                """;
        List<DockerParser.Stats> s = DockerParser.stats(out);
        assertThat(s).hasSize(2);
        assertThat(s.get(0).cpuPercent()).isEqualTo(12.5);
        assertThat(s.get(0).memPercent()).isEqualTo(3.1);
        assertThat(s.get(0).memUsage()).isEqualTo("120MiB / 3.7GiB");
        assertThat(s.get(1).cpuPercent()).isNull();
    }

    @Test
    void namesAreFiltered() {
        assertThat(DockerParser.names("web\n\nmedia\nBad Name\n../x\nweb\n", DockerCommands.STACK)).containsExactly("media", "web");
    }

    @Test
    void imagesAreMergedByIdWithoutNoneTags() {
        List<DockerParser.Image> images = DockerParser.images(DockerTestServer.IMAGES + "not json\n{\"ID\":\"bad id\"}\n");
        assertThat(images).extracting(DockerParser.Image::id).containsExactly("sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64), "sha256:" + "4".repeat(64));
        assertThat(images.get(2).tags()).containsExactly("nginx:1.27", "nginx:latest");
        assertThat(images.get(2).size()).isEqualTo("187MB");
        assertThat(images.get(3).tags()).isEmpty();
        assertThat(images.get(3).created()).isEqualTo("2 days ago");
    }

    @Test
    void networksLeaveOutDockersOwn() {
        String out = """
                {"Name":"proxy","Driver":"bridge","CreatedAt":"x"}
                {"Name":"bridge","Driver":"bridge"}
                {"Name":"host","Driver":"host"}
                {"Name":"old-net","Driver":"bridge"}
                {"Name":"bad name"}
                """;
        assertThat(DockerParser.networks(out)).extracting(DockerParser.Network::name).containsExactly("old-net", "proxy");
    }

    @Test
    void imageIdsAreNormalised() {
        assertThat(DockerParser.imageIds("sha256:" + "a".repeat(64) + "\n" + "b".repeat(64) + "\nError: nope\n"))
                .containsExactlyInAnyOrder("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64));
    }
}
