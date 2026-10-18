// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";

/** Runs public/sw.js against a fake worker scope so its decisions can be tested without a browser or a network. */
const code = readFileSync(new URL("./public/sw.js", import.meta.url), "utf8");

function makeWorld(fetchImpl: (req: any) => Promise<any>) {
  const stores = new Map<string, Map<string, any>>();
  const cacheApi = {
    open: async (name: string) => {
      if (!stores.has(name)) stores.set(name, new Map());
      const m = stores.get(name)!;
      return { add: async (u: string) => { m.set(u, { ok: true, type: "basic", url: u, clone() { return this; } }); }, match: async (r: any) => m.get(typeof r === "string" ? r : r.url), put: async (r: any, res: any) => { m.set(r.url, res); }, keys: async () => [...m.keys()] };
    },
    keys: async () => [...stores.keys()],
    delete: async (n: string) => stores.delete(n),
    match: async (r: any) => { for (const m of stores.values()) { const h = m.get(typeof r === "string" ? r : r.url); if (h) return h; } return undefined; },
  };
  const handlers: Record<string, (e: any) => void> = {};
  const self = { location: { origin: "https://hub.test" }, addEventListener: (t: string, f: any) => { handlers[t] = f; }, clients: { claim: vi.fn(async () => {}) } };
  const fetchSpy = vi.fn(fetchImpl);
  new Function("self", "caches", "fetch", "Response", "URL", code)(self, cacheApi, fetchSpy, Response, URL);
  const fire = async (type: string, extra: object = {}) => {
    const e: any = { ...extra, promise: undefined as Promise<any> | undefined, respondWith(p: any) { this.promise = Promise.resolve(p); }, waitUntil(p: any) { this.promise = Promise.resolve(p); } };
    handlers[type](e);
    return { e, result: e.promise ? await e.promise : undefined };
  };
  return { stores, fire, fetchSpy, self };
}
const req = (url: string, over: object = {}) => ({ method: "GET", url: "https://hub.test" + url, mode: "cors", ...over });
const ok = (body = "x") => ({ ok: true, type: "basic", body, clone() { return this; } });

describe("service worker", () => {
  it("installs only the offline page", async () => {
    const w = makeWorld(async () => ok());
    await w.fire("install");
    expect([...w.stores.keys()]).toEqual(["hub-shell-v1"]);
    expect([...w.stores.get("hub-shell-v1")!.keys()]).toEqual(["/offline.html"]);
  });

  it("on activation removes caches from other versions and keeps the current ones", async () => {
    const w = makeWorld(async () => ok());
    await w.fire("install");
    (await (w as any)).stores.set("hub-assets-v0", new Map());
    (await (w as any)).stores.set("something-else", new Map());
    (await (w as any)).stores.set("hub-assets-v1", new Map());
    await w.fire("activate");
    expect([...w.stores.keys()].sort()).toEqual(["hub-assets-v1", "hub-shell-v1"]);
    expect(w.self.clients.claim).toHaveBeenCalled();
  });

  it("never touches API calls, images, other methods, other origins or ordinary requests", async () => {
    const w = makeWorld(async () => ok());
    for (const r of [req("/api/dashboard"), req("/api/admin/config", { method: "PUT" }), req("/images/logo.png"), req("/wake/mac/AA"), req("/assets/x.js", { method: "POST" }), { method: "GET", url: "https://evil.test/assets/x.js", mode: "cors" }]) {
      const { e } = await w.fire("fetch", { request: r });
      expect(e.promise, JSON.stringify(r)).toBeUndefined();
    }
    expect(w.fetchSpy).not.toHaveBeenCalled();
    expect(w.stores.size).toBe(0);
  });

  it("serves fingerprinted assets from the cache after the first fetch", async () => {
    const w = makeWorld(async () => ok("js"));
    await w.fire("fetch", { request: req("/assets/app-abc.js") });
    expect(w.fetchSpy).toHaveBeenCalledTimes(1);
    const second = await w.fire("fetch", { request: req("/assets/app-abc.js") });
    expect(w.fetchSpy).toHaveBeenCalledTimes(1);
    expect(second.result.body).toBe("js");
  });

  it("does not cache failed or opaque asset responses", async () => {
    const w = makeWorld(async () => ({ ok: false, type: "basic", clone() { return this; } }));
    await w.fire("fetch", { request: req("/assets/missing.js") });
    await w.fire("fetch", { request: req("/assets/missing.js") });
    expect(w.fetchSpy).toHaveBeenCalledTimes(2);
    const o = makeWorld(async () => ({ ok: true, type: "opaque", clone() { return this; } }));
    await o.fire("fetch", { request: req("/assets/o.js") });
    expect([...(o.stores.get("hub-assets-v1")?.keys() ?? [])]).toEqual([]);
  });

  it("lets page navigations through, and shows the offline page when the server cannot be reached", async () => {
    let up = true;
    const w = makeWorld(async () => { if (!up) throw new TypeError("network"); return ok("live page"); });
    await w.fire("install");
    expect((await w.fire("fetch", { request: req("/", { mode: "navigate" }) })).result.body).toBe("live page");
    up = false;
    const off = await w.fire("fetch", { request: req("/#/config", { mode: "navigate" }) });
    expect(off.result.url).toBe("/offline.html");
  });

  it("falls back to a network error when even the offline page is missing", async () => {
    const w = makeWorld(async () => { throw new TypeError("network"); });
    const off = await w.fire("fetch", { request: req("/", { mode: "navigate" }) });
    expect(off.result.type).toBe("error");
  });
});
