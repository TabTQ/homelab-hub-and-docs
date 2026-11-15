import { describe, expect, it } from "vitest";
import { credentialsText } from "../budget/credentials";
import { appMode, isMemberContext, type MemberContext } from "./context";
import { passwordProblem } from "./MemberApp";

const base: MemberContext = { memberPort: false, authenticated: false, role: null, name: null, personId: null, canAdd: false, mustChange: false };

describe("context to mode", () => {
  it("is admin mode when the server has no context endpoint", () => {
    expect(appMode(null)).toBe("admin");
    expect(isMemberContext(null)).toBe(false);
    expect(isMemberContext({ error: "not found" })).toBe(false);
    expect(isMemberContext(base)).toBe(true);
  });
  it("is member mode on the member port, signed in or not", () => {
    expect(appMode({ ...base, memberPort: true })).toBe("member");
    expect(appMode({ ...base, memberPort: true, authenticated: true, role: "member" })).toBe("member");
  });
  it("is member mode for a member session on the main port", () => {
    expect(appMode({ ...base, authenticated: true, role: "member", name: "Asha" })).toBe("member");
  });
  it("is admin mode for the admin and for anyone on the main port", () => {
    expect(appMode(base)).toBe("admin");
    expect(appMode({ ...base, authenticated: true, role: "admin" })).toBe("admin");
  });
  it("shows the member sign-in only when asked, and never over an admin session", () => {
    expect(appMode(base, true)).toBe("member");
    expect(appMode({ ...base, authenticated: true, role: "admin" }, true)).toBe("admin");
  });
});

describe("one-time password text", () => {
  it("lists name and password", () => {
    expect(credentialsText("Ravi", "k7Rm-Qe2x-94Vd")).toBe("Name: Ravi\nPassword: k7Rm-Qe2x-94Vd");
  });
  it("adds the address only when it is known", () => {
    expect(credentialsText("Ravi", "p", "http://192.0.2.10:3012/")).toBe("Name: Ravi\nPassword: p\nAddress: http://192.0.2.10:3012/");
    expect(credentialsText("Ravi", "p", null)).not.toContain("Address");
  });
});

describe("password rules", () => {
  it("matches the server rules", () => {
    expect(passwordProblem("old", "short", "short")).toMatch(/8/);
    expect(passwordProblem("same-pass1", "same-pass1", "same-pass1")).toMatch(/different/);
    expect(passwordProblem("old", "longenough1", "other")).toMatch(/match/);
    expect(passwordProblem("old", "longenough1", "longenough1")).toBeNull();
  });
});
