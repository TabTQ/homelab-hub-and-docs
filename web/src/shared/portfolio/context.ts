import { createContext, useContext, type Dispatch, type SetStateAction } from "react";
import type { FilterState } from "../filter";
import type { Portfolio } from "../types";

export const TABS = [
  { key: "character", label: "Character" },
  { key: "skills", label: "Skills" },
  { key: "quests", label: "Quests" },
  { key: "inventory", label: "Inventory" },
  { key: "world", label: "World" },
  { key: "codex", label: "Codex" },
  { key: "summary", label: "Summary" },
] as const;
export type TabKey = (typeof TABS)[number]["key"];
export const DEFAULT_TAB: TabKey = "character";

export function parseTab(hash: string): TabKey {
  const m = /^#\/([a-z]+)/.exec(hash);
  return TABS.find((t) => t.key === m?.[1])?.key ?? DEFAULT_TAB;
}

/** What every portfolio screen can read and change. The skill filter is shared by all tabs. */
export interface PortfolioContextValue {
  data: Portfolio;
  filter: FilterState;
  setFilter: Dispatch<SetStateAction<FilterState>>;
  /** Toggle one skill in the filter. */
  pickSkill: (skill: string) => void;
  /** Clear the picked skills and the text query. */
  clear: () => void;
  /** Switch to a tab (sets the hash). */
  go: (tab: TabKey) => void;
}

export const PortfolioContext = createContext<PortfolioContextValue | null>(null);

export function usePortfolio(): PortfolioContextValue {
  const v = useContext(PortfolioContext);
  if (!v) throw new Error("usePortfolio must be used inside the portfolio shell");
  return v;
}
