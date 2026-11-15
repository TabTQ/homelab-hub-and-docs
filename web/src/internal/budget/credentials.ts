/** The text "Copy all" puts on the clipboard. The address line is added only when the page knows it. */
export function credentialsText(name: string, password: string, address?: string | null): string {
  return [`Name: ${name}`, `Password: ${password}`, address ? `Address: ${address}` : ""].filter(Boolean).join("\n");
}

/** Copies text. Plain http on the home network has no clipboard API, so it falls back to a hidden text box. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); return true; }
  } catch { /* fall through */ }
  try {
    const t = document.createElement("textarea");
    t.value = text;
    t.setAttribute("readonly", "");
    t.style.position = "fixed";
    t.style.opacity = "0";
    document.body.appendChild(t);
    t.select();
    const ok = document.execCommand("copy");
    t.remove();
    return ok;
  } catch { return false; }
}
