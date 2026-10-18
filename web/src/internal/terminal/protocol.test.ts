import { describe, expect, it } from "vitest";
import { authMessage, closeText, duration, elapsed, inputMessages, parseDetail, parseServerMessage, resizeMessage, socketUrl } from "./protocol";

describe("messages to the server", () => {
  it("builds auth and resize messages", () => {
    expect(JSON.parse(authMessage("t0k", 80, 24))).toEqual({ type: "auth", ticket: "t0k", cols: 80, rows: 24 });
    expect(JSON.parse(resizeMessage(120, 40))).toEqual({ type: "resize", cols: 120, rows: 40 });
  });
  it("splits big input under the message limit and keeps it intact", () => {
    const big = "x".repeat(20_000);
    const msgs = inputMessages(big);
    expect(msgs).toHaveLength(3);
    for (const m of msgs) expect(new TextEncoder().encode(m).length).toBeLessThan(65536);
    expect(msgs.map((m) => JSON.parse(m).data).join("")).toBe(big);
  });
  it("never splits an emoji", () => {
    const text = "a" + "😀".repeat(5);
    const msgs = inputMessages(text, 2);
    for (const m of msgs) expect(JSON.parse(m).data).not.toMatch(/[\ud800-\udbff]$/);
    expect(msgs.map((m) => JSON.parse(m).data).join("")).toBe(text);
  });
  it("sends nothing for empty input", () => {
    expect(inputMessages("")).toEqual([]);
  });
});

describe("parseServerMessage", () => {
  it("parses the known frames", () => {
    expect(parseServerMessage('{"type":"ready","host":"pi","label":"Pi","user":"hubshell"}')).toEqual({ type: "ready", host: "pi", label: "Pi", user: "hubshell" });
    expect(parseServerMessage('{"type":"warn","secondsLeft":60}')).toEqual({ type: "warn", secondsLeft: 60 });
    expect(parseServerMessage('{"type":"exit","reason":"idle","message":"bye"}')).toEqual({ type: "exit", reason: "idle", message: "bye" });
    expect(parseServerMessage('{"type":"exit","reason":"closed","message":"m","exitCode":3}')).toMatchObject({ exitCode: 3 });
    expect(parseServerMessage('{"type":"pong"}')).toEqual({ type: "pong" });
  });
  it("ignores malformed or unknown frames", () => {
    for (const bad of ["", "nope", "null", "[]", "42", '{"type":1}', '{"type":"warn"}', '{"type":"ready"}', '{"type":"future"}']) {
      expect(parseServerMessage(bad)).toBeNull();
    }
  });
});

describe("helpers", () => {
  it("explains closes without an exit message", () => {
    expect(closeText(1008)).toContain("signed out");
    expect(closeText(1006)).toContain("lost");
  });
  it("uses wss on https", () => {
    expect(socketUrl({ protocol: "https:", host: "pi.example.ts.net" })).toBe("wss://pi.example.ts.net/api/admin/terminal/ws");
    expect(socketUrl({ protocol: "http:", host: "192.168.1.5:3010" })).toBe("ws://192.168.1.5:3010/api/admin/terminal/ws");
  });
  it("formats elapsed time", () => {
    expect(elapsed(0)).toBe("0:00");
    expect(elapsed(65_000)).toBe("1:05");
    expect(elapsed(3_725_000)).toBe("1:02:05");
  });
  it("words durations", () => {
    expect(duration(900)).toBe("15 minutes");
    expect(duration(7200)).toBe("2 hours");
    expect(duration(3600)).toBe("1 hour");
    expect(duration(12)).toBe("12 seconds");
    expect(duration(1)).toBe("1 second");
    expect(duration(90)).toBe("2 minutes");
  });
  it("reads audit details", () => {
    expect(parseDetail("session=ab reason=idle duration_s=12")).toEqual({ session: "ab", reason: "idle", duration_s: "12" });
    expect(parseDetail(null)).toEqual({});
  });
});
