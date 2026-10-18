// Small fetch wrapper: JSON in and out, CSRF header from the XSRF-TOKEN cookie, errors that carry status and body.

export class ApiError extends Error {
  constructor(public status: number, public body: any, message?: string) {
    super(message ?? (body && (body.message || body.error)) ?? `HTTP ${status}`);
  }
}

function cookie(name: string): string | null {
  try {
    const m = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
    return m ? decodeURIComponent(m.slice(name.length + 1)) : null;
  } catch {
    return null;
  }
}

export async function api<T = any>(path: string, opts: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<T> {
  const method = opts.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json", ...(opts.headers ?? {}) };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    const t = cookie("XSRF-TOKEN");
    if (t) headers["X-XSRF-TOKEN"] = t;
  }
  let res: Response;
  try {
    res = await fetch(path, { method, headers, credentials: "same-origin", body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
  } catch {
    throw new ApiError(0, null, "Cannot reach the server. Is it running?");
  }
  const text = await res.text();
  let body: any = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = { message: text.slice(0, 200) }; }
  }
  if (!res.ok) throw new ApiError(res.status, body);
  return body as T;
}

/** Makes sure the XSRF-TOKEN cookie exists before the first write. */
export function primeCsrf(): Promise<unknown> {
  return api("/api/auth/status").catch(() => null);
}

/** Like api(), but also returns the response headers (the portfolio editor reads X-Public-Site). */
export async function apiWithHeaders<T = any>(path: string, opts: { method?: string; body?: unknown } = {}): Promise<{ data: T; headers: Headers }> {
  const method = opts.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    const t = cookie("XSRF-TOKEN");
    if (t) headers["X-XSRF-TOKEN"] = t;
  }
  let res: Response;
  try {
    res = await fetch(path, { method, headers, credentials: "same-origin", body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
  } catch {
    throw new ApiError(0, null, "Cannot reach the server. Is it running?");
  }
  const text = await res.text();
  let body: any = null;
  if (text) { try { body = JSON.parse(text); } catch { body = { message: text.slice(0, 200) }; } }
  if (!res.ok) throw new ApiError(res.status, body);
  return { data: body as T, headers: res.headers };
}

/** multipart/form-data upload with the CSRF header (Docs: images, attachments, webapp bundles). */
export async function upload<T = any>(path: string, file: Blob, fileName: string, fields: Record<string, string> = {}): Promise<T> {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  form.append("file", file, fileName);
  const headers: Record<string, string> = { Accept: "application/json" };
  const t = cookie("XSRF-TOKEN");
  if (t) headers["X-XSRF-TOKEN"] = t;
  let res: Response;
  try {
    res = await fetch(path, { method: "POST", headers, credentials: "same-origin", body: form });
  } catch {
    throw new ApiError(0, null, "Cannot reach the server. Is it running?");
  }
  const text = await res.text();
  let body: any = null;
  if (text) { try { body = JSON.parse(text); } catch { body = { message: text.slice(0, 200) }; } }
  if (!res.ok) throw new ApiError(res.status, body);
  return body as T;
}
