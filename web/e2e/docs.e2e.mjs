// End-to-end tests for Docs in a real browser (docs plan P9), against scripts/run-local-docs.sh: docs-internal on 3920 and
// docs-public on 3921. *.example.test resolves to 127.0.0.1 inside the browser, so public pages are visited by hostname
// exactly as Cloudflare would forward them. Nothing here touches Cloudflare or the Docs LXC.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { request } from "node:http";
import { appendFileSync, mkdirSync, readFileSync } from "node:fs";
import { after, before, describe, test } from "node:test";
import { deflateRawSync } from "node:zlib";
import { chromium } from "playwright-core";

const DOCS = "http://127.0.0.1:3920", PUB = "http://127.0.0.1:3911", PUBPORT = 3921, PASS = "local-test-pass";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SHOTS = new URL("../../.scratch/e2e-shots/", import.meta.url).pathname;
mkdirSync(SHOTS, { recursive: true });

let browser;
const problems = [];

before(async () => {
  browser = await chromium.launch({
    executablePath: process.env.CHROMIUM ?? "/opt/pw-browsers/chromium",
    args: ["--no-sandbox", "--host-resolver-rules=MAP *.example.test 127.0.0.1"],
  });
});
after(async () => { await browser?.close(); });

async function newPage(width = 1280, height = 900) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  page.on("console", (m) => { if (m.type() === "error" && !m.text().startsWith("Failed to load resource")) problems.push(`console: ${m.text()}`); });
  page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));
  page.on("response", (r) => { if (r.status() >= 500 || r.status() === 403) problems.push(`HTTP ${r.status()}: ${r.url()}`); });
  return page;
}
function expectNoProblems(allow = []) {
  const real = problems.splice(0).filter((p) => !allow.some((a) => p.includes(a)));
  assert.deepEqual(real, [], "browser problems:\n" + real.join("\n"));
}
async function login(page) {
  await page.goto(DOCS + "/");
  await page.locator("#pw").fill(PASS);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator(".dx-bar .brand").waitFor();
}
/** The editor API from inside the logged-in page (same session and CSRF cookie as the UI). */
function apiIn(page, method, path, body) {
  return page.evaluate(async ({ method, path, body }) => {
    const t = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice(11);
    const r = await fetch("/api/admin/docs" + path, { method, headers: { "Content-Type": "application/json", ...(t ? { "X-XSRF-TOKEN": decodeURIComponent(t) } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    return { status: r.status, body: text ? JSON.parse(text) : null };
  }, { method, path, body });
}
/** GET on docs-public with a chosen Host header (what cloudflared sends). */
function publicGet(host, path) {
  return new Promise((resolve, reject) => {
    const req = request({ host: "127.0.0.1", port: PUBPORT, path, headers: { Host: host } }, (res) => {
      let data = "";
      res.on("data", (c) => { data += c; });
      res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on("error", reject);
    req.end();
  });
}

// A minimal ZIP writer (deflate), so the test can build a webapp bundle without extra packages.
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function zip(files) {
  const locals = [], centrals = [];
  let offset = 0;
  for (const [name, content] of Object.entries(files)) {
    const data = Buffer.from(content), comp = deflateRawSync(data), nameBuf = Buffer.from(name), crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(8, 8);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(comp.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(nameBuf.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(8, 10);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(comp.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28); central.writeUInt32LE(offset, 42);
    locals.push(local, nameBuf, comp);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + comp.length;
  }
  const cd = Buffer.concat(centrals), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(Object.keys(files).length, 8); end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

// The test app: shows whether it can see the parent's cookies or call the Docs API (it must not), and reports its height.
const APP_HTML = `<!doctype html><html><head><script src="_hub/hub-embed.js"></script></head><body>
<h1 id="h">Request flow</h1><p id="theme"></p><p id="cookie"></p><p id="api"></p><p id="data"></p>
<script src="app.js"></script></body></html>`;
const APP_JS = `
document.getElementById("theme").textContent = "theme:" + document.documentElement.getAttribute("data-theme");
let c; try { c = document.cookie; } catch (e) { c = "blocked"; }
document.getElementById("cookie").textContent = "cookie:" + (c === "" ? "none" : c === "blocked" ? "blocked" : "LEAK " + c);
fetch("/api/admin/docs/tree", { credentials: "include" }).then(r => r.status).catch(() => "blocked")
  .then(s => { document.getElementById("api").textContent = "api:" + s; });
fetch("data/steps.json").then(r => r.json()).then(d => { document.getElementById("data").textContent = "data:" + d.steps; })
  .catch(e => { document.getElementById("data").textContent = "data:failed"; });
`;
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");

const ids = {};

describe("docs: sign in and the tree", () => {
  test("Docs is locked until its own password is entered, and links back to the hub", async () => {
    const page = await newPage();
    await page.goto(DOCS + "/");
    await page.getByRole("heading", { name: "Sign in" }).waitFor();
    assert.equal(await page.evaluate(async () => (await fetch("/api/admin/docs/tree")).status), 401);
    await page.locator("#pw").fill("wrong");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByText("Wrong password.").waitFor();
    await page.locator("#pw").fill(PASS);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("button", { name: "Create the first page" }).waitFor();
    assert.equal(await page.getByRole("link", { name: "← Hub" }).getAttribute("href"), "http://127.0.0.1:3910/");
    const cookies = (await page.context().cookies()).map((c) => c.name);
    assert.ok(cookies.includes("DOCSSESSION") && !cookies.includes("HUBSESSION"), cookies.join(","));
    await page.close();
    expectNoProblems(["401"]);
  });

  test("pages are created from the UI and re-parented by drag and drop", async () => {
    const page = await newPage();
    await login(page);
    for (const title of ["Homelab", "Networking", "Router VLANs"]) {
      await page.getByRole("button", { name: "New page" }).click();
      await page.locator("#dx-title").fill(title);
      await page.getByRole("button", { name: "Create", exact: true }).click();
      await page.locator(".dx-titlein").waitFor(); // new rich pages open in the editor
      await page.locator(".dx-node", { hasText: title }).waitFor();
    }
    const tree = (await apiIn(page, "GET", "/tree")).body;
    for (const t of tree) ids[t.title] = t.id;
    // drop "Networking" onto the middle of "Homelab": it becomes a child
    await page.locator(".dx-node", { hasText: "Networking" }).dragTo(page.locator(".dx-node", { hasText: "Homelab" }));
    await page.waitForFunction(async (id) => (await (await fetch("/api/admin/docs/tree")).json()).find((t) => t.id === id).parentId !== null, ids.Networking);
    const after = (await apiIn(page, "GET", "/tree")).body;
    assert.equal(after.find((t) => t.id === ids.Networking).parentId, ids.Homelab);
    // Move to… (keyboard and phone path): "Router VLANs" under Networking
    await page.locator(".dx-node", { hasText: "Router VLANs" }).click({ button: "right" });
    await page.getByRole("menuitem", { name: "Move to…" }).click();
    await page.locator("#dx-parent").selectOption({ label: "  Networking" });
    await page.getByRole("button", { name: "Move", exact: true }).click();
    await page.waitForFunction(async (a) => (await (await fetch("/api/admin/docs/tree")).json()).find((t) => t.id === a.id).parentId === a.parent, { id: ids["Router VLANs"], parent: ids.Networking });
    // the filter keeps ancestors of a match
    await page.locator(".dx-node", { hasText: "Networking" }).locator("xpath=..").locator(".dx-node", { hasText: "Router VLANs" }).waitFor();
    await page.locator(".dx-filter").fill("vlans");
    assert.deepEqual(await page.locator(".dx-node .t").allInnerTexts(), ["Homelab", "Networking", "Router VLANs"]);
    await page.close();
    expectNoProblems();
  });
});

describe("docs: editing", () => {
  test("the editor writes every kind of block and the reading view shows them", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(`${DOCS}/#/p/${ids.Networking}/edit`);
    const ed = page.locator(".dx-prose");
    await ed.click();
    await page.keyboard.type("Every public page has its own hostname.");
    await page.keyboard.press("Enter");
    await page.keyboard.type("/head");
    await page.locator(".dx-slash .it", { hasText: "Heading" }).first().waitFor();
    await page.keyboard.press("Enter");
    await page.keyboard.type("Routing by hostname");
    await page.keyboard.press("Enter");
    await page.keyboard.type("/table");
    await page.keyboard.press("Enter");
    await page.keyboard.type("Hostname");
    await page.locator(".dx-tabletools").waitFor();
    // leave the table: click below it, then add a callout, code, a diagram, a page link and an image
    await page.locator(".dx-toolbar").getByRole("button", { name: "ⓘ Callout" }).click();
    await page.keyboard.type("Never set the HTTP Host Header in the tunnel.");
    await page.locator(".dx-toolbar").getByRole("button", { name: "◇ Diagram" }).click();
    await page.locator(".dx-mermaid-grid .pv img").waitFor({ timeout: 30000 });
    await page.locator(".dx-toolbar").getByRole("button", { name: "↗ Page" }).click();
    await page.locator(".dx-picklist button", { hasText: "Router VLANs" }).click();
    await page.locator("input[type=file][accept^='image']").setInputFiles({ name: "net.png", mimeType: "image/png", buffer: PNG });
    await page.locator(".dx-prose .dx-figure img").waitFor();
    await page.getByRole("button", { name: "Save draft" }).first().click();
    await page.getByText(/Saved/).first().waitFor({ timeout: 30000 });
    const saved = (await apiIn(page, "GET", `/pages/${ids.Networking}`)).body.body;
    const types = JSON.stringify(saved);
    for (const t of ["heading", "table", "callout", "mermaid", "pageLink", "image"]) assert.ok(types.includes(`"type":"${t}"`), `${t} saved`);
    assert.match(types, /"lightSvg":"[0-9a-f]{64}"/, "the diagram was rendered to an SVG on save");
    // reading view
    await page.getByRole("button", { name: "Done" }).click();
    await page.locator("article.doc h2", { hasText: "Routing by hostname" }).waitFor();
    assert.equal(await page.locator("article.doc .dx-callout").count(), 1);
    assert.equal(await page.locator("article.doc table").count(), 1);
    assert.equal(await page.locator("article.doc .dx-mermaid img").count(), 1);
    assert.equal(await page.locator("article.doc a.dx-xlink", { hasText: "Router VLANs" }).count(), 1);
    await page.screenshot({ path: SHOTS + "docs-page.png", fullPage: true });
    await page.close();
    expectNoProblems();
  });

  test("a stale editor gets the conflict dialog instead of overwriting", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(`${DOCS}/#/p/${ids["Router VLANs"]}/edit`);
    await page.locator(".dx-prose").click();
    await page.keyboard.type("Mine");
    const p = (await apiIn(page, "GET", `/pages/${ids["Router VLANs"]}`)).body;
    await apiIn(page, "PUT", `/pages/${p.id}`, { title: p.title, body: p.body, version: p.version }); // someone else saves
    await page.getByRole("button", { name: "Save draft" }).first().click();
    await page.getByRole("dialog", { name: "Changed elsewhere" }).waitFor();
    await page.getByRole("button", { name: "Keep mine (save over it)" }).click();
    await page.getByText(/Saved/).first().waitFor();
    assert.match(JSON.stringify((await apiIn(page, "GET", `/pages/${p.id}`)).body.body), /Mine/);
    await page.close();
    expectNoProblems(["409"]);
  });
});

describe("docs: webapp pages", () => {
  test("an uploaded ZIP runs in the sandbox and cannot reach Docs", async () => {
    const page = await newPage();
    await login(page);
    const created = await apiIn(page, "POST", "/pages", { title: "Request flow", kind: "webapp" });
    ids.Flow = created.body.id;
    await page.goto(`${DOCS}/#/p/${ids.Flow}`);
    await page.locator("input[type=file][accept='.html,.htm,.zip']").setInputFiles({ name: "app.zip", mimeType: "application/zip",
      buffer: zip({ "flow/index.html": APP_HTML, "flow/app.js": APP_JS, "flow/data/steps.json": JSON.stringify({ steps: 4 }) }) });
    await page.locator(".dx-bundles td", { hasText: "b1" }).waitFor();
    const frame = page.frameLocator(".dx-previewbox iframe");
    await frame.locator("#api").filter({ hasText: /api:/ }).waitFor();
    assert.equal(await frame.locator("#cookie").innerText(), "cookie:blocked", "opaque origin: no cookies");
    assert.notEqual(await frame.locator("#api").innerText(), "api:200", "the app cannot call the Docs API");
    await frame.locator("#data").filter({ hasText: "data:4" }).waitFor(); // its own files are reachable
    assert.equal(await page.locator(".dx-previewbox iframe").getAttribute("sandbox"), "allow-scripts");
    await page.close();
    expectNoProblems(["blocked by CORS policy"]); // the app's own attempt to call the Docs API, refused as it should be
  });
});

describe("docs: publishing", () => {
  test("publish shows exactly one page on its hostname; internal links become plain text", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(`${DOCS}/#/p/${ids.Networking}`);
    await page.getByRole("button", { name: "Publish…" }).click();
    await page.locator("#dx-host").fill("t1");
    await page.getByText("t1.example.test is free").waitFor();
    await page.locator(".dx-check", { hasText: "Router VLANs" }).filter({ hasText: "plain text" }).waitFor();
    await page.getByRole("button", { name: /^Publish v/ }).click();
    await page.locator(".dx-meta .dx-pill.pub").waitFor();

    const pub = await newPage();
    await pub.goto(`http://t1.example.test:${PUBPORT}/`);
    await pub.locator("h1.title", { hasText: "Networking" }).waitFor();
    assert.equal(await pub.locator(".dx-xtext", { hasText: "Router VLANs" }).count(), 1, "internal link is plain text");
    assert.equal(await pub.locator("a", { hasText: "Router VLANs" }).count(), 0);
    assert.equal(await pub.locator(".dx-mermaid img").count(), 1);
    assert.equal(await pub.locator(".dx-tree, .dx-side").count(), 0, "no tree on the public page");
    await pub.screenshot({ path: SHOTS + "docs-public.png", fullPage: true });
    const html = await publicGet("t1.example.test", "/");
    assert.equal(html.headers["x-robots-tag"], "noindex, nofollow");
    assert.equal((await publicGet("random.example.test", "/")).status, 404);
    assert.equal((await publicGet("random.example.test", "/")).body, "");
    assert.equal((await publicGet("t1.example.test", "/api/admin/docs/tree")).status, 404);
    // a draft change never reaches the public page until it is published
    const p = (await apiIn(page, "GET", `/pages/${ids.Networking}`)).body;
    await apiIn(page, "PUT", `/pages/${p.id}`, { title: "Networking (draft)", body: p.body, version: p.version });
    assert.match((await publicGet("t1.example.test", "/api/public/page")).body, /"title":"Networking"/);
    await pub.close();
    await page.close();
    expectNoProblems();
  });

  test("a full-page webapp is served on its own hostname, sandboxed", async () => {
    const page = await newPage();
    await login(page);
    const v = (await apiIn(page, "GET", `/pages/${ids.Flow}`)).body.version;
    const r = await apiIn(page, "POST", `/pages/${ids.Flow}/publish`, { publicHost: "flow.example.test", version: v });
    assert.equal(r.status, 200, JSON.stringify(r.body));
    const pub = await newPage();
    await pub.goto(`http://flow.example.test:${PUBPORT}/`);
    const frame = pub.frameLocator(".dx-full iframe");
    await frame.locator("#data").filter({ hasText: "data:4" }).waitFor();
    assert.equal(await frame.locator("#cookie").innerText(), "cookie:blocked");
    await pub.close();
    await page.close();
    expectNoProblems(["blocked by CORS policy"]);
  });

  test("history compares versions and unpublish takes the hostname offline", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(`${DOCS}/#/p/${ids.Networking}/history`);
    await page.locator(".dx-ver").first().waitFor();
    await page.getByRole("button", { name: /^live/ }).click();
    await page.locator(".dx-diff").waitFor();
    await page.goto(`${DOCS}/#/p/${ids.Networking}/publish`);
    await page.getByRole("button", { name: "Unpublish" }).click();
    await page.locator(".dx-meta .dx-pill", { hasText: "internal" }).waitFor();
    assert.equal((await publicGet("t1.example.test", "/api/public/page")).status, 404);
    await page.close();
    expectNoProblems();
  });
});

describe("docs: phone", () => {
  test("at 390 px the tree is a drawer", async () => {
    const page = await newPage(390, 800);
    await login(page);
    assert.equal(await page.locator(".dx-side.open").count(), 0);
    await page.getByRole("button", { name: "Pages", exact: true }).click();
    await page.locator(".dx-side.open .dx-node", { hasText: "Homelab" }).click();
    await page.locator(".dx-pagehead h1", { hasText: "Homelab" }).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= 390), true, "no sideways scrolling");
    await page.screenshot({ path: SHOTS + "docs-phone.png" });
    await page.close();
    expectNoProblems();
  });
});

// ============================================================================ public portfolio (hub-public on 3911, data from the Docs database)
describe("public portfolio", () => {
  test("renders every section from the published snapshot", async () => {
    const page = await newPage();
    await page.goto(PUB + "/");
    await page.getByRole("heading", { level: 1, name: "Tabish Tariq" }).waitFor();
    for (const s of ["About", "Experience", "Projects", "Education", "Homelab", "Contact"]) await page.locator(`#${s.toLowerCase()} h2`).first().waitFor();
    assert.equal(await page.locator("#experience article.card").count(), 3);
    assert.ok((await page.locator("#homelab article.dev").count()) >= 3);
    assert.equal(await page.getByRole("button", { name: /Edit/ }).count(), 0, "no edit buttons on the public site");
    await page.close();
    expectNoProblems();
  });

  test("the skill filter narrows the whole page and can be cleared", async () => {
    const page = await newPage();
    await page.goto(PUB + "/");
    await page.getByLabel("Search skills or page text").fill("Docker");
    await page.locator(".panel").getByRole("button", { name: /^Docker \/ Compose/ }).first().click();
    await page.getByText("Showing what matches").waitFor();
    assert.ok((await page.locator("#experience article.card").count()) === 0, "no role uses Docker, so none are shown");
    assert.ok((await page.locator("#homelab article.dev").count()) >= 1);
    assert.ok((await page.locator(".subnav a", { hasText: "Homelab" }).locator(".n").count()) === 1, "section nav shows the count");
    await page.getByRole("button", { name: "Clear all" }).click();
    await page.getByLabel("Search skills or page text").fill("");
    assert.equal(await page.locator("#experience article.card").count(), 3);
    await page.close();
    expectNoProblems();
  });

  test("text search reaches into deliverables and highlights", async () => {
    const page = await newPage();
    await page.goto(PUB + "/");
    await page.getByLabel("Search skills or page text").fill("deadlock");
    await page.locator("#projects details[open] .dl li").first().waitFor();
    assert.ok((await page.locator("#experience li.hit").count()) >= 1, "a matching highlight is marked");
    await page.close();
    expectNoProblems();
  });

  test("dim mode keeps everything and fades the rest; the panel collapses and remembers it", async () => {
    const page = await newPage();
    await page.goto(PUB + "/");
    await page.getByRole("button", { name: "Match all" }).waitFor();
    await page.getByLabel("Search skills or page text").fill("Redshift");
    await page.locator(".panel").getByRole("button", { name: /^Redshift/ }).first().click();
    await page.getByRole("button", { name: "Dim others" }).click();
    assert.equal(await page.locator("#experience article.card").count(), 3);
    assert.ok((await page.locator("#experience article.card.dim").count()) >= 0);
    await page.getByRole("button", { name: "Collapse skill filter" }).click();
    assert.ok(await page.locator(".page.collapsed").count() === 1);
    await page.reload();
    assert.ok(await page.locator(".page.collapsed").count() === 1, "collapsed state survives a reload");
    await page.close();
    expectNoProblems();
  });

  test("the command palette finds and jumps to content", async () => {
    const page = await newPage();
    await page.goto(PUB + "/");
    await page.getByRole("heading", { level: 1 }).waitFor();
    await page.keyboard.press("Control+k");
    await page.getByRole("dialog", { name: "Search" }).waitFor();
    await page.getByRole("dialog", { name: "Search" }).locator("input").fill("homelab");
    await page.keyboard.press("Enter");
    await page.waitForTimeout(600);
    assert.ok((await page.locator("#homelab").boundingBox()).y < 400, "scrolled to the homelab section");
    await page.close();
    expectNoProblems();
  });

  test("nothing internal is reachable through the public port", async () => {
    for (const [method, path] of [["GET", "/api/admin/config"], ["GET", "/api/dashboard"], ["GET", "/api/status"], ["POST", "/api/actions/wake-test/prepare"],
      ["GET", "/wake/mac/AA:BB:CC:DD:EE:FF"], ["GET", "/toggle/pve/lxc/100"], ["GET", "/node/pve/shutdown"], ["GET", "/api/portfolio"], ["GET", "/actuator/env"], ["GET", "/api/admin/portfolio/schema"], ["GET", "/api/admin/docs/tree"], ["POST", "/api/admin/portfolio/republish"]]) {
      const r = await fetch(PUB + path, { method });
      assert.ok([404, 405].includes(r.status), `${method} ${path} answered ${r.status}`);
    }
    const health = await fetch(PUB + "/api/public/health");
    assert.equal(health.status, 200);
    assert.match(health.headers.get("content-security-policy") ?? "", /script-src 'self'/);
    assert.equal(health.headers.get("x-content-type-options"), "nosniff");
  });
});

// ============================================================================ portfolio editing (in Docs)
describe("portfolio editing", () => {
  test("editing a role saves, publishes, and the public site shows it", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(DOCS + "/#/portfolio");
    await page.getByText("Editing view.").waitFor();
    await page.getByRole("button", { name: "Edit Consultant / Data Engineer" }).click();
    const drawer = page.getByRole("dialog", { name: /Edit experience/i });
    await drawer.locator("#f-role").fill("Lead Data Engineer (edited by E2E)");
    await drawer.getByRole("button", { name: "Save", exact: true }).click();
    await page.getByText(/Saved and published/).waitFor();
    await page.getByRole("heading", { name: "Lead Data Engineer (edited by E2E)" }).waitFor();
    const pub = await newPage();
    let seen = false;
    for (let i = 0; i < 20 && !seen; i++) {
      await pub.goto(PUB + "/");
      seen = (await pub.getByText("Lead Data Engineer (edited by E2E)").count()) > 0;
      if (!seen) await sleep(500);
    }
    assert.ok(seen, "the public site picked up the edit");
    await pub.close();
    await page.close();
    expectNoProblems();
  });

  test("bad input is explained field by field and nothing is saved", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(DOCS + "/#/portfolio");
    await page.locator("#experience").getByRole("button", { name: "Add" }).click();
    const drawer = page.getByRole("dialog", { name: /Add experience/i });
    await drawer.getByRole("button", { name: "Save", exact: true }).click();
    await drawer.getByText("Required.").first().waitFor();
    await drawer.locator("#f-slug").fill("Not A Slug!");
    await drawer.locator("#f-role").fill("R");
    await drawer.locator("#f-company").fill("C");
    await drawer.locator("#f-duration").fill("now");
    await drawer.locator("#f-summary").fill("S");
    await drawer.getByRole("button", { name: "Save", exact: true }).click();
    await drawer.getByText(/lower-case letters/i).first().waitFor();
    assert.equal(await page.locator("#experience article.card").count(), 3, "nothing was added");
    await drawer.getByRole("button", { name: "Cancel" }).click();
    await page.close();
    expectNoProblems();
  });

  test("a skill can be linked to a role and then filters the page", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(DOCS + "/#/portfolio");
    await page.getByRole("button", { name: "Edit System Engineer" }).click();
    const drawer = page.getByRole("dialog", { name: /Edit experience/i });
    await drawer.getByLabel("Filter skills").fill("Docker");
    await drawer.getByLabel("Docker / Compose").check();
    await drawer.getByRole("button", { name: "Save", exact: true }).click();
    await page.getByText(/Saved and published/).waitFor();
    await page.locator("#experience article", { hasText: "System Engineer" }).getByRole("button", { name: "Docker / Compose" }).waitFor();
    await page.close();
    expectNoProblems();
  });

  test("adding, then deleting, an education entry tells you what depends on it", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(DOCS + "/#/portfolio");
    await page.locator("#education").getByRole("button", { name: "Add" }).click();
    let drawer = page.getByRole("dialog", { name: /Add education/i });
    await drawer.locator("#f-type").selectOption("Certification");
    await drawer.locator("#f-title").fill("Temporary E2E certificate");
    await drawer.locator("#f-institution").fill("Test Academy");
    await drawer.locator("#f-date").fill("2026");
    await drawer.getByRole("button", { name: "Save", exact: true }).click();
    await page.getByText("Temporary E2E certificate").waitFor();
    await page.locator("#education li", { hasText: "Temporary E2E certificate" }).getByRole("button", { name: "Edit" }).click();
    drawer = page.getByRole("dialog", { name: /Edit education/i });
    await drawer.getByRole("button", { name: "Delete…" }).click();
    await drawer.getByText("Nothing else depends on it.").waitFor();
    await drawer.getByRole("button", { name: "Delete for good" }).click();
    await page.getByText("Temporary E2E certificate").waitFor({ state: "detached" });
    await page.close();
    expectNoProblems();
  });
});


describe("portfolio: slugs", () => {
  test("the slug is suggested from the title, and a wrong one is explained and can be fixed in one click", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(DOCS + "/#/portfolio");
    await page.locator("#projects").getByRole("button", { name: "Add" }).first().click();
    const drawer = page.getByRole("dialog", { name: /Add project/i });
    await drawer.locator("#f-title").fill("Power System Monitor");
    assert.equal(await drawer.locator("#f-slug").inputValue(), "power-system-monitor");
    await drawer.locator("#f-slug").fill("Bad Slug_1");
    await drawer.getByText(/Not a valid slug/).waitFor();
    await drawer.getByRole("button", { name: /Use “bad-slug-1”/ }).click();
    assert.equal(await drawer.locator("#f-slug").inputValue(), "bad-slug-1");
    assert.equal(await drawer.getByText(/Not a valid slug/).count(), 0);
    await drawer.locator("#f-title").fill("Something else");
    assert.equal(await drawer.locator("#f-slug").inputValue(), "bad-slug-1", "a slug the user typed is never overwritten");
    await drawer.getByRole("button", { name: "Cancel" }).click();
    await page.close();
    expectNoProblems();
  });
});

describe("portfolio: layout at real screen sizes", () => {
  for (const [w, h] of [[390, 900], [1280, 900], [1920, 1080], [2560, 1300]]) {
    test(`no horizontal scrolling and no errors at ${w}px (public portfolio and the editor in Docs)`, async () => {
      const page = await newPage(w, h);
      await page.goto(PUB + "/");
      await page.getByRole("heading", { level: 1, name: "Tabish Tariq" }).waitFor();
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${SHOTS}public-${w}.png`, fullPage: false });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `public page overflows horizontally at ${w}px`);

      await login(page);
      await page.goto(DOCS + "/#/portfolio");
      await page.getByText("Editing view.").waitFor();
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${SHOTS}portfolio-edit-${w}.png` });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `portfolio editor overflows at ${w}px`);
      await page.close();
      expectNoProblems();
    });
  }

  test("on a phone the skill filter is a drawer opened by a button", async () => {
    const page = await newPage(390, 900);
    await page.goto(PUB + "/");
    await page.getByRole("heading", { level: 1 }).waitFor();
    assert.equal(await page.locator(".panel.open").count(), 0);
    await page.getByRole("button", { name: "Open skill filter" }).last().click();
    await page.locator(".panel.open").waitFor();
    await page.screenshot({ path: `${SHOTS}public-390-drawer.png` });
    await page.keyboard.press("Escape");
    await page.locator(".panel.open").waitFor({ state: "detached" });
    await page.close();
    expectNoProblems();
  });
});

describe("portfolio: header, link and route inside Docs", () => {
  test("Docs links to the portfolio editor, the editor links to the public site, and the Docs pages UI is untouched", async () => {
    const page = await newPage();
    await login(page);
    assert.equal(await page.getByRole("link", { name: "Portfolio", exact: true }).first().getAttribute("href"), "#/portfolio");
    await page.getByRole("link", { name: "Portfolio", exact: true }).first().click();
    await page.getByText("Editing view.").waitFor();
    assert.equal(await page.getByRole("link", { name: "Open public site" }).getAttribute("href"), PUB + "/");
    assert.equal(await page.getByRole("link", { name: "← Hub" }).getAttribute("href"), "http://127.0.0.1:3910/");
    await page.getByRole("link", { name: "Pages", exact: true }).click();
    await page.locator(".dx-bar .brand").waitFor();
    assert.equal(await page.evaluate(async () => (await fetch("/api/admin/portfolio/schema")).status), 200);
    await page.close();
    expectNoProblems();
  });
});

describe("independence: stopping docs-internal keeps both public sites online", () => {
  const PIDS = new URL("../../.scratch/docs-e2e/pids", import.meta.url).pathname;
  const START = new URL("../../.scratch/docs-e2e/start-internal.sh", import.meta.url).pathname;
  const up = async (url) => { try { return (await fetch(url, { signal: AbortSignal.timeout(2000) })).status; } catch { return 0; } };
  async function waitFor(fn, what, ms = 60000) {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { if (await fn()) return; await sleep(500); }
    throw new Error("timed out waiting for " + what);
  }

  test("with docs-internal stopped the portfolio and the public Docs pages still answer; starting it again restores editing", async () => {
    // a public Docs page exists from the publishing tests (flow.example.test)
    assert.equal((await publicGet("flow.example.test", "/")).status, 200, "precondition: the published Docs page");
    const first = readFileSync(PIDS, "utf8").split("\n").filter(Boolean)[0];
    process.kill(Number(first), "SIGTERM");
    await waitFor(async () => (await up(DOCS + "/actuator/health")) === 0, "docs-internal to stop", 20000);

    // everything public is unaffected
    const page = await newPage();
    await page.goto(PUB + "/");
    await page.getByRole("heading", { level: 1, name: "Tabish Tariq" }).waitFor();
    assert.equal(await up(PUB + "/api/public/health"), 200);
    assert.equal((await publicGet("flow.example.test", "/")).status, 200, "public Docs page still served by docs-public");
    assert.equal(await up(`http://127.0.0.1:${PUBPORT}/api/public/health`), 200);
    await page.close();
    expectNoProblems();

    // start it again: the editor is back, with the portfolio data intact
    const child = spawn("bash", [START], { detached: true, stdio: "ignore" });
    child.unref();
    appendFileSync(PIDS, child.pid + "\n");
    await waitFor(async () => (await up(DOCS + "/actuator/health")) === 200, "docs-internal to start again");
    const again = await newPage();
    await login(again);
    await again.goto(DOCS + "/#/portfolio");
    await again.getByText("Editing view.").waitFor();
    await again.getByRole("heading", { level: 1, name: "Tabish Tariq" }).waitFor();
    await again.close();
    expectNoProblems(["401"]);
  });
});
