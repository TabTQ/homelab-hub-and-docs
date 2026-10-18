import { describe, expect, it } from "vitest";
import { buildTree, dropPlacement, filterTree, parentChoices, pathTitle, stepPlacement, visibleOrder, type TreeItem } from "./treeModel";

const t = (id: number, parentId: number | null, position: number, title: string): TreeItem =>
  ({ id, parentId, position, title, kind: "rich", publicHost: null, state: "internal" });

//  1 Homelab
//    2 Networking
//      4 Tunnel
//    3 Services
//  5 Notes
const items = [t(1, null, 0, "Homelab"), t(5, null, 1, "Notes"), t(2, 1, 0, "Networking"), t(3, 1, 1, "Services"), t(4, 2, 0, "Cloudflare tunnel")];

describe("tree model", () => {
  it("builds the nested tree in order", () => {
    const tree = buildTree(items);
    expect(tree.map((n) => n.title)).toEqual(["Homelab", "Notes"]);
    expect(tree[0].children.map((n) => n.title)).toEqual(["Networking", "Services"]);
    expect(tree[0].children[0].children[0].title).toBe("Cloudflare tunnel");
  });

  it("filters by title and keeps ancestors", () => {
    const f = filterTree(buildTree(items), "tunnel");
    expect(f.map((n) => n.title)).toEqual(["Homelab"]);
    expect(f[0].children[0].children.map((n) => n.title)).toEqual(["Cloudflare tunnel"]);
    expect(filterTree(buildTree(items), "nothing")).toEqual([]);
    expect(filterTree(buildTree(items), "  ")).toHaveLength(2);
  });

  it("turns drops into placements", () => {
    expect(dropPlacement(items, 5, 3, "into")).toEqual({ parentId: 3, afterId: null });
    expect(dropPlacement(items, 5, 2, "into")).toEqual({ parentId: 2, afterId: 4 });
    expect(dropPlacement(items, 5, 3, "before")).toEqual({ parentId: 1, afterId: 2 });
    expect(dropPlacement(items, 5, 2, "before")).toEqual({ parentId: 1, afterId: null });
    expect(dropPlacement(items, 3, 2, "after")).toEqual({ parentId: 1, afterId: 2 });
  });

  it("refuses drops into the page itself or its subtree", () => {
    expect(dropPlacement(items, 1, 1, "into")).toBeNull();
    expect(dropPlacement(items, 1, 4, "into")).toBeNull();
    expect(dropPlacement(items, 1, 4, "before")).toBeNull();
    expect(dropPlacement(items, 2, 99, "into")).toBeNull();
  });

  it("steps up and down among siblings", () => {
    expect(stepPlacement(items, 3, -1)).toEqual({ parentId: 1, afterId: null });
    expect(stepPlacement(items, 2, 1)).toEqual({ parentId: 1, afterId: 3 });
    expect(stepPlacement(items, 2, -1)).toBeNull();
    expect(stepPlacement(items, 3, 1)).toBeNull();
  });

  it("offers every parent except the moving subtree", () => {
    const c = parentChoices(items, 2);
    expect(c.map((x) => x.label)).toEqual(["(top level)", "Homelab", "Services", "Notes"]);
    expect(pathTitle(items, 4)).toBe("Homelab / Networking / Cloudflare tunnel");
    expect(pathTitle(items, null)).toBe("(top level)");
  });

  it("lists visible pages, skipping collapsed ones", () => {
    expect(visibleOrder(buildTree(items), new Set())).toEqual([1, 2, 4, 3, 5]);
    expect(visibleOrder(buildTree(items), new Set([2]))).toEqual([1, 2, 3, 5]);
  });
});
