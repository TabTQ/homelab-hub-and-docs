import { describe, expect, it } from "vitest";
import { formatRupees, group, MAX_PAISE, paiseToInput, parseRupees, splitPaise } from "./money";

// The same vectors as BudgetDomainTest in Java, so the screen and the server always agree.
describe("budget money", () => {
  it("formats with Indian grouping", () => {
    expect(formatRupees(0)).toBe("₹0.00");
    expect(formatRupees(5)).toBe("₹0.05");
    expect(formatRupees(99)).toBe("₹0.99");
    expect(formatRupees(100000)).toBe("₹1,000.00");
    expect(formatRupees(12500050)).toBe("₹1,25,000.50");
    expect(formatRupees(123456789)).toBe("₹12,34,567.89");
    expect(formatRupees(MAX_PAISE)).toBe("₹10,00,00,000.00");
    expect(formatRupees(-30000)).toBe("−₹300.00");
    expect(formatRupees(-821750, { symbol: false })).toBe("−8,217.50");
    expect(formatRupees(1638349, { sign: true, symbol: false })).toBe("+16,383.49");
    expect(formatRupees(0, { sign: true, symbol: false })).toBe("0.00");
    expect(group("1234567")).toBe("12,34,567");
    expect(group("123")).toBe("123");
    expect(group("1234")).toBe("1,234");
  });

  it("parses rupee text exactly", () => {
    expect(parseRupees("1850")).toBe(185000);
    expect(parseRupees("1850.5")).toBe(185050);
    expect(parseRupees("1,850.50")).toBe(185050);
    expect(parseRupees("1,25,000.50")).toBe(12500050);
    expect(parseRupees(" 12 ")).toBe(1200);
    expect(parseRupees("12.")).toBe(1200);
    expect(parseRupees(".5")).toBe(50);
    expect(parseRupees("0.01")).toBe(1);
    expect(parseRupees("100000000")).toBe(MAX_PAISE);
    // 0.1 + 0.2 style float errors cannot happen: 1850.50 is exactly 185050
    expect(parseRupees("0.29")).toBe(29);
    for (const bad of ["", " ", ".", "0", "0.00", "1e3", "-5", "1.234", "abc", "12a", "99999999999", "100000000.01", "1,,000", ",100", "1.2.3", "+5"]) {
      expect(parseRupees(bad), bad).toBeNull();
    }
  });

  it("turns paise back into input text", () => {
    expect(paiseToInput(185050)).toBe("1850.50");
    expect(paiseToInput(5)).toBe("0.05");
    expect(parseRupees(paiseToInput(123456789))).toBe(123456789);
  });

  it("splits like the server", () => {
    expect(splitPaise(185050, [3, 1, 2])).toEqual([{ id: 1, paise: 61684 }, { id: 2, paise: 61683 }, { id: 3, paise: 61683 }]);
    expect(splitPaise(119900, [1, 2, 3]).map((s) => s.paise)).toEqual([39967, 39967, 39966]);
    expect(splitPaise(1, [1, 2, 3]).map((s) => s.paise)).toEqual([1, 0, 0]);
    expect(splitPaise(100, [])).toEqual([]);
    for (let i = 0; i < 500; i++) {
      const n = 1 + (i % 50);
      const amount = 1 + ((i * 7919 * 104729) % MAX_PAISE);
      const s = splitPaise(amount, Array.from({ length: n }, (_, k) => k + 1));
      expect(s.reduce((a, b) => a + b.paise, 0)).toBe(amount);
      expect(Math.max(...s.map((x) => x.paise)) - Math.min(...s.map((x) => x.paise))).toBeLessThanOrEqual(1);
    }
  });
});
