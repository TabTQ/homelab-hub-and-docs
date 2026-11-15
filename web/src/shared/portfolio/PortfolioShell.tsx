import { useCallback, useEffect, useMemo, useState, type ComponentType, type ReactNode } from "react";
import { toggle, type FilterState } from "../filter";
import { Crest } from "../components/Crest";
import { Palette, usePaletteHotkey, type PaletteItem } from "../components/Palette";
import { PortfolioPage } from "../components/PortfolioPage";
import { applyTheme, loadTheme, nextTheme, themeLabel, type ThemeChoice } from "../theme";
import { safeHref } from "../util";
import type { Portfolio } from "../types";
import { PortfolioContext, TABS, parseTab, type PortfolioContextValue, type PortfolioEditHooks, type TabKey } from "./context";
import { Embers } from "./Embers";
import { Tour } from "./Tour";
import { CharacterScreen, CrestRing, ringIcons } from "./screens/CharacterScreen";
import { CodexScreen, InventoryScreen, QuestsScreen, SkillsScreen, WorldScreen } from "./screens";
import "./portfolio.css";

const SCREENS: Record<Exclude<TabKey, "summary">, ComponentType> = {
  character: CharacterScreen, skills: SkillsScreen, quests: QuestsScreen, inventory: InventoryScreen, world: WorldScreen, codex: CodexScreen,
};

/** The current tab. On the public site it follows the URL hash. In the editor the hash belongs to the Docs app, so the tab lives in state. */
function useTab(own: boolean): [TabKey, (t: TabKey) => void] {
  const [tab, setTab] = useState<TabKey>(() => (own ? parseTab("") : parseTab(location.hash)));
  useEffect(() => {
    if (own) return;
    const on = () => setTab(parseTab(location.hash));
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, [own]);
  const go = useCallback((t: TabKey) => { if (own) setTab(t); else location.hash = `#/${t}`; }, [own]);
  return [tab, go];
}

const TAB_HREF = new RegExp(`^#/(${TABS.map((t) => t.key).join("|")})$`);

/** True when keys typed here belong to a field, so the tab keys must stay out of the way. */
export function inField(t: EventTarget | null): boolean {
  return t instanceof Element && !!t.closest("input, textarea, select, [contenteditable]:not([contenteditable='false'])");
}

const SUMMARY_SECTIONS: [string, string][] = [["about", "About"], ["experience", "Experience"], ["projects", "Projects"], ["education", "Education"], ["homelab", "Homelab"], ["contact", "Contact"]];

function SummaryHero({ data }: { data: Portfolio }) {
  const p = data.profile;
  return (
    <div className="pf-hero">
      <div>
        {(p.location || p.status) && <div className="eyebrow">{[p.location, p.status].filter(Boolean).join(" · ")}</div>}
        <h1 className="display">{p.name}</h1>
        <div className="role">{p.title}</div>
        {p.tagline && <p className="sub">{p.tagline}</p>}
        <div className="pf-hero-cta">
          <a className="btn pri" href="#experience" onClick={(e) => { e.preventDefault(); document.getElementById("experience")?.scrollIntoView({ behavior: "smooth" }); }}>View experience</a>
          <a className="btn" href="#contact" onClick={(e) => { e.preventDefault(); document.getElementById("contact")?.scrollIntoView({ behavior: "smooth" }); }}>Contact</a>
        </div>
        {p.stats.length > 0 && (
          <div className="nums">
            {p.stats.map((s, i) => <div className="panel" key={i}><b className="num">{s.value}</b><span>{s.label}</span></div>)}
          </div>
        )}
      </div>
      <div className="port"><CrestRing icons={ringIcons(data.skills)} /></div>
    </div>
  );
}

interface ShellProps {
  data: Portfolio;
  /** Edit hooks. Setting them turns on edit mode: every screen shows its edit and add buttons. */
  edit?: PortfolioEditHooks;
  /** Editor slots, shown above the tab bar. */
  header?: ReactNode;
  ribbon?: ReactNode;
  /** Extra search entries (the editor adds Docs pages). */
  extraPalette?: PaletteItem[];
}

export function PortfolioShell({ data, edit, header, ribbon, extraPalette }: ShellProps) {
  const editing = !!edit;
  const [tab, go] = useTab(editing);
  const [filter, setFilter] = useState<FilterState>({ picked: new Set(), mode: "all", q: "" });
  const [theme, setTheme] = useState<ThemeChoice>(loadTheme);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [tour, setTour] = useState(false);
  useEffect(() => applyTheme(theme), [theme]);

  const pickSkill = useCallback((s: string) => setFilter((f) => ({ ...f, picked: toggle(f.picked, s) })), []);
  const clear = useCallback(() => setFilter((f) => ({ ...f, picked: new Set(), q: "" })), []);
  const ctx = useMemo<PortfolioContextValue>(() => ({ data, filter, setFilter, pickSkill, clear, go, edit }), [data, filter, pickSkill, clear, go, edit]);

  const togglePalette = useCallback(() => setPaletteOpen((o) => !o), []);
  usePaletteHotkey(togglePalette);
  useEffect(() => {
    const h = () => setPaletteOpen(true);
    window.addEventListener("hub:open-palette", h);
    return () => window.removeEventListener("hub:open-palette", h);
  }, []);

  // Q and E move between tabs, unless a field has focus or a dialog is open.
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (paletteOpen || tour || e.ctrlKey || e.metaKey || e.altKey || inField(e.target)) return;
      if (document.querySelector('[role="dialog"][aria-modal="true"]')) return; // an edit drawer is open
      const k = e.key.toLowerCase();
      if (k !== "q" && k !== "e") return;
      const i = TABS.findIndex((t) => t.key === tab) + (k === "e" ? 1 : -1);
      if (i >= 0 && i < TABS.length) go(TABS[i].key);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [tab, paletteOpen, tour, go]);

  useEffect(() => { window.scrollTo(0, 0); }, [tab]);

  // In the editor, links to other tabs must not touch the URL hash (the Docs app owns it).
  const onRootClick = editing ? (e: React.MouseEvent) => {
    const a = (e.target as Element).closest?.("a[href^='#/']");
    const m = a ? TAB_HREF.exec(a.getAttribute("href") ?? "") : null;
    if (m && !e.metaKey && !e.ctrlKey && !e.shiftKey) { e.preventDefault(); go(m[1] as TabKey); }
  } : undefined;

  const inSummary = useCallback((id: string) => () => {
    clear(); go("summary");
    setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }), 120);
  }, [clear, go]);
  const items: PaletteItem[] = useMemo(() => [
    ...TABS.map((t) => ({ group: "Go to", title: t.label, hint: "Tab", run: () => go(t.key) })),
    ...SUMMARY_SECTIONS.map(([id, t]) => ({ group: "Summary sections", title: t, hint: "Jump to", run: inSummary(id) })),
    ...data.experience.map((e) => ({ group: "Experience", title: e.role, hint: e.company, run: inSummary(e.id) })),
    ...data.projects.map((x) => ({ group: "Projects", title: x.title, hint: x.kind, run: inSummary(x.id) })),
    ...data.homelab.map((d) => ({ group: "Homelab", title: d.name, hint: d.ip ?? "", run: inSummary(d.id) })),
    ...data.skills.flatMap((g) => g.skills.map((sk) => ({ group: "Skills", title: sk.l, hint: sk.n ? `${sk.n} linked` : "no linked content", run: () => { pickSkill(sk.l); go("summary"); } }))),
    ...(extraPalette ?? []),
  ], [data, go, inSummary, pickSkill, extraPalette]);

  const s = data.profile.socials;
  const github = safeHref(s.github), linkedin = safeHref(s.linkedin), resume = safeHref(s.resume);
  const Screen = tab === "summary" ? null : SCREENS[tab];

  return (
    <PortfolioContext.Provider value={ctx}>
      <div className={"pf" + (editing ? " editing" : "")} onClick={onRootClick}>
        {!editing && <Embers />}
        {header}
        {ribbon && <div className="pf-ribbon">{ribbon}</div>}
        <header className="vh-top">
          <a className="vh-brand" href="#/character"><Crest />{data.profile.name}</a>
          <nav className="vh-tabs" aria-label="Sections">
            <span className="key" aria-hidden="true">Q</span>
            {TABS.map((t) => <a key={t.key} href={`#/${t.key}`} aria-current={t.key === tab ? "page" : undefined}>{t.label}</a>)}
            <span className="key" aria-hidden="true">E</span>
          </nav>
          <div className="vh-actions">
            {!editing && <button className="btn sm" onClick={() => setTour(true)} aria-label="Show the 10 second navigation tutorial">? Guide</button>}
            <button className="btn sm" onClick={() => setTheme(nextTheme(theme))} aria-label="Change theme">Theme: {themeLabel(theme)}</button>
            <button className="btn sm" onClick={() => setPaletteOpen(true)} aria-label="Search portfolio">Search <span className="key">Ctrl K</span></button>
          </div>
        </header>

        <main className={"vh-main" + (tab === "summary" ? " flush" : "")} id="content" data-tour-main>
          {Screen ? <Screen /> : (
            <PortfolioPage embedded data={data} hero={<SummaryHero data={data} />} hooks={edit} />
          )}
        </main>

        <footer className="vh-hints">
          <span className="hint dim"><span className="key">Q</span><span className="key">E</span>Switch tab</span>
          <button className="hint" onClick={() => setPaletteOpen(true)}><span className="key">Ctrl K</span>Search</button>
          <span className="grow" />
          {resume && <a className="hint" href={resume} target="_blank" rel="noopener noreferrer"><span className="key">↗</span>Résumé</a>}
          {github && <a className="hint" href={github} target="_blank" rel="noopener noreferrer"><span className="key">↗</span>GitHub</a>}
          {linkedin && <a className="hint" href={linkedin} target="_blank" rel="noopener noreferrer"><span className="key">↗</span>LinkedIn</a>}
        </footer>

        {tour && <Tour onClose={() => setTour(false)} />}
        <Palette items={items} open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      </div>
    </PortfolioContext.Provider>
  );
}
