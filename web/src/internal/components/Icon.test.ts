import { describe, expect, it } from "vitest";
import { iconSource } from "./Icon";

describe("iconSource", () => {
  it("uses paths and https urls as they are", () => {
    expect(iconSource("/images/profile.jpg")).toEqual({ kind: "img", src: "/images/profile.jpg" });
    expect(iconSource("https://x.example/a.png")).toEqual({ kind: "img", src: "https://x.example/a.png" });
  });
  it("maps homepage-style names to the local icon folder", () => {
    expect(iconSource("proxmox.svg")).toEqual({ kind: "img", src: "/images/icons/proxmox.svg" });
    expect(iconSource("grafana.png")).toEqual({ kind: "img", src: "/images/icons/grafana.png" });
    expect(iconSource("guacamole")).toEqual({ kind: "img", src: "/images/icons/guacamole.png" });
    expect(iconSource("mdi-home-assistant")).toEqual({ kind: "mask", src: "/images/icons/mdi-home-assistant.svg" });
  });
  it("refuses names that could escape the folder", () => {
    for (const bad of ["../x.png", "a/b.svg", "a b", ".hidden", "x?y=1", ""]) expect(iconSource(bad)).toEqual({ kind: "none" });
    expect(iconSource(null)).toEqual({ kind: "none" });
  });
});
