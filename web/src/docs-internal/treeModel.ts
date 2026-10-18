// The page tree as plain data (mirrors docs-internal's DocTree): building, filtering, and turning a drag or a key press
// into the {parentId, afterId} the move API takes. No React here, so all of it is unit-tested.

export type PageState = "internal" | "public" | "public-changed";
export interface TreeItem { id: number; parentId: number | null; position: number; kind: "rich" | "webapp"; title: string; publicHost: string | null; state: PageState }
export interface TreeNode extends TreeItem { children: TreeNode[] }
export interface Placement { parentId: number | null; afterId: number | null }
export type DropZone = "before" | "into" | "after";

export function siblings(items: TreeItem[], parentId: number | null): TreeItem[] {
  return items.filter((i) => i.parentId === parentId).sort((a, b) => a.position - b.position || a.id - b.id);
}

export function buildTree(items: TreeItem[]): TreeNode[] {
  const ids = new Set(items.map((i) => i.id));
  const build = (parentId: number | null): TreeNode[] =>
    siblings(items, parentId).map((i) => ({ ...i, children: build(i.id) }));
  // A page whose parent is missing (should not happen) is shown at the top level rather than lost.
  const orphans = items.filter((i) => i.parentId !== null && !ids.has(i.parentId)).map((i) => ({ ...i, children: build(i.id) }));
  return [...build(null), ...orphans];
}

/** Pages whose title matches every word, plus their ancestors so the match stays in context. */
export function filterTree(nodes: TreeNode[], query: string): TreeNode[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return nodes;
  const walk = (list: TreeNode[]): TreeNode[] => list.flatMap((n) => {
    const kids = walk(n.children);
    const hit = words.every((w) => n.title.toLowerCase().includes(w));
    return hit || kids.length ? [{ ...n, children: kids }] : [];
  });
  return walk(nodes);
}

export function descendants(items: TreeItem[], id: number): Set<number> {
  const out = new Set<number>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const i of items) if (i.parentId !== null && out.has(i.parentId) && !out.has(i.id)) { out.add(i.id); grew = true; }
  }
  return out;
}

export function ancestors(items: TreeItem[], id: number): TreeItem[] {
  const byId = new Map(items.map((i) => [i.id, i]));
  const out: TreeItem[] = [];
  let cur = byId.get(id);
  const seen = new Set<number>();
  while (cur && cur.parentId !== null && !seen.has(cur.parentId)) {
    seen.add(cur.parentId);
    const p = byId.get(cur.parentId);
    if (!p) break;
    out.unshift(p);
    cur = p;
  }
  return out;
}

export function pathTitle(items: TreeItem[], id: number | null): string {
  if (id === null) return "(top level)";
  const self = items.find((i) => i.id === id);
  return [...ancestors(items, id), ...(self ? [self] : [])].map((i) => i.title).join(" / ");
}

/**
 * Where a drop lands. "before"/"after" put the dragged page next to the target; "into" makes it the target's last
 * child. Returns null for a drop onto itself or into its own subtree.
 */
export function dropPlacement(items: TreeItem[], dragId: number, targetId: number, zone: DropZone): Placement | null {
  if (dragId === targetId) return null;
  const target = items.find((i) => i.id === targetId);
  if (!target) return null;
  const banned = descendants(items, dragId);
  if (zone === "into") {
    if (banned.has(targetId)) return null;
    const kids = siblings(items, targetId).filter((i) => i.id !== dragId);
    return { parentId: targetId, afterId: kids.length ? kids[kids.length - 1].id : null };
  }
  if (target.parentId !== null && banned.has(target.parentId)) return null;
  const sibs = siblings(items, target.parentId).filter((i) => i.id !== dragId);
  const at = sibs.findIndex((i) => i.id === targetId);
  if (zone === "after") return { parentId: target.parentId, afterId: targetId };
  return { parentId: target.parentId, afterId: at > 0 ? sibs[at - 1].id : null };
}

/** Alt+↑ / Alt+↓: one step among the siblings. Null at the ends. */
export function stepPlacement(items: TreeItem[], id: number, dir: -1 | 1): Placement | null {
  const self = items.find((i) => i.id === id);
  if (!self) return null;
  const sibs = siblings(items, self.parentId);
  const at = sibs.findIndex((i) => i.id === id);
  const others = sibs.filter((i) => i.id !== id);
  const newIndex = at + dir;
  if (newIndex < 0 || newIndex > others.length) return null;
  return { parentId: self.parentId, afterId: newIndex === 0 ? null : others[newIndex - 1].id };
}

/** Choices for the Move dialog: every page that is not the moving page or below it, indented by depth. */
export function parentChoices(items: TreeItem[], movingId: number | null): { id: number | null; label: string; depth: number }[] {
  const banned = movingId === null ? new Set<number>() : descendants(items, movingId);
  const out: { id: number | null; label: string; depth: number }[] = [{ id: null, label: "(top level)", depth: 0 }];
  const walk = (nodes: TreeNode[], depth: number) => {
    for (const n of nodes) {
      if (banned.has(n.id)) continue;
      out.push({ id: n.id, label: n.title, depth });
      walk(n.children, depth + 1);
    }
  };
  walk(buildTree(items), 1);
  return out;
}

/** Visible order of the tree (for ↑/↓ in the sidebar), honouring collapsed pages. */
export function visibleOrder(nodes: TreeNode[], collapsed: Set<number>): number[] {
  const out: number[] = [];
  const walk = (list: TreeNode[]) => list.forEach((n) => { out.push(n.id); if (!collapsed.has(n.id)) walk(n.children); });
  walk(nodes);
  return out;
}
