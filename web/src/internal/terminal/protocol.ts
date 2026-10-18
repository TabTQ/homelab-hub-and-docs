// The Terminal page's WebSocket protocol (plan 5.3). Output arrives as binary frames; everything else is JSON text.

/** Input is sent in pieces well under the server's 64 KiB message limit (a big paste becomes several messages). */
export const MAX_INPUT_CHARS = 8192;

export type ServerMessage =
  | { type: "ready"; host: string; label: string; user: string }
  | { type: "warn"; secondsLeft: number }
  | { type: "exit"; reason: string; message: string; exitCode?: number }
  | { type: "pong" };

export const authMessage = (ticket: string, cols: number, rows: number) => JSON.stringify({ type: "auth", ticket, cols, rows });
export const resizeMessage = (cols: number, rows: number) => JSON.stringify({ type: "resize", cols, rows });
export const pingMessage = () => JSON.stringify({ type: "ping" });

/** One or more input messages; never splits a surrogate pair (an emoji) across two of them. */
export function inputMessages(data: string, max = MAX_INPUT_CHARS): string[] {
  const out: string[] = [];
  let i = 0;
  while (i < data.length) {
    let end = Math.min(i + max, data.length);
    const last = data.charCodeAt(end - 1);
    if (end < data.length && last >= 0xd800 && last <= 0xdbff) end--;
    out.push(JSON.stringify({ type: "input", data: data.slice(i, end) }));
    i = end;
  }
  return out;
}

/** Parses a server text frame; null for anything unexpected (it is then ignored). */
export function parseServerMessage(text: string): ServerMessage | null {
  let m: any;
  try { m = JSON.parse(text); } catch { return null; }
  if (!m || typeof m !== "object" || typeof m.type !== "string") return null;
  switch (m.type) {
    case "ready":
      return typeof m.host === "string" ? { type: "ready", host: m.host, label: String(m.label ?? m.host), user: String(m.user ?? "") } : null;
    case "warn":
      return typeof m.secondsLeft === "number" ? { type: "warn", secondsLeft: m.secondsLeft } : null;
    case "exit":
      return { type: "exit", reason: String(m.reason ?? "error"), message: String(m.message ?? ""), ...(typeof m.exitCode === "number" ? { exitCode: m.exitCode } : {}) };
    case "pong":
      return { type: "pong" };
    default:
      return null;
  }
}

/** What to tell the user when the socket closed without an exit message. */
export function closeText(code: number): string {
  if (code === 1008) return "You signed out of the dashboard (or the login expired), so the terminal was closed.";
  if (code === 1009) return "A message was too large for the terminal connection.";
  if (code === 1000) return "The terminal was closed.";
  return "The connection to the hub was lost.";
}

export function socketUrl(loc: { protocol: string; host: string }): string {
  return (loc.protocol === "https:" ? "wss://" : "ws://") + loc.host + "/api/admin/terminal/ws";
}

/** "12:05" style elapsed time. */
export function elapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const mm = String(m).padStart(h ? 2 : 1, "0"), ss = String(r).padStart(2, "0");
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Reads "reason=idle duration_s=12" style audit details. */
export function parseDetail(detail: string | null): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (detail ?? "").split(" ")) {
    const eq = part.indexOf("=");
    if (eq > 0) out[part.slice(0, eq)] = part.slice(eq + 1);
  }
  return out;
}

/** "15 minutes", "2 hours", "12 seconds": for the limits shown before connecting. */
export function duration(seconds: number): string {
  const unit = (n: number, w: string) => `${n} ${w}${n === 1 ? "" : "s"}`;
  if (seconds >= 3600 && seconds % 3600 === 0) return unit(seconds / 3600, "hour");
  if (seconds >= 60 && seconds % 60 === 0) return unit(seconds / 60, "minute");
  if (seconds >= 60) return unit(Math.round(seconds / 60), "minute");
  return unit(seconds, "second");
}
