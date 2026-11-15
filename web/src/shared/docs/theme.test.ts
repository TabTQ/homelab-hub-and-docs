import { afterEach, describe, expect, it, vi } from "vitest";
import { currentTheme } from "./theme";

describe("currentTheme", () => {
  afterEach(() => { document.documentElement.removeAttribute("data-theme"); vi.unstubAllGlobals(); });

  it("treats a missing attribute as Stone (dark)", () => {
    document.documentElement.removeAttribute("data-theme");
    expect(currentTheme()).toBe("dark");
  });

  it("treats data-theme=light as Parchment (light)", () => {
    document.documentElement.dataset.theme = "light";
    expect(currentTheme()).toBe("light");
  });

  it("does not look at the OS preference", () => {
    const mm = vi.fn(() => ({ matches: false }));
    vi.stubGlobal("matchMedia", mm);
    expect(currentTheme()).toBe("dark");
    expect(mm).not.toHaveBeenCalled();
  });
});
