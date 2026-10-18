import { describe, expect, it } from "vitest";
import { formatBytes, groupByProject, matches, memAvailable, memUsed, parseSize, pct, ports, runToCompose, runToEnv, shortTime, splitArgs, splitLogLine, tone, webPort } from "./format";
import type { Container, RunSpec } from "./types";

const c = (over: Partial<Container>): Container => ({
  id: "a".repeat(64), name: "web", image: "nginx", state: "running", status: "Up", health: null, ports: "", created: "", runningFor: "",
  project: null, service: null, stack: null, protected: false, protectedReason: null, canUpdate: false, canRemove: false, ...over,
});

describe("tone", () => {
  it("reflects state and health", () => {
    expect(tone(c({}))).toBe("ok");
    expect(tone(c({ health: "unhealthy" }))).toBe("bad");
    expect(tone(c({ health: "starting" }))).toBe("warn");
    expect(tone(c({ state: "restarting" }))).toBe("warn");
    expect(tone(c({ state: "exited" }))).toBe("off");
  });
});

describe("matches", () => {
  it("filters by state and text", () => {
    expect(matches(c({ state: "exited" }), "", "running")).toBe(false);
    expect(matches(c({ state: "exited" }), "", "stopped")).toBe(true);
    expect(matches(c({ health: "unhealthy" }), "", "unhealthy")).toBe(true);
    expect(matches(c({}), "", "unhealthy")).toBe(false);
    expect(matches(c({ image: "jellyfin/jellyfin" }), "JELLY", "all")).toBe(true);
    expect(matches(c({ project: "media" }), "med", "all")).toBe(true);
    expect(matches(c({}), "nope", "all")).toBe(false);
  });
});

describe("groupByProject", () => {
  it("sorts projects and puts standalone containers last", () => {
    const g = groupByProject([c({ name: "x" }), c({ name: "b", project: "zeta" }), c({ name: "a", project: "alpha", stack: "alpha" })]);
    expect(g.map((x) => x.project)).toEqual(["alpha", "zeta", null]);
    expect(g[0].stack).toBe(true);
    expect(g[1].stack).toBe(false);
  });
});

describe("ports", () => {
  it("dedupes ipv4/ipv6 and keeps specific addresses", () => {
    expect(ports("0.0.0.0:8096->8096/tcp, [::]:8096->8096/tcp, 53/udp")).toEqual(["8096→8096/tcp", "53/udp"]);
    expect(ports(":::8080->80/tcp")).toEqual(["8080→80/tcp"]);
    expect(ports("127.0.0.1:5432->5432/tcp")).toEqual(["127.0.0.1:5432→5432/tcp"]);
    expect(ports("")).toEqual([]);
    expect(webPort("53/udp, 0.0.0.0:8080->80/tcp")).toBe(8080);
    expect(webPort("127.0.0.1:5432->5432/tcp")).toBeNull();
  });
});

describe("logs", () => {
  it("splits docker timestamps", () => {
    expect(splitLogLine("2026-10-02T10:00:01.123456789Z hello world")).toEqual({ ts: "2026-10-02T10:00:01.123456789Z", text: "hello world" });
    expect(splitLogLine("no stamp")).toEqual({ ts: null, text: "no stamp" });
    expect(shortTime("2026-10-02T10:00:01.12Z")).toBe("10:00:01");
  });
});

describe("numbers", () => {
  it("formats percent and memory", () => {
    expect(pct(null)).toBe("–");
    expect(pct(3.14159)).toBe("3.1%");
    expect(pct(42.6)).toBe("43%");
    expect(memUsed("120.5MiB / 3.704GiB")).toBe("120.5 MiB");
    expect(memUsed("")).toBe("–");
  });
});

describe("splitArgs", () => {
  it("groups quotes without shell expansion", () => {
    expect(splitArgs(`nginx -g 'daemon off;'`)).toEqual(["nginx", "-g", "daemon off;"]);
    expect(splitArgs(`echo "" $(x)`)).toEqual(["echo", "", "$(x)"]);
    expect(splitArgs("   ")).toEqual([]);
  });
});

describe("runToCompose", () => {
  const spec: RunSpec = {
    image: "ghcr.io/me/app:1.2", name: "app", ports: ["8080:80", ""], volumes: [{ source: "/srv/app", target: "/data", readOnly: true }],
    env: [{ key: "TOKEN", value: "s3cret" }], restart: "always", network: "proxy", command: ["serve", "--port", "80"],
  };
  it("writes an equivalent service with env values kept in .env", () => {
    const y = runToCompose(spec);
    expect(y).toContain("  app:\n    image: ghcr.io/me/app:1.2\n    container_name: app\n    restart: always");
    expect(y).toContain('      - "8080:80"');
    expect(y).toContain('      - "/srv/app:/data:ro"');
    expect(y).toContain("      TOKEN: ${TOKEN}");
    expect(y).toContain('command: ["serve", "--port", "80"]');
    expect(y).toContain("networks:\n  proxy:\n    external: true");
    expect(y).not.toContain("s3cret");
    expect(runToEnv(spec)).toBe("TOKEN=s3cret\n");
  });
  it("derives a service name from the image", () => {
    expect(runToCompose({ ...spec, name: "", network: "", env: [], command: [] })).toContain("services:\n  app:\n");
  });
});

describe("memory accounting and sizes", () => {
  it("treats a zero memory limit as no memory cgroup on the host", () => {
    expect(memAvailable("0B / 0B")).toBe(false);
    expect(memAvailable("12.5MiB / 0B")).toBe(false);
    expect(memAvailable("120.5MiB / 3.704GiB")).toBe(true);
    expect(memAvailable("")).toBe(true);
    expect(memAvailable(null)).toBe(true);
  });

  it("parses docker's human sizes", () => {
    expect(parseSize("187MB")).toBe(187e6);
    expect(parseSize("1.5GB")).toBe(1.5e9);
    expect(parseSize("3.704GiB")).toBeCloseTo(3.704 * 1024 ** 3);
    expect(parseSize("0B")).toBe(0);
    expect(parseSize("12.3kB")).toBeCloseTo(12300);
    expect(parseSize("N/A")).toBeNull();
    expect(parseSize(undefined)).toBeNull();
  });

  it("formats bytes the way docker does", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(187e6)).toBe("187 MB");
    expect(formatBytes(1.53e9)).toBe("1.5 GB");
    expect(formatBytes(12300)).toBe("12.3 kB");
  });
});
