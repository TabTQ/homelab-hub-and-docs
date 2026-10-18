import { describe, expect, it } from "vitest";
import { addMonths, dateLabel, daysIn, entryDay, isDate, monthEnd, monthLabel, parseMonth, pdfName, thisMonth } from "./dates";

describe("budget dates", () => {
  it("parses months within the allowed range", () => {
    expect(parseMonth("2026-09")).toBe("2026-09");
    expect(parseMonth(" 2026-09 ")).toBe("2026-09");
    for (const bad of ["2026-9", "2026-13", "2026-00", "1999-12", "2101-01", "", "sep", null, undefined]) expect(parseMonth(bad), String(bad)).toBeNull();
  });
  it("knows month lengths and moves between months", () => {
    expect(daysIn("2026-02")).toBe(28);
    expect(daysIn("2028-02")).toBe(29);
    expect(daysIn("2026-04")).toBe(30);
    expect(monthEnd("2026-09")).toBe("2026-09-30");
    expect(addMonths("2026-12", 1)).toBe("2027-01");
    expect(addMonths("2026-01", -1)).toBe("2025-12");
    expect(addMonths("2026-09", -13)).toBe("2025-08");
    expect(thisMonth(new Date(2026, 8, 26))).toBe("2026-09");
    expect(isDate("2026-02-29")).toBe(false);
    expect(isDate("2028-02-29")).toBe(true);
    expect(isDate("2026-9-1")).toBe(false);
  });
  it("labels months, dates and entries", () => {
    expect(monthLabel("2026-09")).toBe("September 2026");
    expect(monthLabel("2026-09", true)).toBe("Sep 2026");
    expect(dateLabel("2026-09-01")).toBe("1 Sep 2026");
    expect(entryDay("2026-09-01", true)).toBe("01 Sep");
    expect(entryDay("2026-09-15", false)).toBe("Sep (no day)");
  });
  it("names the PDF like the server", () => {
    expect(pdfName("2026-09-01", "2026-09-30")).toBe("budget-2026-09.pdf");
    expect(pdfName("2028-02-01", "2028-02-29")).toBe("budget-2028-02.pdf");
    expect(pdfName("2026-08-10", "2026-09-20")).toBe("budget-2026-08-10_to_2026-09-20.pdf");
    expect(pdfName("2026-09-01", "2026-09-29")).toBe("budget-2026-09-01_to_2026-09-29.pdf");
  });
});
