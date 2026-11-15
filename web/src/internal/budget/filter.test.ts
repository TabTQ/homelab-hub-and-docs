import { describe, expect, it } from "vitest";
import { cleanFilter, filterExpenses, filterLabel, filterQuery, isFiltered, NO_FILTER, parseFilter, toggleId, viewMonthRow, viewRows, viewTransfers } from "./filter";
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

// Asha = 1, Ravi = 2, Meera = 3; amounts match their shares.
const x = (id: number, paidBy: number, shares: [number, number][]): Expense => ({
  id, name: `x${id}`, amountPaise: shares.reduce((a, s) => a + s[1], 0), paidBy, date: "2026-11-01", daySet: true, recurringId: null, recurringMonth: null, version: 1,
  shares: shares.map(([personId, paise]) => ({ personId, paise })),
});
const month = [x(1, 2, [[1, 100], [2, 100], [3, 100]]), x(2, 2, [[2, 50]]), x(3, 1, [[1, 40], [2, 40]]), x(4, 3, [[1, 200], [2, 200], [3, 200]]), x(5, 1, [[1, 30]])];

describe("figures from the filtered entries", () => {
  const rows = (f: Parameters<typeof filterExpenses>[1]) => viewRows(filterExpenses(month, f), [1, 2, 3]);
  it("matches the whole month when nothing is filtered", () => {
    expect(rows(NO_FILTER)).toEqual([
      { personId: 1, paidPaise: 110, sharePaise: 370, netPaise: -260 },
      { personId: 2, paidPaise: 350, sharePaise: 390, netPaise: -40 },
      { personId: 3, paidPaise: 600, sharePaise: 300, netPaise: 300 },
    ]);
  });
  it("nets always add up to zero when every entry balances", () => {
    for (const f of [NO_FILTER, { split: true, people: [] }, { split: false, people: [3] }, { split: true, people: [1] }]) {
      expect(rows(f).reduce((a, r) => a + r.netPaise, 0)).toBe(0);
    }
  });
  it("split only leaves out the solo entries", () => {
    expect(rows({ split: true, people: [] })).toEqual([
      { personId: 1, paidPaise: 80, sharePaise: 340, netPaise: -260 },
      { personId: 2, paidPaise: 300, sharePaise: 340, netPaise: -40 },
      { personId: 3, paidPaise: 600, sharePaise: 300, netPaise: 300 },
    ]);
  });
  it("a person filter counts the whole entries they are in", () => {
    expect(rows({ split: false, people: [3] }).map((r) => r.paidPaise)).toEqual([0, 300, 600]);
  });
  it("plans who pays whom from the filtered entries", () => {
    expect(viewTransfers(rows(NO_FILTER))).toEqual([{ fromId: 1, toId: 3, paise: 260 }, { fromId: 2, toId: 3, paise: 40 }]);
    expect(viewTransfers(rows({ split: false, people: [3] }))).toEqual([{ fromId: 1, toId: 3, paise: 300 }]);
    expect(viewTransfers(viewRows([x(1, 1, [[1, 5]])], [1, 2]))).toEqual([]);
  });
  it("breaks ties by person id", () => {
    const t = viewTransfers([{ personId: 1, paidPaise: 0, sharePaise: 0, netPaise: -50 }, { personId: 2, paidPaise: 0, sharePaise: 0, netPaise: -50 },
      { personId: 3, paidPaise: 0, sharePaise: 0, netPaise: 50 }, { personId: 4, paidPaise: 0, sharePaise: 0, netPaise: 50 }]);
    expect(t).toEqual([{ fromId: 1, toId: 3, paise: 50 }, { fromId: 2, toId: 4, paise: 50 }]);
  });
  it("builds the month row", () => {
    expect(viewMonthRow(filterExpenses(month, { split: true, people: [] }))).toEqual({ paidPaise: { 1: 80, 2: 300, 3: 600 }, totalPaise: 980 });
    expect(viewMonthRow([])).toEqual({ paidPaise: {}, totalPaise: 0 });
  });
});
