// Who is looking at the hub: the answer of GET /api/member/context, and what it means for the app.

export interface MemberContext {
  memberPort: boolean; authenticated: boolean; role: "admin" | "member" | null;
  name: string | null; personId: number | null; canAdd: boolean; mustChange: boolean;
}

/** null = an older server without the endpoint (or it failed): the app behaves exactly as before. */
export const isMemberContext = (v: any): v is MemberContext => !!v && typeof v === "object" && typeof v.memberPort === "boolean";

/**
 * Member mode: on the member port, or signed in as a member, or (main port only) the person asked for the member
 * sign-in page. An admin who is signed in never lands in member mode by accident.
 */
export function appMode(ctx: MemberContext | null, wantsMember = false): "admin" | "member" {
  if (!ctx) return "admin";
  if (ctx.memberPort || ctx.role === "member") return "member";
  return wantsMember && ctx.role !== "admin" ? "member" : "admin";
}

export const wantsMemberHash = () => location.hash.startsWith("#/member");
