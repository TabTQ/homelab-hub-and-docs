import { describe, expect, it } from "vitest";
import { cleanFilter, filterExpenses, filterLabel, filterQuery, isFiltered, NO_FILTER, parseFilter, toggleId } from "./filter";
import type { Expense } from "./types";

const e = (id: number, paidBy: number, shareIds: number[]): Expense => ({
  id, name: `e${id}`, amountPaise: 100, paidBy, date: "2026-11-01", daySet: true, recurringId: null, recurringMonth: null, version: 1,
  shares: shareIds.map((personId) => ({ personId, paise: 50 })),
});
// Asha = 1, Ravi = 2, Meera = 3
const list = [e(1, 1, [1, 2, 3]), e(2, 2, [2]), e(3, 3, [1, 3]), e(4, 1, [1])];
const ids = (f: Parameters<typeof filterExpenses>[1]) => filterExpenses(list, f).map((x) => x.id);

describe("ledger filter", () => {
  it("keeps everything with no filter", () => {
    expect(isFiltered(NO_FILTER)).toBe(false);
    expect(ids(NO_FILTER)).toEqual([1, 2, 3, 4]);
  });
  it("split only keeps entries with more than one share", () => {
    expect(ids({ split: true, people: [] })).toEqual([1, 3]);
  });
  it("people keeps entries a chosen person paid or has a share in", () => {
    expect(ids({ split: false, people: [2] })).toEqual([1, 2]);
    expect(ids({ split: false, people: [3] })).toEqual([1, 3]);
    expect(ids({ split: false, people: [1, 2] })).toEqual([1, 2, 3, 4]);
  });
  it("combines split and people", () => {
    expect(ids({ split: true, people: [2] })).toEqual([1]);
    expect(ids({ split: true, people: [1] })).toEqual([1, 3]);
  });
  it("counts a payer who has no share", () => {
    expect(filterExpenses([e(9, 2, [1])], { split: false, people: [2] })).toHaveLength(1);
  });
  it("builds the export query", () => {
    expect(filterQuery(NO_FILTER)).toBe("");
    expect(filterQuery({ split: true, people: [] })).toBe("&split=true");
    expect(filterQuery({ split: true, people: [1, 2] })).toBe("&split=true&people=1,2");
    expect(filterQuery({ split: false, people: [3] })).toBe("&people=3");
  });
  it("labels the filter", () => {
    const people = [{ id: 1, name: "Asha", archived: false }, { id: 2, name: "Ravi", archived: false }];
    expect(filterLabel({ split: true, people: [1, 2] }, people)).toBe("Split entries · Asha, Ravi");
    expect(filterLabel(NO_FILTER, people)).toBe("");
  });
  it("reads a stored filter safely", () => {
    expect(parseFilter(null)).toEqual(NO_FILTER);
    expect(parseFilter("not json")).toEqual(NO_FILTER);
    expect(parseFilter('{"split":true,"people":[1,"x",2]}')).toEqual({ split: true, people: [1, 2] });
  });
  it("drops people who no longer exist and toggles ids", () => {
    expect(cleanFilter({ split: false, people: [1, 99] }, [{ id: 1, name: "Asha", archived: false }]).people).toEqual([1]);
    expect(toggleId([1], 2)).toEqual([1, 2]);
    expect(toggleId([1, 2], 1)).toEqual([2]);
  });
});
