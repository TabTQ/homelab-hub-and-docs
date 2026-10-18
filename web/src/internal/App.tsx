import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../shared/api";
import { Palette, openPalette, usePaletteHotkey, type PaletteItem } from "../shared/components/Palette";
import { applyTheme, loadTheme, nextTheme, type ThemeChoice } from "../shared/theme";
import { ActionRunner, useRunAction } from "./components/ActionRunner";
import { useDashboard, useStatus } from "./hooks";
import { Dashboard } from "./pages/Dashboard";
import { Login } from "../shared/components/Login";
import type { EntryView } from "../shared/types";

// The YAML editor (CodeMirror) is the heaviest part of the app and only one page needs it.
const ConfigPage = lazy(() => import("./pages/ConfigPage"));
// The Budget page is only fetched when opened, so it costs nothing on the dashboard.
const BudgetPage = lazy(() => import("./pages/BudgetPage"));
// The Terminal page carries xterm.js; it is only downloaded when the page is opened.
const TerminalPage = lazy(() => import("./pages/TerminalPage"));
// The Docker page is fetched only when opened, and its stack editor (CodeMirror) only when a stack is edited.
const DockerPage = lazy(() => import("./pages/DockerPage"));

type Route = "dashboard" | "config" | "budget" | "terminal" | "docker";
interface AuthStatus { authenticated: boolean; mode: "all" | "edit" | "none"; passwordConfigured: boolean }

function useRoute(): [Route, (r: Route) => void] {
  const parse = (): Route => (location.hash.startsWith("#/config") ? "config"
    : location.hash.startsWith("#/budget") ? "budget" : location.hash.startsWith("#/terminal") ? "terminal"
    : location.hash.startsWith("#/docker") ? "docker" : "dashboard");
  const [route, setRoute] = useState<Route>(parse);
  useEffect(() => { const h = () => setRoute(parse()); window.addEventListener("hashchange", h); return () => window.removeEventListener("hashchange", h); }, []);
  return [route, (r) => { location.hash = r === "dashboard" ? "#/" : `#/${r}`; }];
}

function flatItems(entries: EntryView[]): { name: string; href: string | null; action: string | null }[] {
  return entries.flatMap((e) => (e.kind === "group" ? flatItems(e.entries) : [{ name: e.name, href: e.href, action: e.action }]));
}

export function App() {
  const [auth, setAuth] = useState<AuthStatus | null>(null);
  const [forceLogin, setForceLogin] = useState(false);
  const [offline, setOffline] = useState<string | null>(null);

  const loadAuth = useCallback(() => {
    api<AuthStatus>("/api/auth/status").then((a) => { setAuth(a); setOffline(null); }).catch((e) => setOffline(e.message));
  }, []);
  useEffect(loadAuth, [loadAuth]);

  if (offline) return <div className="wrap"><div className="login" role="alert"><h2>Cannot reach the server</h2><p className="sub">{offline}</p><button className="btn" onClick={loadAuth}>Try again</button></div></div>;
  if (!auth) return <div className="wrap"><p className="sub" style={{ marginTop: 40 }}>Loading…</p></div>;

  const needsLogin = forceLogin || (auth.mode === "all" && !auth.authenticated);
  if (needsLogin) {
    return <Login passwordConfigured={auth.passwordConfigured} onLoggedIn={() => { setForceLogin(false); loadAuth(); }} />;
  }
  const onUnauthorized = () => { setAuth({ ...auth, authenticated: false }); if (auth.mode !== "none") setForceLogin(true); };
  return <Shell auth={auth} onUnauthorized={onUnauthorized} onLogout={() => api("/api/auth/logout", { method: "POST" }).finally(() => { setForceLogin(false); loadAuth(); })} />;
}

function Shell({ auth, onUnauthorized, onLogout }: { auth: AuthStatus; onUnauthorized: () => void; onLogout: () => void }) {
  const [route, go] = useRoute();
  const [theme, setTheme] = useState<ThemeChoice>(loadTheme);
  useEffect(() => applyTheme(theme), [theme]);
  const dash = useDashboard(onUnauthorized);
  const status = useStatus();
  const [paletteOpen, setPaletteOpen] = useState(false);

  // On the Terminal page Ctrl+K belongs to the shell (readline: delete to end of line); the Search button still works.
  usePaletteHotkey(useCallback(() => { if (route !== "terminal") setPaletteOpen((o) => !o); }, [route]));
  useEffect(() => { const h = () => { setPaletteOpen(true); }; window.addEventListener("hub:open-palette", h); return () => window.removeEventListener("hub:open-palette", h); }, [route]);

  const header = (
    <div className="wrap">
      <header className="top">
        <div className="brand"><i aria-hidden="true" />Homelab Hub</div>
        <nav className="nav" aria-label="Main">
          {(["dashboard", "config", "budget", ...(dash.data?.features?.docker ? ["docker"] : []), ...(dash.data?.features?.terminal ? ["terminal"] : [])] as Route[]).map((r) => (
            <a key={r} href={r === "dashboard" ? "#/" : `#/${r}`} aria-current={route === r ? "page" : undefined}>{r === "dashboard" ? "Dashboard" : r === "config" ? "Config" : r === "budget" ? "Budget" : r === "docker" ? "Docker" : "Terminal"}</a>
          ))}
          {dash.data?.docsUrl && <a href={dash.data.docsUrl} title="Docs (its own app, on the Docs LXC)">Docs</a>}
        </nav>
        <span className="grow" />
        <span className="scope" title="This app is only reachable from your LAN and Tailscale"><b>●</b> Internal · LAN + Tailscale</span>
        <button className="btn" onClick={() => setTheme(nextTheme(theme))} aria-label="Change theme">Theme: {theme}</button>
        {auth.mode !== "none" && auth.authenticated && <button className="btn" onClick={onLogout}>Sign out</button>}
        <button className="kbd-search" onClick={openPalette} aria-label="Search"><span>Search services and actions</span><kbd>Ctrl K</kbd></button>
      </header>
    </div>
  );

  return (
    <ActionRunner onDone={status.refresh}>
      <Content route={route} go={go} header={header} dash={dash} status={status} onUnauthorized={onUnauthorized}
        palette={{ open: paletteOpen, setOpen: setPaletteOpen }} />
    </ActionRunner>
  );
}

function Content({ route, go, header, dash, status, onUnauthorized, palette }: {
  route: Route; go: (r: Route) => void; header: React.ReactNode; dash: ReturnType<typeof useDashboard>; status: ReturnType<typeof useStatus>;
  onUnauthorized: () => void; palette: { open: boolean; setOpen: (b: boolean) => void };
}) {
  const run = useRunAction();
  const view = dash.data;
  const items: PaletteItem[] = useMemo(() => {
    const pages: PaletteItem[] = [["dashboard", "Dashboard"], ["config", "Config"], ["budget", "Budget"], ...(view?.features?.docker ? [["docker", "Docker"]] : []), ...(view?.features?.terminal ? [["terminal", "Terminal"]] : [])].map(([r, t]) => ({ group: "Pages", title: t, hint: "Go to", run: () => go(r as Route) }));
    if (view?.docsUrl) pages.push({ group: "Pages", title: "Open Docs", hint: view.docsUrl, run: () => { location.href = view.docsUrl!; } });
    if (!view) return pages;
    const services = flatItems(view.groups.flatMap((g) => g.entries)).filter((i) => !i.action && i.href).map((i) => ({ group: "Services", title: i.name, hint: i.href ?? "", run: () => { window.open(i.href!, "_blank", "noopener,noreferrer"); } }));
    const actions = view.actions.map((a) => ({ group: "Actions", title: a.label, hint: a.type, run: () => run(a.id) }));
    return [...pages, ...services, ...actions];
  }, [view, go, run]);

  if (route === "terminal") {
    // Its own layout: the terminal fills the remaining height instead of sitting in the padded page flow.
    return (
      <>
        {header}
        <Suspense fallback={<div className="wrap"><p className="sub">Loading the terminal…</p></div>}><TerminalPage onUnauthorized={onUnauthorized} /></Suspense>
        <Palette items={items} open={palette.open} onClose={() => palette.setOpen(false)} />
      </>
    );
  }
  return (
    <>
      {header}
      <div className="wrap" style={{ paddingBottom: 80 }}>
        {route === "config" && <Suspense fallback={<p className="sub">Loading the editor…</p>}><ConfigPage onUnauthorized={onUnauthorized} onSaved={dash.reload} /></Suspense>}
        {route === "budget" && <Suspense fallback={<p className="sub">Loading the budget…</p>}><BudgetPage onUnauthorized={onUnauthorized} /></Suspense>}
        {route === "docker" && <Suspense fallback={<p className="sub">Loading Docker…</p>}><DockerPage onUnauthorized={onUnauthorized} /></Suspense>}
        {route === "dashboard" && (
          dash.error ? <div className="banner bad" role="alert">{dash.error}</div>
          : !view ? <p className="sub">Loading the dashboard…</p>
          : <Dashboard view={view} statuses={status.map} live={status.live} refresh={status.refresh}
              gotoConfig={() => go("config")} />
        )}
      </div>
      <Palette items={items} open={palette.open} onClose={() => palette.setOpen(false)} />
    </>
  );
}
