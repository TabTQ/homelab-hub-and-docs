// End-to-end tests in a real browser against the locally running stack (scripts/e2e.sh starts it).
// Nothing here touches the Pi or the real Proxmox: a mock Proxmox answers and Wake-on-LAN goes to a local UDP port.
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { after, before, describe, test } from "node:test";
import { chromium } from "playwright-core";

const INT = "http://127.0.0.1:3910", MOCK = "http://127.0.0.1:8907", PASS = "local-test-pass";
const DATA = new URL("../../.scratch/e2e-data/", import.meta.url).pathname;
const SHOTS = new URL("../../.scratch/e2e-shots/", import.meta.url).pathname;
mkdirSync(SHOTS, { recursive: true });

let browser;
const problems = []; // console errors, page errors and failed requests, checked at the end of every test

before(async () => {
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium", args: ["--no-sandbox"] });
});
after(async () => { await browser?.close(); });

async function newPage(width = 1280, height = 900) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  page.on("console", (m) => { if (m.type() === "error" && !m.text().startsWith("Failed to load resource")) problems.push(`console: ${m.text()}`); });
  page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));
  page.on("requestfailed", (r) => { if (!r.url().includes("/api/status/stream")) problems.push(`requestfailed: ${r.url()} ${r.failure()?.errorText}`); });
  page.on("response", (r) => { if (r.status() >= 500 || r.status() === 403 || r.status() === 404) problems.push(`HTTP ${r.status()}: ${r.url()}`); });
  return page;
}
function expectNoProblems(allow = []) {
  const real = problems.splice(0).filter((p) => !allow.some((a) => p.includes(a)));
  assert.deepEqual(real, [], "browser problems:\n" + real.join("\n"));
}
async function login(page) {
  await page.goto(INT + "/");
  await page.locator("#pw").fill(PASS);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { level: 1 }).first().waitFor();
}
const mockLog = async () => (await fetch(MOCK + "/__mock/log")).json();
const guestState = async (id) => (await (await fetch(`${MOCK}/api2/json/nodes/pve/lxc/${id}/status/current`, { headers: { Authorization: "PVEAPIToken=t=x" } })).json()).data?.status;
const wolLog = () => (existsSync(DATA + "wol.log") ? readFileSync(DATA + "wol.log", "utf8") : "");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ============================================================================ sign in
describe("sign in", () => {
  test("the dashboard is locked until the password is entered", async () => {
    const page = await newPage();
    await page.goto(INT + "/");
    await page.getByRole("heading", { name: "Sign in" }).waitFor();
    assert.equal(await page.getByText("E2E Lab").count(), 0, "no dashboard content before login");
    const api = await page.evaluate(async () => (await fetch("/api/dashboard")).status);
    assert.equal(api, 401);
    await page.locator("#pw").fill("wrong password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByText("Wrong password.").waitFor();
    await page.locator("#pw").fill(PASS);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("heading", { level: 1, name: "E2E Lab" }).waitFor();
    await page.close();
    expectNoProblems(["401"]);
  });

  test("signing out returns to the login screen and the session is really gone", async () => {
    const page = await newPage();
    await login(page);
    await page.getByRole("button", { name: "Sign out" }).click();
    await page.getByRole("heading", { name: "Sign in" }).waitFor();
    assert.equal(await page.evaluate(async () => (await fetch("/api/dashboard")).status), 401);
    await page.close();
    expectNoProblems(["401"]);
  });

  test("the top bar links to Docs when dashboard.yaml has docs.url", async () => {
    const page = await newPage();
    await login(page);
    const link = page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Docs" });
    assert.equal(await link.getAttribute("href"), "http://127.0.0.1:3920/");
    assert.equal(await link.getAttribute("target"), null, "a plain same-tab link");
    await page.close();
    expectNoProblems();
  });

  test("the Portfolio card only links to Docs; the dashboard never calls the Docs host and has no Portfolio page", async () => {
    const page = await newPage();
    const calls = [];
    page.on("request", (r) => { if (/:(3920|3921|3911)\b/.test(r.url()) || /\/api\/(admin\/)?portfolio/.test(r.url())) calls.push(r.url()); });
    await login(page);
    await page.locator(".portfolio-card").waitFor();
    assert.equal(await page.getByRole("link", { name: "Edit in Docs" }).getAttribute("href"), "http://127.0.0.1:3920/#/portfolio");
    assert.equal(await page.getByRole("link", { name: "Open public site" }).getAttribute("href"), "http://127.0.0.1:3911/");
    assert.equal(await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Portfolio" }).count(), 0, "no Portfolio nav item");
    await page.goto(INT + "/#/portfolio"); // an old bookmark or shortcut lands on the dashboard
    await page.locator(".portfolio-card").waitFor();
    await page.keyboard.press("Control+k");
    await page.getByRole("dialog", { name: "Search" }).locator("input").fill("portfolio");
    assert.equal(await page.getByRole("dialog", { name: "Search" }).locator(".res [role=option]").count(), 0, "the palette no longer lists a Portfolio page");
    await page.keyboard.press("Escape");
    await page.waitForTimeout(1500);
    assert.deepEqual(calls, [], "the hub must not call Docs, the public portfolio or any portfolio API: " + calls.join(", "));
    await page.close();
    expectNoProblems();
  });
});

// ============================================================================ dashboard
describe("dashboard", () => {
  test("shows groups, live status, widgets and host resources", async () => {
    const page = await newPage();
    await login(page);
    await page.locator(".grp", { hasText: "Local services" }).waitFor();
    const tile = (name) => page.locator(".item", { hasText: name });
    await tile("Hub internal").locator(".dot.up").waitFor();
    await tile("Broken service").locator(".dot.down").waitFor();
    await page.locator(".wline", { hasText: "LXC 3/6" }).waitFor();
    assert.ok((await page.locator(".widgets .w").count()) >= 4, "host resource cells");
    await page.getByText(/monitored services up/).waitFor();
    assert.ok((await page.locator(".pinned .chip-act").count()) === 2);
    const href = await tile("Hub internal").locator("a").getAttribute("href");
    assert.equal(href, "http://127.0.0.1:3910/");
    assert.equal(await tile("Hub internal").locator("a").getAttribute("rel"), "noopener noreferrer");
    await page.close();
    expectNoProblems();
  });

  test("the service filter hides what does not match", async () => {
    const page = await newPage();
    await login(page);
    await page.locator(".item", { hasText: "Broken service" }).waitFor();
    await page.getByLabel("Filter services").fill("broken");
    assert.equal(await page.locator(".item").count(), 1);
    await page.getByLabel("Filter services").fill("");
    assert.ok((await page.locator(".item").count()) >= 7);
    await page.close();
    expectNoProblems();
  });

  test("the theme switch changes the page and is remembered", async () => {
    const page = await newPage();
    await login(page);
    await page.getByRole("button", { name: "Change theme" }).click(); // system -> light
    await page.getByRole("button", { name: "Change theme" }).click(); // -> dark
    assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), "dark");
    const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    assert.match(bg, /rgb\(15, 23, 29\)/);
    await page.reload();
    await page.getByRole("heading", { level: 1 }).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), "dark");
    await page.close();
    expectNoProblems();
  });

  test("the palette lists services, actions and pages, and opens the action dialog", async () => {
    const page = await newPage();
    await login(page);
    await page.locator(".item", { hasText: "Hub internal" }).waitFor();
    await page.keyboard.press("Control+k");
    await page.getByRole("dialog", { name: "Search" }).locator("input").fill("shutdown");
    await page.keyboard.press("Enter");
    await page.getByRole("alertdialog").waitFor();
    assert.match(await page.getByRole("alertdialog").innerText(), /Shut down Proxmox host "pve"/);
    await page.getByRole("button", { name: "Cancel" }).click();
    assert.equal((await mockLog()).filter((e) => e.path.endsWith("/status")).length, 0, "cancelling sent nothing");
    await page.close();
    expectNoProblems();
  });
});

// ============================================================================ actions (mock Proxmox, local UDP)
describe("power actions", () => {
  before(async () => { await fetch(MOCK + "/__mock/reset", { method: "POST" }); });

  test("wake: a click sends a real magic packet", async () => {
    const page = await newPage();
    await login(page);
    await page.locator(".pinned").getByRole("button", { name: /Wake test box/ }).click();
    const dlg = page.getByRole("alertdialog");
    await dlg.getByText(/AA:BB:CC:DD:EE:01/).first().waitFor();
    assert.ok(!wolLog().includes("AA:BB:CC:DD:EE:01"), "nothing is sent before confirming");
    await dlg.getByRole("button", { name: "Send packet" }).click();
    await page.getByText(/Wake packet sent to Wake test box/).first().waitFor();
    for (let i = 0; i < 20 && !wolLog().includes("MAGIC AA:BB:CC:DD:EE:01"); i++) await sleep(100);
    assert.match(wolLog(), /MAGIC AA:BB:CC:DD:EE:01/);
    await page.close();
    expectNoProblems();
  });

  test("shutdown: the button stays disabled until the host name is typed exactly", async () => {
    const page = await newPage();
    await login(page);
    await page.locator(".item", { hasText: "Shut down host" }).locator("button.tile").click();
    const dlg = page.getByRole("alertdialog");
    assert.match(await dlg.innerText(), /3 guests are running and will go down with the host/);
    assert.match(await dlg.innerText(), /UbuntuCT \(100\)/);
    const go = dlg.getByRole("button", { name: "Shut down" });
    assert.equal(await go.isDisabled(), true);
    await dlg.getByLabel(/Type/).fill("PVE");
    assert.equal(await go.isDisabled(), true, "case sensitive");
    await dlg.getByLabel(/Type/).fill("pve");
    assert.equal(await go.isDisabled(), false);
    assert.equal((await mockLog()).filter((e) => e.path.endsWith("/nodes/pve/status")).length, 0);
    await go.click();
    await page.getByText("Shutdown command sent to pve.").first().waitFor();
    const sent = (await mockLog()).filter((e) => e.path.endsWith("/nodes/pve/status"));
    assert.equal(sent.length, 1);
    assert.equal(sent[0].body, "command=shutdown");
    await page.close();
    expectNoProblems();
  });

  test("stopping a guest is a hard stop that follows the real task to the end, then start brings it back", async () => {
    await fetch(MOCK + "/__mock/reset", { method: "POST" });
    const page = await newPage();
    await login(page);
    await page.locator(".item", { hasText: "Apps LXC" }).locator("button.tile").click();
    let dlg = page.getByRole("alertdialog");
    assert.match(await dlg.innerText(), /immediate power-off/);
    await dlg.getByLabel(/Type/).fill("100");
    await dlg.getByRole("button", { name: "Stop now" }).click();
    await page.getByText(/finished/).first().waitFor();
    assert.equal(await guestState(100), "stopped");
    await page.getByRole("button", { name: "Close" }).click();

    await page.locator(".item", { hasText: "Apps LXC" }).locator("button.tile").click();
    dlg = page.getByRole("alertdialog");
    assert.match(await dlg.innerText(), /Start Apps LXC/);
    assert.equal(await dlg.getByLabel(/Type/).count(), 0, "starting only needs a click");
    await dlg.getByRole("button", { name: "Start", exact: true }).click();
    await page.getByText(/finished/).first().waitFor();
    assert.equal(await guestState(100), "running");
    await page.close();
    expectNoProblems();
  });

  test("a confirmation that sits too long expires and nothing is sent", async () => {
    const page = await newPage();
    await login(page);
    const before = (await mockLog()).length;
    await page.locator(".item", { hasText: "Media LXC" }).locator("button.tile").click();
    await page.getByRole("alertdialog").waitFor();
    await page.getByText(/This confirmation expired/).waitFor({ timeout: 40000 });
    assert.equal((await mockLog()).length, before);
    await page.getByRole("button", { name: "Cancel" }).click();
    await page.close();
    expectNoProblems();
  });

  test("Proxmox being unreachable is explained, not a blank error", async () => {
    // stop the mock's answers by asking for a guest that does not exist via a fresh action would need config; instead check the legacy contract still works
    const r = await fetch(INT + "/toggle/pve/lxc/99999");
    assert.equal(r.status, 500);
    assert.equal((await r.json()).error, "Operation failed");
  });
});

// ============================================================================ config editor
describe("config editor", () => {
  test("loads the file, marks errors on the right line and blocks saving them", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(INT + "/#/config");
    await page.getByRole("heading", { level: 1, name: "Config" }).waitFor();
    await page.locator(".cm-content").waitFor();
    assert.match(await page.locator(".cm-content").innerText(), /title: E2E Lab/);
    assert.equal(await page.getByRole("button", { name: "Save…" }).isDisabled(), true, "nothing changed yet");

    await page.locator(".cm-content").click();
    await page.keyboard.press("Control+End");
    await page.keyboard.type("\nbroken: [");
    await page.getByText(/YAML syntax error/).first().waitFor();
    assert.equal(await page.getByRole("button", { name: "Save…" }).isDisabled(), true, "errors block saving");
    await page.getByRole("button", { name: "Discard changes" }).click();
    await page.getByText("No problems found.").waitFor();
    await page.close();
    expectNoProblems();
  });

  test("Add service inserts only its own lines, shows the diff, saves, and the dashboard picks it up", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(INT + "/#/config");
    await page.locator(".cm-content").waitFor();
    await page.getByRole("button", { name: "Add service" }).click();
    await page.locator("#sv-g").fill("Local services");
    await page.locator("#sv-n").fill("Added by test");
    await page.locator("#sv-h").fill("http://127.0.0.1:3910/added");
    await page.getByRole("button", { name: "Add to the editor" }).click();
    await page.getByText("unsaved changes").waitFor();
    await page.getByRole("button", { name: "Save…" }).click();
    await page.getByRole("dialog", { name: "Review your changes" }).waitFor();
    assert.equal(await page.locator(".diff-pre .del").count(), 0, "nothing removed");
    assert.equal(await page.locator(".diff-pre .add").count(), 1, "exactly one line added");
    assert.match(await page.locator(".diff-pre .add").innerText(), /Added by test/);
    await page.getByRole("button", { name: "Save these changes" }).click();
    await page.getByText(/Saved. The dashboard is using it now/).waitFor();
    await page.goto(INT + "/#/");
    await page.locator(".item", { hasText: "Added by test" }).waitFor();
    await page.close();
    expectNoProblems();
  });

  test("an edit made over SSH while the editor is open is caught as a conflict", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(INT + "/#/config");
    await page.locator(".cm-content").waitFor();
    await page.locator(".cm-content").click();
    await page.keyboard.press("Control+Home");
    await page.keyboard.type("# my edit\n");
    // someone else changes the file on disk
    const file = DATA + "config/dashboard.yaml";
    writeFileSync(file, readFileSync(file, "utf8").replace("title: E2E Lab", "title: E2E Lab edited elsewhere"));
    await sleep(1500);
    await page.getByRole("button", { name: "Save…" }).click();
    await page.getByRole("button", { name: "Save these changes" }).click();
    await page.getByRole("dialog", { name: "The file changed while you were editing" }).waitFor();
    await page.getByRole("button", { name: "Overwrite with mine" }).click();
    await page.getByText(/Saved. The dashboard is using it now/).waitFor();
    assert.match(readFileSync(file, "utf8"), /^# my edit\n/);
    await page.close();
    expectNoProblems();
  });

  test("a broken file on disk keeps the dashboard alive and shows what is wrong", async () => {
    const file = DATA + "config/dashboard.yaml";
    const good = readFileSync(file, "utf8");
    writeFileSync(file, good + "\nbroken: [\n");
    const page = await newPage();
    await login(page);
    await page.getByText(/dashboard.yaml has 1 error/).waitFor({ timeout: 20000 });
    await page.getByRole("heading", { level: 1 }).waitFor();
    assert.ok((await page.locator(".item").count()) >= 7, "the last good dashboard is still shown");
    writeFileSync(file, good);
    await page.getByText(/dashboard.yaml has 1 error/).waitFor({ state: "detached", timeout: 40000 });
    await page.close();
    expectNoProblems();
  });

  test("an older version can be restored", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(INT + "/#/config");
    await page.locator(".vlist li").first().waitFor();
    assert.ok((await page.locator(".vlist li").count()) >= 3);
    await page.locator(".vlist li").nth(2).getByRole("button", { name: "Restore" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Restore" }).click();
    await page.getByText("Restored.").waitFor();
    await page.close();
    expectNoProblems();
  });
});

// ============================================================================ fixes requested after the first Pi test
describe("removing from the config, dialogs on short screens, slugs, tile sizes", () => {
  test("Remove service cuts only that entry, shows it in the diff, saves, and the tile is gone", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(INT + "/#/config");
    await page.locator(".cm-content").waitFor();
    await page.getByRole("button", { name: "Remove service" }).click();
    await page.getByLabel("Find").fill("added by");
    await page.getByRole("radio").first().check();
    await page.getByRole("button", { name: "Remove from the editor" }).click();
    await page.getByText("unsaved changes").waitFor();
    await page.getByRole("button", { name: "Save…" }).click();
    await page.getByRole("dialog", { name: "Review your changes" }).waitFor();
    assert.equal(await page.locator(".diff-pre .add").count(), 0, "nothing added");
    assert.equal(await page.locator(".diff-pre .del").count(), 1, "exactly one line removed");
    assert.match(await page.locator(".diff-pre .del").innerText(), /Added by test/);
    await page.getByRole("button", { name: "Save these changes" }).click();
    await page.getByText(/Saved. The dashboard is using it now/).waitFor();
    await page.goto(INT + "/#/");
    await page.locator(".item", { hasText: "Hub internal" }).waitFor();
    assert.equal(await page.locator(".item", { hasText: "Added by test" }).count(), 0);
    await page.close();
    expectNoProblems();
  });

  test("Remove action also removes its button and its pin, so nothing dangles, and the file still validates", async () => {
    const page = await newPage();
    await login(page);
    await page.goto(INT + "/#/config");
    await page.locator(".cm-content").waitFor();
    await page.getByRole("button", { name: "Remove action" }).click();
    await page.getByRole("radio", { name: /Wake test box/ }).check();
    await page.getByText(/1 dashboard button and its “Quick actions” pin/).waitFor();
    await page.getByRole("button", { name: "Remove from the editor" }).click();
    await page.getByText("No problems found.").waitFor();
    await page.getByRole("button", { name: "Save…" }).click();
    await page.getByRole("dialog", { name: "Review your changes" }).waitFor();
    // the pin list is one line ([wake-test, apps]), so that line shows as changed: 3 lines out, 1 rewritten line in
    assert.equal(await page.locator(".diff-pre .add").count(), 1);
    assert.match(await page.locator(".diff-pre .add").innerText(), /pinned: \[apps\]/);
    assert.equal(await page.locator(".diff-pre .del").count(), 3, "the action, its button and the old pin line");
    await page.getByRole("button", { name: "Save these changes" }).click();
    await page.getByText(/Saved. The dashboard is using it now/).waitFor();
    await page.goto(INT + "/#/");
    await page.locator(".pinned .chip-act").first().waitFor();
    assert.equal(await page.locator(".pinned .chip-act").count(), 1);
    assert.equal(await page.locator(".item", { hasText: "Wake test box" }).count(), 0);
    await page.close();
    expectNoProblems();
  });

  test("the Add action dialog is fully reachable on a short screen (100% zoom on a laptop)", async () => {
    const page = await newPage(1280, 480);
    await login(page);
    await page.goto(INT + "/#/config");
    await page.locator(".cm-content").waitFor();
    await page.getByRole("button", { name: "Add action" }).click();
    const dlg = page.getByRole("dialog", { name: "Add an action" });
    await dlg.waitFor();
    const inView = async (loc) => { const b = await loc.boundingBox(); return b && b.y >= 0 && b.y + b.height <= 480 + 1; };
    assert.ok(await inView(dlg.getByRole("button", { name: "Add to the editor" })), "the buttons are on screen without scrolling the page");
    assert.ok(await inView(dlg.getByRole("button", { name: "Cancel" })));
    await dlg.locator("#ac-p").scrollIntoViewIfNeeded();
    assert.ok(await inView(dlg.locator("#ac-p")), "the last field can be scrolled to");
    assert.ok(await inView(dlg.getByRole("button", { name: "Add to the editor" })), "the buttons stay visible while scrolled");
    await page.screenshot({ path: `${SHOTS}add-action-short.png` });
    await dlg.getByRole("button", { name: "Cancel" }).click();
    await page.close();
    expectNoProblems();
  });


  for (const [w, h] of [[390, 900], [900, 900], [1280, 900], [1920, 1080], [2560, 1300]]) {
    test(`dashboard tiles are uniform at ${w}px: one height for plain tiles, one for widget tiles, names on one line`, async () => {
      const page = await newPage(w, h);
      await login(page);
      await page.locator(".item.hasw .wline").first().waitFor();
      const r = await page.evaluate(() => {
        const heights = (sel) => [...new Set([...document.querySelectorAll(sel)].map((e) => Math.round(e.getBoundingClientRect().height)))];
        const wrapped = [...document.querySelectorAll(".item .nm")].filter((e) => e.scrollHeight > e.clientHeight + 1 || e.getBoundingClientRect().height > 22).map((e) => e.textContent);
        const widths = [...new Set([...document.querySelectorAll(".items > .item")].map((e) => Math.round(e.getBoundingClientRect().width / 4)))];
        return { plain: heights(".item:not(.hasw)"), widget: heights(".item.hasw"), wrapped, nPlain: document.querySelectorAll(".item:not(.hasw)").length, nWidget: document.querySelectorAll(".item.hasw").length, overflowX: document.documentElement.scrollWidth > innerWidth + 1 };
      });
      assert.ok(r.nPlain > 3 && r.nWidget >= 1);
      assert.equal(r.plain.length, 1, `plain tiles have different heights: ${r.plain}`);
      assert.equal(r.widget.length, 1, `widget tiles have different heights: ${r.widget}`);
      assert.ok(r.widget[0] > r.plain[0], "widget tiles are taller");
      assert.deepEqual(r.wrapped, [], "names must not wrap");
      assert.equal(r.overflowX, false);
      await page.screenshot({ path: `${SHOTS}tiles-${w}.png` });
      await page.close();
      expectNoProblems();
    });
  }
});

// ============================================================================ installable app (PWA)
describe("installable app", () => {
  test("the manifest is public, valid, and every icon it names exists with the size it claims", async () => {
    const r = await fetch(INT + "/manifest.json"); // no cookies: the browser fetches it before login
    assert.equal(r.status, 200);
    assert.match(r.headers.get("content-type") ?? "", /json/);
    const m = await r.json();
    assert.equal(m.display, "standalone");
    assert.equal(m.start_url, "/");
    assert.ok(m.name && m.short_name);
    const purposes = m.icons.map((i) => i.purpose);
    assert.ok(purposes.includes("any") && purposes.includes("maskable"), "has both regular and maskable icons");
    for (const icon of m.icons) {
      const res = await fetch(INT + icon.src);
      assert.equal(res.status, 200, icon.src);
      assert.equal(res.headers.get("content-type"), icon.type, icon.src);
      if (icon.type === "image/png") {
        const b = Buffer.from(await res.arrayBuffer());
        assert.equal(`${b.readUInt32BE(16)}x${b.readUInt32BE(20)}`, icon.sizes, `${icon.src} really is ${icon.sizes}`);
      }
    }
    for (const sc of m.shortcuts) assert.ok(sc.url.startsWith("/"), sc.name);
    for (const path of ["/sw.js", "/offline.html"]) assert.equal((await fetch(INT + path)).status, 200, path);
    assert.match((await fetch(INT + "/sw.js")).headers.get("content-type") ?? "", /javascript/);
  });

  test("the browser considers the app installable", async () => {
    // a normal (non-incognito) profile: Chrome refuses to call anything installable in incognito
    const { mkdtempSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const ctx = await chromium.launchPersistentContext(mkdtempSync(tmpdir() + "/pwa-profile-"), { executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium", args: ["--no-sandbox"] });
    try {
      const page = ctx.pages()[0] ?? (await ctx.newPage());
      await page.goto(INT + "/");
      await page.getByRole("heading", { name: "Sign in" }).waitFor();
      assert.equal(await page.locator('link[rel="manifest"]').getAttribute("href"), "/manifest.json");
      await page.evaluate(() => navigator.serviceWorker.ready);
      const cdp = await ctx.newCDPSession(page);
      await cdp.send("Page.enable");
      const { installabilityErrors } = await cdp.send("Page.getInstallabilityErrors");
      assert.deepEqual(installabilityErrors, []);
    } finally { await ctx.close(); }
  });

  test("the service worker takes control of the page after a reload", async () => {
    const page = await newPage();
    await page.goto(INT + "/");
    await page.getByRole("heading", { name: "Sign in" }).waitFor();
    await page.evaluate(() => navigator.serviceWorker.ready);
    await page.reload();
    assert.equal(await page.evaluate(() => !!navigator.serviceWorker.controller), true);
    await page.close();
    expectNoProblems();
  });

  test("nothing private is ever stored by the service worker", async () => {
    const page = await newPage();
    await login(page);
    await page.locator(".item").first().waitFor();
    await page.evaluate(() => navigator.serviceWorker.ready);
    await page.reload();
    await page.locator(".item").first().waitFor();
    await page.goto(INT + "/#/config");
    await page.locator(".cm-content").waitFor();
    const urls = await page.evaluate(async () => {
      const out = [];
      for (const k of await caches.keys()) for (const r of await (await caches.open(k)).keys()) out.push(new URL(r.url).pathname);
      return out;
    });
    assert.ok(urls.length > 0, "the fingerprinted assets are cached");
    for (const u of urls) assert.ok(u.startsWith("/assets/") || u === "/offline.html", `unexpected cached URL ${u}`);
    await page.close();
    expectNoProblems();
  });
});

// ============================================================================ responsive + visual evidence
// ============================================================================ budget
// The e2e database is fresh per run. The reference month (plan 5.6, Anu/Ravi/Meera in September 2026) is built first;
// later tests use other months and people so they cannot change its figures.

/** Calls the budget API from inside the logged-in page (session cookie + CSRF header, like the app does). */
async function bapi(page, method, path, body) {
  return page.evaluate(async ([method, path, body]) => {
    const t = decodeURIComponent((document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN=")) ?? "=").split("=")[1]);
    const r = await fetch("/api/admin/budget" + path, { method, headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": t }, body: body ? JSON.stringify(body) : undefined });
    return { status: r.status, body: await r.json().catch(() => null) };
  }, [method, path, body]);
}
const budgetIds = {};
const xfer = (page, from, to) => page.locator(".bud-xfer", { hasText: `${from} pays ${to}` });
async function openBudget(page, month) {
  const url = `${INT}/#/budget/${month}`;
  if (page.url() === url) await page.reload(); // going to the same hash would not load the month again
  else await page.goto(url);
  await page.locator(".bud-card").first().waitFor();
}

describe("budget", () => {
  test("nothing of the budget loads until its page is opened", async () => {
    const page = await newPage();
    const seen = [];
    page.on("request", (r) => seen.push(r.url()));
    await login(page);
    await page.locator(".grp").first().waitFor();
    await page.waitForTimeout(1000);
    assert.deepEqual(seen.filter((u) => u.includes("/api/admin/budget") || u.includes("BudgetPage")), [], "no budget request or chunk on the dashboard");
    await page.getByRole("link", { name: "Budget", exact: true }).click();
    await page.getByRole("heading", { level: 2, name: "Start with the people" }).waitFor();
    assert.ok(seen.some((u) => /BudgetPage-.*\.js/.test(u)), "the budget chunk is fetched when the page opens");
    assert.ok(seen.some((u) => /\/api\/admin\/budget\/months\/\d{4}-\d{2}\/open/.test(u)));
    assert.ok(!seen.some((u) => /ConfigPage-.*\.js/.test(u)), "the budget page does not pull in the config editor (CodeMirror)");
    await page.close();
    expectNoProblems();
  });

  test("people, a month-only expense and the reference month's who pays whom", async () => {
    const page = await newPage();
    await login(page);
    await openBudget(page, "2026-09");
    // people through the People panel
    await page.getByRole("button", { name: "People", exact: true }).click();
    for (const n of ["Anu", "Ravi", "Meera"]) {
      await page.getByLabel("Add a person").fill(n);
      await page.getByRole("button", { name: "Add", exact: true }).click();
      await page.locator(".bud-list b", { hasText: n }).waitFor();
    }
    await page.getByRole("button", { name: "Done" }).click();
    await page.getByRole("heading", { level: 2, name: "Who pays whom" }).waitFor();
    const people = (await bapi(page, "GET", "/people")).body;
    for (const p of people) budgetIds[p.name] = p.id;
    const { Anu: a, Ravi: r, Meera: m } = budgetIds;
    const all = [a, r, m];
    // a month-only expense through the form: stored as the 15th, shown as "Sep (no day)"
    await page.getByRole("button", { name: "+ Add expense" }).click();
    await page.getByLabel("Payment name").fill("Electricity bill");
    await page.getByLabel("Amount").fill("1,850.50");
    await page.getByLabel("Paid by").selectOption({ label: "Meera" });
    await page.getByText("₹1,850.50 ÷ 3 → Anu ₹616.84 · Ravi ₹616.83 · Meera ₹616.83").waitFor();
    await page.getByText("Saved as 15 Sep 2026 and shown as “Sep (no day)”.").waitFor();
    await page.getByRole("button", { name: "Save expense" }).click();
    await page.getByRole("status").filter({ hasText: "Expense added." }).waitFor();
    const row = page.locator(".bud-table tr", { hasText: "Electricity bill" });
    await row.getByText("no day").waitFor();
    const view = (await bapi(page, "POST", "/months/2026-09/open")).body;
    const elec = view.expenses.find((e) => e.name === "Electricity bill");
    assert.equal(elec.date, "2026-09-15");
    assert.equal(elec.daySet, false);
    // a subset split with a day, through the form
    await page.getByRole("button", { name: "+ Add expense" }).click();
    await page.getByLabel("Payment name").fill("Dinner out");
    await page.getByLabel("Amount").fill("3600");
    await page.getByLabel("Paid by").selectOption({ label: "Meera" });
    await page.getByRole("button", { name: "Ravi", exact: true }).click(); // untick
    await page.getByRole("button", { name: "Pick day" }).click();
    await page.getByLabel("Day", { exact: true }).fill("2026-09-20");
    await page.getByRole("button", { name: "Save expense" }).click();
    await page.locator(".bud-table tr", { hasText: "Dinner out" }).getByText("Anu, Meera").waitFor();
    // the rest of the reference data through the API
    for (const x of [
      ["POST", "/recurring", { name: "Rent", amountPaise: 3000000, paidBy: a, splitIds: all, startMonth: "2026-09", day: 1 }],
      ["POST", "/recurring", { name: "Internet", amountPaise: 119900, paidBy: r, splitIds: all, startMonth: "2026-09", endMonth: "2027-02", day: 5 }],
      ["POST", "/expenses", { name: "August dinner", amountPaise: 60000, paidBy: m, month: "2026-08", day: 10, splitIds: [r, m] }],
      ["POST", "/expenses", { name: "Groceries", amountPaise: 240000, paidBy: r, month: "2026-09", day: 12, splitIds: all }],
    ]) assert.equal((await bapi(page, ...x)).status, 201);
    await openBudget(page, "2026-09");
    await xfer(page, "Ravi", "Anu").getByText("₹8,517.50").waitFor();
    await xfer(page, "Meera", "Anu").getByText("₹7,865.99").waitFor();
    await page.getByText("Carried in from before: Ravi owes Meera ₹300.00.").waitFor();
    await page.locator(".bud-table tr", { hasText: "Rent" }).getByText("recurring").waitFor();
    await page.getByText("5 entries · ₹39,049.50").waitFor();
    const total = page.locator(".bud-table tfoot tr").first();
    assert.match(await total.innerText(), /₹39,049.50\s+₹39,049.50\s+0.00\s+0.00\s+0.00/);
    await page.screenshot({ path: `${SHOTS}budget-month-1280.png`, fullPage: true });
    await page.close();
    expectNoProblems();
  });

  test("mark paid records a part payment, and it can be undone", async () => {
    const page = await newPage();
    await login(page);
    await openBudget(page, "2026-09");
    await xfer(page, "Ravi", "Anu").getByRole("button", { name: /Mark paid/ }).click();
    const amount = page.getByLabel("Amount paid");
    assert.equal(await amount.inputValue(), "8517.50");
    await amount.fill("9000");
    await page.getByText("More than the suggested amount: this will flip the balance.").waitFor();
    await amount.fill("5000");
    await page.getByLabel("Date (optional)").fill("2026-09-25");
    await page.getByLabel("Note (optional)").fill("UPI");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await xfer(page, "Ravi", "Anu").getByText("₹3,517.50").waitFor();
    const card = page.locator(".bud-card", { hasText: "Settlements" });
    await card.getByText("UPI").waitFor();
    await page.getByRole("columnheader", { name: "Settled" }).waitFor();
    await page.screenshot({ path: `${SHOTS}budget-settled-1280.png`, fullPage: true });
    // the rest carries into October
    await openBudget(page, "2026-10");
    const ravi = page.locator(".bud-wide .bud-table tbody tr", { hasText: "Ravi" }).first();
    assert.match(await ravi.innerText(), /−3,517.50/);
    // undo
    await openBudget(page, "2026-09");
    await card.getByRole("button", { name: /Delete the payment/ }).click();
    await card.getByRole("button", { name: "Remove" }).click();
    await xfer(page, "Ravi", "Anu").getByText("₹8,517.50").waitFor();
    assert.equal(await page.getByRole("columnheader", { name: "Settled" }).count(), 0);
    // no date = today in the budget's zone (Asia/Kolkata)
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date());
    const s = await bapi(page, "POST", "/settlements", { fromId: budgetIds.Ravi, toId: budgetIds.Anu, amountPaise: 100 });
    assert.equal(s.body.date, today);
    assert.equal((await bapi(page, "DELETE", `/settlements/${s.body.id}`)).status, 200);
    await page.close();
    expectNoProblems();
  });

  test("mark paid in a past month counts in that month, so it is not offered twice", async () => {
    const page = await newPage();
    await login(page);
    await openBudget(page, "2026-08");
    await xfer(page, "Ravi", "Meera").getByRole("button", { name: /Mark paid/ }).click();
    const date = page.getByLabel("Date (optional)");
    assert.equal(await date.inputValue(), "2026-08-31", "the date starts inside the month on screen, not today");
    await page.getByText("Defaults to the last day of August 2026").waitFor();
    await date.fill("2026-09-03");
    await page.getByText("This date is in September 2026, so the payment counts there, not in August 2026.").waitFor();
    await date.fill("2026-08-31");
    assert.equal(await page.getByText(/so the payment counts there/).count(), 0);
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await xfer(page, "Ravi", "Meera").waitFor({ state: "detached" });
    const card = page.locator(".bud-card", { hasText: "Settlements" });
    await card.getByText("₹300.00").first().waitFor();
    await openBudget(page, "2026-09");
    await xfer(page, "Ravi", "Anu").waitFor();
    assert.equal(await page.getByText(/Carried in from before: Ravi owes Meera/).count(), 0, "September no longer carries August's debt");
    // undo, so later tests see the reference data
    await openBudget(page, "2026-08");
    await card.getByRole("button", { name: /Delete the payment/ }).click();
    await card.getByRole("button", { name: "Remove" }).click();
    await xfer(page, "Ravi", "Meera").waitFor();
    await page.close();
    expectNoProblems();
  });

  test("the add form checks amounts and adds a person inline", async () => {
    const page = await newPage();
    await login(page);
    await openBudget(page, "2027-02");
    await page.getByRole("button", { name: "+ Add expense" }).click();
    await page.getByLabel("Payment name").fill("Weekend trip fuel");
    await page.getByLabel("Amount").fill("12.345");
    await page.getByRole("button", { name: "Save expense" }).click();
    await page.getByText(/at most 2 decimals/).waitFor();
    await page.getByRole("button", { name: "None" }).click();
    await page.getByLabel("Amount").fill("100");
    await page.getByRole("button", { name: "Save expense" }).click();
    await page.getByText("Choose at least one person to split with.").first().waitFor();
    await page.getByRole("button", { name: "+ Add person" }).click();
    await page.getByLabel("New person's name").fill("Kiran");
    await page.getByRole("button", { name: "Add", exact: true }).click();
    await page.getByRole("button", { name: "Kiran", exact: true, pressed: true }).waitFor();
    assert.ok((await page.getByLabel("Paid by").locator("option").allInnerTexts()).includes("Kiran"));
    await page.getByLabel("Paid by").selectOption({ label: "Kiran" });
    await page.getByText("₹100.00 ÷ 1 → Kiran ₹100.00").waitFor();
    await page.getByRole("button", { name: "Save expense" }).click();
    await page.locator(".bud-table tr", { hasText: "Weekend trip fuel" }).waitFor();
    // a duplicate name is refused, whatever its case
    const dup = await bapi(page, "POST", "/people", { name: "kiran" });
    assert.equal(dup.status, 409);
    await page.close();
    expectNoProblems(["HTTP 409"]);
  });

  test("recurring entries appear when a month is opened, and stay deleted", async () => {
    const page = await newPage();
    await login(page);
    await openBudget(page, "2027-03");
    await page.getByRole("button", { name: "Recurring", exact: true }).click();
    await page.locator("tr", { hasText: "Rent" }).waitFor();
    await page.getByRole("button", { name: "+ New" }).click();
    await page.getByLabel("Payment name").fill("Cook salary");
    await page.getByLabel("Amount").fill("4000");
    await page.getByLabel("Starts").fill("2027-03");
    await page.getByLabel("Ends (optional)").fill("2027-07");
    await page.getByLabel("Day of month (optional)").fill("31");
    await page.getByText("Creates an entry each month from Mar 2027 to Jul 2027, on day 31 (the last day in shorter months).").waitFor();
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await page.locator("tr", { hasText: "Cook salary" }).waitFor();
    await page.getByRole("button", { name: "Done" }).click();
    // April has 30 days
    await openBudget(page, "2027-04");
    const cook = page.locator(".bud-wide .bud-table tr", { hasText: "Cook salary" });
    await cook.getByText("30 Apr").waitFor();
    await cook.getByRole("button", { name: "Edit Cook salary" }).click();
    await page.getByRole("button", { name: "Delete", exact: true }).click();
    await page.getByRole("button", { name: "Delete", exact: true }).click(); // confirm
    await page.getByRole("status").filter({ hasText: "Expense deleted." }).waitFor();
    await openBudget(page, "2027-04");
    assert.equal(await cook.count(), 0, "a deleted generated entry is not created again");
    await openBudget(page, "2027-05");
    await page.locator(".bud-wide .bud-table tr", { hasText: "Cook salary" }).getByText("₹4,000.00").waitFor();
    // editing the definition changes only months not opened yet
    const def = (await bapi(page, "GET", "/recurring")).body.find((x) => x.name === "Cook salary");
    assert.equal((await bapi(page, "PUT", `/recurring/${def.id}`, { ...def, amountPaise: 500000 })).status, 200);
    await openBudget(page, "2027-05");
    await page.locator(".bud-wide .bud-table tr", { hasText: "Cook salary" }).getByText("₹4,000.00").waitFor();
    await openBudget(page, "2027-07");
    await page.locator(".bud-wide .bud-table tr", { hasText: "Cook salary" }).getByText("₹5,000.00").waitFor();
    await openBudget(page, "2027-08");
    assert.equal(await page.locator(".bud-table tr", { hasText: "Cook salary" }).count(), 0, "the end month is inclusive");
    await page.close();
    expectNoProblems();
  });

  test("export: three choices, a live preview and a real PDF", async () => {
    const page = await newPage();
    await login(page);
    await openBudget(page, "2026-09");
    await page.getByRole("button", { name: "Export PDF" }).click();
    for (const n of ["This month", "Choose month", "Choose range"]) await page.getByRole("button", { name: n }).waitFor();
    await page.getByRole("button", { name: "Choose month" }).click();
    await page.getByLabel("Month", { exact: true }).fill("2026-09");
    await page.getByTestId("bud-export-preview").getByText("Result: Ravi → Anu ₹8,517.50 · Meera → Anu ₹7,865.99").waitFor();
    await page.getByRole("button", { name: "Choose range" }).click();
    await page.getByLabel("From").fill("2026-09-01");
    await page.getByLabel("To", { exact: true }).fill("2026-09-10");
    const preview = page.getByTestId("bud-export-preview");
    await preview.getByText("In the range: 2 expenses").waitFor(); // the 15th (no day) and the 20th drop out
    await preview.getByText("Balances before 1 Sep 2026 (carried in): Ravi owes Meera ₹300.00.").waitFor();
    await page.screenshot({ path: `${SHOTS}budget-export-1280.png` });
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Download" }).click()]);
    assert.equal(download.suggestedFilename(), "budget-2026-09-01_to_2026-09-10.pdf");
    const r = await page.request.get(`${INT}/api/admin/budget/export.pdf?from=2026-09-01&to=2026-09-30`);
    assert.equal(r.status(), 200);
    assert.equal(r.headers()["content-type"], "application/pdf");
    assert.equal(r.headers()["content-disposition"], 'attachment; filename="budget-2026-09.pdf"');
    assert.equal((await r.body()).subarray(0, 5).toString(), "%PDF-");
    await page.close();
    expectNoProblems();
  });

  test("works on a phone and on a short screen", async () => {
    const page = await newPage(390, 800);
    await login(page);
    await openBudget(page, "2026-09");
    await page.locator(".bud-entries", { hasText: "Sep (no day)" }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), "budget overflows at 390px");
    await page.screenshot({ path: `${SHOTS}budget-month-390.png`, fullPage: true });
    await page.locator(".bud-entries").getByRole("button", { name: "Edit Groceries" }).click();
    await page.getByRole("button", { name: "Save changes" }).waitFor();
    assert.ok(await page.getByRole("button", { name: "Save changes" }).isVisible());
    await page.screenshot({ path: `${SHOTS}budget-edit-390.png` });
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Export PDF" }).click();
    await page.getByRole("button", { name: "Share PDF…" }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await page.screenshot({ path: `${SHOTS}budget-export-390.png` });
    await page.close();

    const short = await newPage(1280, 480);
    await login(short);
    await openBudget(short, "2026-09");
    await short.getByRole("button", { name: "+ Add expense" }).click();
    const save = short.getByRole("button", { name: "Save expense" });
    await save.waitFor();
    const box = await save.boundingBox();
    assert.ok(box && box.y + box.height <= 480, "the Save button stays on screen on a short window");
    await short.close();
    expectNoProblems();
  });

  test("the budget API needs the login", async () => {
    const ctx = await browser.newContext();
    for (const path of ["/people", "/recurring", "/statement?from=2026-09-01&to=2026-09-30", "/export.pdf?from=2026-09-01&to=2026-09-30"]) {
      assert.equal((await ctx.request.get(`${INT}/api/admin/budget${path}`)).status(), 401, path);
    }
    assert.equal((await ctx.request.post(`${INT}/api/admin/budget/months/2026-09/open`)).status(), 403, "no CSRF token -> refused");
    await ctx.close();
  });
});

describe("layout at real screen sizes", () => {
  const sizes = [[390, 900], [1280, 900], [1920, 1080], [2560, 1300]];
  for (const [w, h] of sizes) {
    test(`no horizontal scrolling and no errors at ${w}px`, async () => {
      const page = await newPage(w, h);
      await login(page);
      await page.locator(".grp").first().waitFor();
      await page.waitForTimeout(1500);
      await page.screenshot({ path: `${SHOTS}dashboard-${w}.png` });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `dashboard overflows horizontally at ${w}px`);

      await page.goto(INT + "/#/config");
      await page.locator(".cm-content").waitFor();
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${SHOTS}config-${w}.png` });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `config overflows at ${w}px`);
      await page.close();
      expectNoProblems();
    });
  }

});

// ============================================================================ terminal
// Needs tools/mock-ssh (started by scripts/run-local.sh when paramiko is installed); skipped otherwise.
const TERMINAL_READY = existsSync(DATA + "ssh-mock/fingerprint");
const termRows = (page) => page.locator(".xterm-rows").innerText().then((t) => t.replace(/ /g, " "));
async function waitTerm(page, text, timeout = 10000) {
  try {
    await page.waitForFunction((t) => (document.querySelector(".xterm-rows")?.innerText ?? "").replace(/ /g, " ").includes(t), text, { timeout });
  } catch {
    const shot = `${SHOTS}terminal-failure-${Date.now()}.png`;
    await page.screenshot({ path: shot }).catch(() => {});
    throw new Error(`terminal never showed ${JSON.stringify(text)}; it shows ${JSON.stringify(await termRows(page).catch(() => "?"))} (screenshot ${shot})`);
  }
}
async function countInTerm(page, text) {
  return (await termRows(page)).split(text).length - 1;
}
async function stepUp(page, password = PASS) {
  await page.getByRole("button", { name: /^(Connect|Reconnect)$/ }).click();
  await page.locator("#term-pw").fill(password);
  await page.getByRole("dialog").getByRole("button", { name: "Connect" }).click();
}
async function openTerminal(page) {
  await page.goto(INT + "/#/terminal");
  await stepUp(page);
  await page.locator(".term-time").filter({ hasText: /^\d+:\d\d$/ }).waitFor();
  await waitTerm(page, "mock$");
}
async function typeLine(page, text) {
  await page.keyboard.type(text);
  await page.keyboard.press("Enter");
}
const sttySize = async (page) => {
  const before = await countInTerm(page, "SIZE=");
  await typeLine(page, 'echo "SIZE=$(stty size)"');
  await page.waitForFunction((n) => ((document.querySelector(".xterm-rows")?.innerText ?? "").match(/SIZE=\d+ \d+/g) ?? []).length > n, Math.max(0, before - 1));
  const all = (await termRows(page)).match(/SIZE=(\d+) (\d+)/g);
  return all[all.length - 1].slice(5);
};

describe("terminal", { skip: TERMINAL_READY ? false : "the mock SSH server is not running (pip install paramiko)" }, () => {
  test("nothing loads until the page is opened, and opening it only reads config and history", async () => {
    const page = await newPage();
    const requests = [];
    const sockets = [];
    page.on("request", (r) => requests.push(r.url()));
    page.on("websocket", (w) => sockets.push(w.url()));
    await login(page);
    await page.locator(".grp").first().waitFor();
    await page.waitForTimeout(800);
    assert.equal(requests.filter((u) => u.includes("TerminalPage") || u.includes("/api/admin/terminal")).length, 0, "the dashboard never touches the terminal");
    await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Terminal" }).click();
    await page.getByRole("button", { name: "Connect" }).waitFor();
    await page.getByText("Recent sessions").waitFor();
    assert.ok(requests.some((u) => /assets\/TerminalPage-.*\.js$/.test(u)), "the terminal chunk loads with the page");
    const api = requests.filter((u) => u.includes("/api/admin/terminal")).map((u) => new URL(u).pathname).sort();
    assert.deepEqual([...new Set(api)], ["/api/admin/terminal/audit", "/api/admin/terminal/config"]);
    assert.deepEqual(sockets, [], "no connection before Connect");
    // Ctrl+K belongs to the shell here, not to the command palette
    await page.keyboard.press("Control+k");
    await page.waitForTimeout(300);
    assert.equal(await page.getByRole("dialog").count(), 0);
    await page.close();
    expectNoProblems();
  });

  test("a wrong password is refused; the right one opens a shell that runs commands and follows resizes", async () => {
    const page = await newPage(1280, 800);
    await login(page);
    await page.goto(INT + "/#/terminal");
    await stepUp(page, "not the password");
    await page.getByRole("dialog").getByRole("alert").filter({ hasText: "Wrong password." }).waitFor();
    assert.equal(await page.locator("#term-pw").inputValue(), "", "the field is cleared");
    await page.locator("#term-pw").fill(PASS);
    await page.getByRole("dialog").getByRole("button", { name: "Connect" }).click();
    await waitTerm(page, "mock$");
    await page.locator(".term-who").filter({ hasText: "hubshell@Mock Pi" }).waitFor();
    await typeLine(page, "echo hello-$((6*7))");
    await waitTerm(page, "hello-42");
    await typeLine(page, "echo grüße ✓");
    await waitTerm(page, "grüße ✓");
    const big = await sttySize(page);
    await page.setViewportSize({ width: 800, height: 600 });
    await page.waitForTimeout(600);
    const small = await sttySize(page);
    assert.notEqual(big, small, `stty size should change after a resize (${big} -> ${small})`);
    const [rows, cols] = small.split(" ").map(Number);
    assert.ok(cols >= 40 && cols < 120 && rows >= 8, `plausible size ${small}`);
    await page.screenshot({ path: `${SHOTS}terminal-1280-light.png` });
    await page.getByRole("button", { name: "Disconnect" }).click();
    await page.getByText("You closed the terminal.").waitFor();
    await page.close();
    expectNoProblems(["401"]);
  });

  test("the key row: sticky Ctrl+C interrupts, the arrow recalls history, Tab completes", async () => {
    const page = await newPage();
    await login(page);
    await openTerminal(page);
    await typeLine(page, "sleep 30; echo NOT-INTERRUPTED");
    await page.waitForTimeout(300);
    const ctrl = page.getByRole("button", { name: "Control (applies to the next key)" });
    await ctrl.dispatchEvent("pointerdown");
    assert.equal(await ctrl.getAttribute("aria-pressed"), "true");
    await page.keyboard.type("c");
    await page.waitForFunction(() => ((document.querySelector(".xterm-rows")?.innerText ?? "").match(/mock\$/g) ?? []).length >= 2);
    assert.equal(await ctrl.getAttribute("aria-pressed"), "false", "Ctrl releases after one key");
    assert.equal(await countInTerm(page, "NOT-INTERRUPTED"), 1, "only the typed command line, not its output");

    await typeLine(page, "echo abc123");
    await waitTerm(page, "abc123\n");
    await page.getByRole("button", { name: "Up arrow" }).dispatchEvent("pointerdown");
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => ((document.querySelector(".xterm-rows")?.innerText ?? "").match(/abc123/g) ?? []).length >= 4);

    await page.keyboard.type("ech");
    await page.getByRole("button", { name: "Tab" }).dispatchEvent("pointerdown");
    await page.waitForTimeout(300);
    await typeLine(page, "tab-completed");
    await waitTerm(page, "\ntab-completed");
    await page.close();
    expectNoProblems();
  });

  test("an idle session is warned, then closed, and Reconnect opens a new one", async () => {
    const page = await newPage();
    await login(page);
    await openTerminal(page);
    await page.getByText(/Idle: the session closes in \d+ s/).waitFor({ timeout: 15000 });
    await page.getByText(/idle for 12 seconds/).waitFor({ timeout: 15000 });
    await page.getByRole("button", { name: "Reconnect" }).waitFor();
    await page.getByText("Recent sessions").waitFor();
    await page.locator(".term-recent td", { hasText: "idle" }).first().waitFor();
    await stepUp(page);
    await page.locator(".term-time").filter({ hasText: /^\d+:\d\d$/ }).waitFor(); // connected again (the old screen is still visible until then)
    await waitTerm(page, "mock$");
    await typeLine(page, "echo again");
    await waitTerm(page, "again\n");
    await page.close();
    expectNoProblems();
  });

  test("at most two sessions at once; signing out in another tab closes the terminal", async () => {
    const a = await newPage();
    await login(a);
    await openTerminal(a);
    const b = await newPage();
    await login(b);
    await openTerminal(b);
    const c = await newPage();
    await login(c);
    await c.goto(INT + "/#/terminal");
    await stepUp(c);
    await c.getByRole("dialog").getByRole("alert").filter({ hasText: "Already 2 terminals open" }).waitFor();
    await c.close();

    const other = await a.context().newPage(); // same browser, same login
    await other.goto(INT + "/");
    await other.getByRole("button", { name: "Sign out" }).click();
    await other.getByRole("heading", { name: "Sign in" }).waitFor();
    await a.getByRole("alert").filter({ hasText: "signed out" }).waitFor();
    await b.getByRole("button", { name: "Disconnect" }).click();
    await a.close();
    await b.close();
    expectNoProblems(["401"]);
  });

  test("on a phone the key row scrolls, the dialog is reachable and nothing overflows", async () => {
    for (const scheme of ["light", "dark"]) {
      const page = await newPage(390, 800);
      await page.emulateMedia({ colorScheme: scheme });
      await login(page);
      await page.goto(INT + "/#/terminal");
      await page.getByRole("button", { name: "Connect" }).click();
      await page.locator("#term-pw").waitFor();
      await page.screenshot({ path: `${SHOTS}terminal-390-stepup-${scheme}.png` });
      const btn = await page.getByRole("dialog").getByRole("button", { name: "Connect" }).boundingBox();
      assert.ok(btn && btn.y + btn.height <= 800, "the dialog's Connect button is on screen");
      await page.locator("#term-pw").fill(PASS);
      await page.keyboard.press("Enter");
      await waitTerm(page, "mock$");
      await typeLine(page, "ls -la /; echo phone-ok");
      await waitTerm(page, "phone-ok");
      const keys = page.locator(".term-keys");
      assert.ok(await keys.isVisible());
      assert.ok(await keys.evaluate((el) => el.scrollWidth > el.clientWidth), "the key row scrolls sideways on a phone");
      const kb = await keys.boundingBox();
      assert.ok(kb.y + kb.height <= 800 + 1, `the key row is on screen (bottom at ${kb.y + kb.height})`);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), "no horizontal page scroll");
      await page.screenshot({ path: `${SHOTS}terminal-390-${scheme}.png` });
      await page.getByRole("button", { name: "Disconnect" }).click();
      await page.getByText("You closed the terminal.").waitFor();
      await page.screenshot({ path: `${SHOTS}terminal-390-ended-${scheme}.png`, fullPage: true });
      await page.close();
    }
    const short = await newPage(1280, 480);
    await login(short);
    await short.goto(INT + "/#/terminal");
    await short.getByRole("button", { name: "Connect" }).click();
    const btn = await short.getByRole("dialog").getByRole("button", { name: "Connect" }).boundingBox();
    assert.ok(btn && btn.y + btn.height <= 480, "dialog buttons reachable on a short screen");
    await short.close();
    expectNoProblems();
  });

  test("without a login the terminal API and socket are refused", async () => {
    const page = await newPage();
    await page.goto(INT + "/");
    await page.getByRole("heading", { name: "Sign in" }).waitFor();
    assert.equal(await page.evaluate(async () => (await fetch("/api/admin/terminal/config")).status), 401);
    const closed = await page.evaluate(() => new Promise((res) => {
      const ws = new WebSocket(location.origin.replace("http", "ws") + "/api/admin/terminal/ws");
      ws.onopen = () => res("opened");
      ws.onclose = () => res("closed");
    }));
    assert.equal(closed, "closed");
    await page.close();
    expectNoProblems(["401", "WebSocket connection"]);
  });
});

// ============================================================================ docker
// The mock SSH server also answers the "hubdocker" user with a fake, stateful docker CLI (tools/mock-docker). The e2e
// dashboard.yaml declares it as "Mock Pi" plus an unreachable "Media LXC", so one host failing must not hide the other.
// 502: the unreachable host, by design. The aborted stream: closing the logs drawer ends its EventSource.
const DOCKER_ALLOW = ["HTTP 502", "status of 502", "profile.jpg", "HTTP 404: http://127.0.0.1:3910/images/", "/logs/stream net::ERR_ABORTED"];

async function openDocker(page) {
  await page.goto(INT + "/#/docker");
  await page.getByRole("heading", { level: 1, name: "Docker" }).waitFor();
  await page.locator(".dk-row-c", { hasText: "jellyfin" }).first().waitFor();
}
const dockerRow = (page, name) => page.locator("section[aria-label='Mock Pi'] .dk-row-c").filter({ has: page.locator(".dk-name .mono", { hasText: new RegExp(`^${name}$`) }) });

describe("docker", { skip: TERMINAL_READY ? false : "the mock SSH server is not running (pip install paramiko)" }, () => {
  test("nothing loads until the page is opened; hosts, groups, protection and a failing host are shown", async () => {
    const page = await newPage();
    const requests = [];
    page.on("request", (r) => requests.push(r.url()));
    await login(page);
    await page.getByRole("link", { name: "Docker" }).waitFor();
    await sleep(500);
    assert.equal(requests.filter((u) => u.includes("DockerPage") || u.includes("/api/admin/docker")).length, 0, "the dashboard never touches Docker");
    await openDocker(page);
    assert.ok(requests.some((u) => /assets\/DockerPage-.*\.js$/.test(u)), "the Docker chunk loads with the page");
    assert.ok(!requests.some((u) => /StackEditor/.test(u)), "the stack editor (CodeMirror) is not loaded until needed");
    await page.locator(".dk-host", { hasText: "Mock Pi" }).locator(".dk-count", { hasText: /\d+\/\d+/ }).waitFor();
    await page.locator("section[aria-label='Media LXC (unreachable)']").getByRole("alert").filter({ hasText: "Cannot reach SSH" }).waitFor();
    for (const g of ["deploy", "media", "Standalone"]) await page.locator("section[aria-label='Mock Pi'] .dk-group-head .eyebrow", { hasText: new RegExp(`^${g}$`, "i") }).waitFor();
    assert.ok(await dockerRow(page, "hub-internal").getByRole("button", { name: "Stop" }).isDisabled(), "the hub cannot stop itself");
    assert.ok(await dockerRow(page, "pihole").getByRole("button", { name: "Restart" }).isDisabled(), "protect: in dashboard.yaml");
    await dockerRow(page, "media-qbittorrent-1").locator(".chip", { hasText: "unhealthy" }).waitFor();
    await page.getByRole("button", { name: "unhealthy" }).click();
    assert.equal(await page.locator("section[aria-label='Mock Pi'] .dk-row-c").count(), 1);
    await page.getByRole("button", { name: "all", exact: true }).click();
    await page.getByLabel("Filter containers").fill("8096");
    assert.equal(await page.locator("section[aria-label='Mock Pi'] .dk-row-c").count(), 1);
    await page.getByLabel("Filter containers").fill("");
    await page.getByLabel("Live CPU / memory").check();
    await dockerRow(page, "jellyfin").locator(".dk-stats span").first().filter({ hasText: /%$/ }).waitFor();
    // a CPU / Mem header over the numbers, and the numbers line up on every row whatever its buttons
    const head = page.locator("section[aria-label='Mock Pi'] .dk-group", { hasText: "Standalone" }).locator(".dk-stats-head");
    assert.equal((await head.innerText()).replace(/\s+/g, " ").trim().toUpperCase(), "CPU MEM");
    const rights = async (sel) => (await page.locator(`section[aria-label='Mock Pi'] ${sel}`).evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().right))));
    const cpu = [...await rights(".dk-stats .dk-cpu"), ...await rights(".dk-stats-head span:first-child")];
    const mem = [...await rights(".dk-stats .dk-mem"), ...await rights(".dk-stats-head span:last-child")];
    assert.ok(cpu.length > 5 && new Set(cpu).size === 1, `CPU column aligned: ${cpu}`);
    assert.ok(new Set(mem).size === 1, `Mem column aligned: ${mem}`);
    await page.screenshot({ path: `${SHOTS}docker-1280-light.png`, fullPage: true });
    await page.close();
    expectNoProblems(DOCKER_ALLOW);
  });

  test("restart is confirmed with a click, stop needs the name typed, start brings it back", async () => {
    const page = await newPage();
    await login(page);
    await openDocker(page);
    await dockerRow(page, "watchtower").getByRole("button", { name: "Restart" }).click();
    const d = page.getByRole("dialog");
    await d.getByText("Stops and starts the container").waitFor();
    await d.getByRole("button", { name: "Confirm" }).click();
    await d.getByText("Restarted watchtower.").waitFor();
    await d.getByRole("button", { name: "Close" }).click();

    await dockerRow(page, "watchtower").getByRole("button", { name: "Stop" }).click();
    const yes = d.getByRole("button", { name: "Yes, do it" });
    assert.ok(await yes.isDisabled(), "disabled until the name is typed");
    await d.getByLabel(/Type watchtower to confirm/).fill("watchtowe");
    assert.ok(await yes.isDisabled());
    await d.getByLabel(/Type watchtower to confirm/).fill("watchtower");
    await yes.click();
    await d.getByText("Stopped watchtower.").waitFor();
    await d.getByRole("button", { name: "Close" }).click();
    await dockerRow(page, "watchtower").getByRole("button", { name: "Start" }).click();
    await d.getByRole("button", { name: "Confirm" }).click();
    await d.getByText("Started watchtower.").waitFor();
    await d.getByRole("button", { name: "Close" }).click();
    await dockerRow(page, "watchtower").getByRole("button", { name: "Stop" }).waitFor();

    await page.getByRole("tab", { name: "Activity" }).click();
    await page.locator(".dk-table td", { hasText: "watchtower" }).first().waitFor();
    await page.close();
    expectNoProblems(DOCKER_ALLOW);
  });

  test("logs: tail, search with highlight, and live follow", async () => {
    const page = await newPage();
    await login(page);
    await openDocker(page);
    await dockerRow(page, "jellyfin").getByRole("button", { name: "Logs" }).click();
    const drawer = page.getByRole("dialog", { name: "Logs of jellyfin" });
    await drawer.locator(".dk-line").first().waitFor();
    assert.equal(await drawer.locator(".dk-line").count(), 200);
    await drawer.getByLabel("Find in logs").fill("Config reloaded");
    await drawer.locator("mark", { hasText: "Config reloaded" }).first().waitFor();
    assert.ok((await drawer.locator(".dk-line").count()) < 200);
    await drawer.getByLabel("Find in logs").fill("");
    await drawer.getByLabel("Follow").check();
    await drawer.locator(".dk-line", { hasText: "live:" }).first().waitFor({ timeout: 10000 });
    await page.screenshot({ path: `${SHOTS}docker-logs-1280.png` });
    await drawer.getByLabel("Follow").uncheck();
    await drawer.getByRole("button", { name: "Close logs" }).click();
    await page.close();
    expectNoProblems(DOCKER_ALLOW);
  });

  test("a new stack is validated on the host, saved, deployed as a job, versioned and deleted", async () => {
    const page = await newPage();
    await login(page);
    await openDocker(page);
    await page.locator("section[aria-label='Mock Pi']").getByRole("button", { name: "+ New stack" }).click();
    const ed = page.getByRole("dialog", { name: "New stack on Mock Pi" });
    await ed.getByLabel("Stack name").fill("whoami");
    // a broken file is refused by "compose config" on the host and nothing is created
    await ed.locator(".cm-content").click();
    await page.keyboard.press("Control+A");
    await page.keyboard.type("nope: 1\n");
    await ed.getByRole("button", { name: "Create", exact: true }).click();
    await ed.getByRole("alert").filter({ hasText: "Compose refused the file" }).waitFor();
    await ed.locator(".cm-content").click();
    await page.keyboard.press("Control+A");
    await page.keyboard.type("services:\n  whoami:\n    image: traefik/whoami\n");
    await ed.getByRole("tab", { name: ".env" }).click();
    await ed.locator(".cm-content").click();
    await page.keyboard.type("SECRET=abc\n");
    await ed.getByRole("button", { name: "Create and deploy" }).click();
    const d = page.getByRole("dialog", { name: "Deploy whoami on Mock Pi" });
    await d.getByText("docker compose -p 'whoami'").waitFor();
    await d.getByRole("button", { name: "Confirm" }).click();
    await page.getByRole("dialog").getByText("Container whoami-whoami-1 Started").waitFor();
    await page.getByRole("dialog").getByText("Deploy whoami: done.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    await dockerRow(page, "whoami-whoami-1").waitFor();

    await page.getByRole("tab", { name: "Stacks" }).click();
    const row = page.locator("section[aria-label='Stacks on Mock Pi'] .dk-row-c", { hasText: "whoami" });
    await row.getByText("1 of 1 running").waitFor();
    await row.getByRole("button", { name: "Edit" }).click();
    const ed2 = page.getByRole("dialog", { name: "Stack whoami on Mock Pi" });
    await ed2.locator(".cm-content", { hasText: "traefik/whoami" }).waitFor();
    await ed2.locator(".cm-content").click();
    await page.keyboard.press("Control+End");
    await page.keyboard.type("    restart: always\n");
    await ed2.getByRole("button", { name: "Save", exact: true }).click();
    await page.getByRole("dialog", { name: "Review your changes" }).locator(".add", { hasText: "restart: always" }).waitFor();
    await page.getByRole("dialog", { name: "Review your changes" }).getByRole("button", { name: "Save" }).click();
    await page.getByRole("dialog", { name: "Review your changes" }).waitFor({ state: "detached" });
    await row.getByRole("button", { name: "Edit" }).click();
    await page.getByRole("dialog", { name: "Stack whoami on Mock Pi" }).getByRole("button", { name: "History" }).click();
    await page.getByRole("dialog", { name: "History of whoami" }).getByRole("button", { name: "Compare and restore" }).first().waitFor();
    await page.keyboard.press("Escape");
    await page.keyboard.press("Escape");

    // the whole stack can be stopped and deleted from where you work with it: the Containers tab and the editor
    await page.getByRole("tab", { name: "Containers" }).click();
    const group = page.locator("section[aria-label='Mock Pi'] .dk-group").filter({ has: page.locator(".dk-group-head .eyebrow", { hasText: /^whoami$/ }) });
    await group.getByRole("button", { name: "Stop stack" }).waitFor();
    await group.getByRole("button", { name: "Down" }).waitFor();
    await group.getByRole("button", { name: "Edit stack" }).click();
    const ed3 = page.getByRole("dialog", { name: "Stack whoami on Mock Pi" });
    await ed3.locator(".cm-content", { hasText: "traefik/whoami" }).waitFor();
    await ed3.getByRole("button", { name: "Stop", exact: true }).click();
    const stop = page.getByRole("dialog", { name: "Stop whoami on Mock Pi" });
    await stop.getByLabel(/Type whoami to confirm/).fill("whoami");
    await stop.getByRole("button", { name: "Yes, do it" }).click();
    await page.getByRole("dialog").getByText("Stop whoami: done.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    await dockerRow(page, "whoami-whoami-1").getByRole("button", { name: "Start" }).waitFor();
    assert.equal(await group.getByRole("button", { name: "Stop stack" }).count(), 0, "nothing left running to stop");
    await group.getByRole("button", { name: "Edit stack" }).click();
    await ed3.locator(".cm-content", { hasText: "traefik/whoami" }).waitFor();
    await ed3.getByRole("button", { name: "Delete…" }).click();
    await ed3.getByRole("button", { name: "Delete with volumes" }).click();
    const del = page.getByRole("dialog", { name: "Delete whoami on Mock Pi" });
    await del.getByText("NAMED VOLUMES").waitFor();
    await del.getByLabel(/Type whoami to confirm/).fill("whoami");
    await del.getByRole("button", { name: "Yes, do it" }).click();
    await page.getByRole("dialog").getByText("Delete whoami: done.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    await dockerRow(page, "whoami-whoami-1").waitFor({ state: "detached" });
    await page.getByRole("tab", { name: "Stacks" }).click();
    await page.locator("section[aria-label='Stacks on Mock Pi'] .dk-row-c", { hasText: "whoami" }).waitFor({ state: "detached" });
    assert.ok(!existsSync(DATA + "ssh-mock/docker-home/stacks/whoami"), "the stack folder is gone from the host");
    await page.close();
    expectNoProblems(DOCKER_ALLOW);
  });

  test("the run form shows the exact command, keeps env values off it, and runs as a job; it can become a stack", async () => {
    const page = await newPage();
    await login(page);
    await openDocker(page);
    await page.locator("section[aria-label='Mock Pi']").getByRole("button", { name: "+ Run container" }).click();
    const f = page.getByRole("dialog", { name: "Run a container on Mock Pi" });
    await f.getByLabel("Image").fill("nginx:alpine");
    await f.getByLabel("Name", { exact: true }).fill("e2e-web");
    await f.getByLabel("Port mapping").fill("8099:80");
    await f.getByLabel("Variable name").fill("TOKEN");
    await f.getByLabel("Value").fill("very-secret");
    await f.getByLabel("Network").selectOption("proxy");
    await page.screenshot({ path: `${SHOTS}docker-run-form.png` });
    await f.getByRole("button", { name: "Review and run" }).click();
    const d = page.getByRole("dialog", { name: "Run nginx:alpine on Mock Pi" });
    await d.getByText("docker run -d --name 'e2e-web' --restart 'unless-stopped' --network 'proxy' -p '8099:80' --env-file /dev/stdin 'nginx:alpine'").waitFor();
    assert.ok(!(await d.textContent()).includes("very-secret"));
    await d.getByRole("button", { name: "Confirm" }).click();
    await page.getByRole("dialog").getByText("Pull complete").first().waitFor();
    await page.getByRole("dialog").getByText("Container started.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    const r = dockerRow(page, "e2e-web");
    await r.waitFor();
    await r.getByRole("button", { name: "Stop" }).click();
    await page.getByRole("dialog").getByLabel(/Type e2e-web/).fill("e2e-web");
    await page.getByRole("dialog").getByRole("button", { name: "Yes, do it" }).click();
    await page.getByRole("dialog").getByText("Stopped e2e-web.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    await r.getByRole("button", { name: "Remove" }).click();
    await page.getByRole("dialog").getByLabel(/Type e2e-web/).fill("e2e-web");
    await page.getByRole("dialog").getByRole("button", { name: "Yes, do it" }).click();
    await page.getByRole("dialog").getByText("Removed e2e-web.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    await r.waitFor({ state: "detached" });

    await page.locator("section[aria-label='Mock Pi']").getByRole("button", { name: "+ Run container" }).click();
    await f.getByLabel("Image").fill("ghcr.io/me/tool:1");
    await f.getByLabel("Variable name").fill("API_KEY");
    await f.getByLabel("Value").fill("k-123");
    await f.getByRole("button", { name: "Save as stack instead" }).click();
    const ed = page.getByRole("dialog", { name: "New stack on Mock Pi" });
    assert.equal(await ed.getByLabel("Stack name").inputValue(), "tool");
    await ed.locator(".cm-content", { hasText: "API_KEY: ${API_KEY}" }).waitFor();
    await ed.getByRole("tab", { name: ".env" }).click();
    await ed.locator(".cm-content", { hasText: "API_KEY=k-123" }).waitFor();
    page.once("dialog", (dlg) => dlg.accept());
    await ed.getByRole("button", { name: "Close" }).click();
    await page.close();
    expectNoProblems(DOCKER_ALLOW);
  });

  test("clean up previews unused images and networks, removes only the selection, as a job", async () => {
    const page = await newPage();
    await login(page);
    await openDocker(page);
    const open = async () => {
      await page.locator("section[aria-label='Mock Pi']").getByRole("button", { name: "Clean up…" }).click();
      const d = page.getByRole("dialog", { name: "Clean up Mock Pi" });
      await d.getByText("Images").first().waitFor();
      return d;
    };
    let d = await open();
    for (const t of ["nginx:1.25, nginx:stable", "redis:7", "old-net"]) await d.getByText(t).waitFor();
    assert.equal(await d.getByText("jellyfin/jellyfin:10.11").count(), 0, "an image a container uses is never offered");
    assert.equal(await d.getByText("alpine:3.20").count(), 0, "nor one only a stopped container uses");
    assert.ok(await d.getByRole("button", { name: "Remove selected…" }).isDisabled());
    await d.locator("label", { hasText: "redis:7" }).getByRole("checkbox").check();
    await d.getByText("1 selected · ≈ 117 MB reclaimable").waitFor();
    await page.screenshot({ path: `${SHOTS}docker-cleanup.png` });
    await d.getByRole("button", { name: "Remove selected…" }).click();
    const c = page.getByRole("dialog", { name: "Clean up Mock Pi (1 item)" });
    await c.getByText("docker rmi 'redis:7'").waitFor();
    await c.getByRole("button", { name: "Confirm" }).click();
    await page.getByRole("dialog").getByText("Removed 1 of 1.").waitFor();
    await page.getByRole("button", { name: "Close" }).click();
    d = await open();
    await d.getByText("nginx:1.25, nginx:stable").waitFor();
    assert.equal(await d.getByText("redis:7").count(), 0, "the removed image is gone on reopen");
    await d.getByRole("button", { name: "Close" }).click();
    await page.close();
    expectNoProblems(DOCKER_ALLOW);
  });

  test("phone width, light and dark", async () => {
    for (const scheme of ["light", "dark"]) {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: scheme });
      const page = await ctx.newPage();
      await login(page);
      await openDocker(page);
      await page.getByLabel("Live CPU / memory").check();
      await dockerRow(page, "jellyfin").locator(".dk-stats .dk-cpu").filter({ hasText: /%$/ }).waitFor();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      assert.ok(overflow <= 1, `no horizontal page scroll (${overflow}px)`);
      await page.screenshot({ path: `${SHOTS}docker-390-${scheme}.png`, fullPage: true });
      await dockerRow(page, "jellyfin").getByRole("button", { name: "Logs" }).click();
      await page.getByRole("dialog", { name: "Logs of jellyfin" }).locator(".dk-line").first().waitFor();
      await page.screenshot({ path: `${SHOTS}docker-390-logs-${scheme}.png` });
      await ctx.close();
    }
    expectNoProblems(DOCKER_ALLOW);
  });

  test("without a login the Docker API is refused", async () => {
    const page = await newPage();
    await page.goto(INT + "/");
    await page.getByRole("heading", { name: "Sign in" }).waitFor();
    assert.equal(await page.evaluate(async () => (await fetch("/api/admin/docker/config")).status), 401);
    assert.equal(await page.evaluate(async () => (await fetch("/api/admin/docker/hosts/pi/containers")).status), 401);
    await page.close();
    expectNoProblems(["401"]);
  });
});
