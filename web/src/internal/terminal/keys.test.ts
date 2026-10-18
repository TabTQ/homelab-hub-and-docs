import { describe, expect, it } from "vitest";
import { applyModifiers, ctrlCode, KEY_ROW, keySequence } from "./keys";

describe("ctrlCode", () => {
  it("maps letters to control codes, either case", () => {
    expect(ctrlCode("a")).toBe("\x01");
    expect(ctrlCode("c")).toBe("\x03");
    expect(ctrlCode("D")).toBe("\x04");
    expect(ctrlCode("z")).toBe("\x1a");
    for (let i = 0; i < 26; i++) expect(ctrlCode(String.fromCharCode(97 + i))!.charCodeAt(0)).toBe(i + 1);
  });
  it("maps the punctuation terminals map", () => {
    expect(ctrlCode("[")).toBe("\x1b");
    expect(ctrlCode("\\")).toBe("\x1c");
    expect(ctrlCode("]")).toBe("\x1d");
    expect(ctrlCode("^")).toBe("\x1e");
    expect(ctrlCode("_")).toBe("\x1f");
    expect(ctrlCode(" ")).toBe("\x00");
    expect(ctrlCode("@")).toBe("\x00");
    expect(ctrlCode("?")).toBe("\x7f");
  });
  it("leaves other keys alone", () => {
    expect(ctrlCode("é")).toBeNull();
    expect(ctrlCode("ab")).toBeNull();
    expect(ctrlCode("")).toBeNull();
  });
});

describe("applyModifiers", () => {
  it("applies sticky Ctrl to one typed character", () => {
    expect(applyModifiers("c", { ctrl: true, alt: false })).toBe("\x03");
    expect(applyModifiers("é", { ctrl: true, alt: false })).toBe("é");
    expect(applyModifiers("ls", { ctrl: true, alt: false })).toBe("ls"); // a paste or autocomplete word is not a key
  });
  it("prefixes ESC for Alt, also combined with Ctrl", () => {
    expect(applyModifiers("b", { ctrl: false, alt: true })).toBe("\x1bb");
    expect(applyModifiers("x", { ctrl: true, alt: true })).toBe("\x1b\x18");
  });
  it("passes everything through without modifiers", () => {
    expect(applyModifiers("hello", { ctrl: false, alt: false })).toBe("hello");
    expect(applyModifiers("👍", { ctrl: true, alt: false })).toBe("👍");
  });
});

describe("keySequence", () => {
  it("sends the usual escape sequences", () => {
    expect(keySequence("esc", false)).toBe("\x1b");
    expect(keySequence("tab", false)).toBe("\t");
    expect(keySequence("up", false)).toBe("\x1b[A");
    expect(keySequence("left", false)).toBe("\x1b[D");
    expect(keySequence("home", false)).toBe("\x1b[H");
    expect(keySequence("end", false)).toBe("\x1b[F");
    expect(keySequence("pgup", false)).toBe("\x1b[5~");
    expect(keySequence("pgdn", true)).toBe("\x1b[6~");
    expect(keySequence("pipe", false)).toBe("|");
  });
  it("follows application cursor mode for arrows, Home and End", () => {
    expect(keySequence("up", true)).toBe("\x1bOA");
    expect(keySequence("home", true)).toBe("\x1bOH");
  });
  it("every non-sticky key in the row has a sequence", () => {
    for (const k of KEY_ROW) {
      if (k.sticky) expect(keySequence(k.id, false)).toBeNull();
      else expect(keySequence(k.id, false)).not.toBeNull();
      expect(k.aria.length).toBeGreaterThan(0);
    }
  });
});
