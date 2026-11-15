import { describe, expect, it } from "vitest";
import { coreSkills } from "./CharacterScreen";

const groups = [
  { name: "A", skills: [{ l: "One", n: 1 }, { l: "Two", n: 9, core: true }] },
  { name: "B", skills: [{ l: "Three", n: 5 }, { l: "Four", n: 3, core: true }, { l: "Five", n: 0 }] },
];

describe("coreSkills", () => {
  it("uses flagged skills in data order, with their group", () => {
    expect(coreSkills(groups).map((s) => [s.l, s.group])).toEqual([["Two", "A"], ["Four", "B"]]);
  });
  it("falls back to the most linked skills", () => {
    const plain = groups.map((g) => ({ ...g, skills: g.skills.map(({ core: _c, ...s }) => s) }));
    expect(coreSkills(plain, 2).map((s) => s.l)).toEqual(["Two", "Three"]);
  });
  it("caps flagged skills at the count", () => {
    const many = [{ name: "X", skills: ["a", "b", "c", "d", "e"].map((l) => ({ l, n: 1, core: true })) }];
    expect(coreSkills(many)).toHaveLength(4);
  });
});
