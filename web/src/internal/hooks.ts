import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../shared/api";
import type { DashboardView, StatusEntry, WidgetResponse } from "../shared/types";

/** Runs `fn` now and every `ms` while the tab is visible. Stops when the tab is hidden, resumes with a fresh run. */
export function usePolling(fn: () => void, ms: number, deps: unknown[] = []): void {
  const saved = useRef(fn);
  saved.current = fn;
  useEffect(() => {
    let timer: number | undefined;
    const tick = () => { if (!document.hidden) saved.current(); };
    saved.current();
    timer = window.setInterval(tick, ms);
    const vis = () => { if (!document.hidden) saved.current(); };
    document.addEventListener("visibilitychange", vis);
    return () => { window.clearInterval(timer); document.removeEventListener("visibilitychange", vis); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, ...deps]);
}

export interface Loaded<T> { data: T | null; error: string | null; loading: boolean; reload: () => void }

export function useDashboard(onUnauthorized: () => void): Loaded<DashboardView> {
  const [data, setData] = useState<DashboardView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const load = useCallback(() => {
    api<DashboardView>("/api/dashboard")
      .then((d) => { setData(d); setError(null); })
      .catch((e: ApiError) => { if (e.status === 401) onUnauthorized(); else setError(e.message); })
      .finally(() => setLoading(false));
  }, [onUnauthorized]);
  usePolling(load, 15_000);
  return { data, error, loading, reload: load };
}

/**
 * Live status lights. Server-Sent Events push changes; a slow poll reconciles anything a dropped connection missed.
 * EventSource reconnects on its own, so a restart of the server heals without a page reload.
 */
export function useStatus(): { map: Map<string, StatusEntry>; live: boolean; refresh: () => void } {
  const [map, setMap] = useState<Map<string, StatusEntry>>(new Map());
  const [live, setLive] = useState(false);

  useEffect(() => {
    let es: EventSource | null = null;
    try {
      es = new EventSource("/api/status/stream");
    } catch {
      return;
    }
    es.addEventListener("snapshot", (ev) => {
      const items = JSON.parse((ev as MessageEvent).data).items as Record<string, StatusEntry>;
      setMap(new Map(Object.entries(items)));
      setLive(true);
    });
    es.addEventListener("status", (ev) => {
      const e = JSON.parse((ev as MessageEvent).data) as StatusEntry;
      setMap((m) => new Map(m).set(e.id, e));
    });
    es.onerror = () => setLive(false);
    return () => es?.close();
  }, []);

  const pull = useCallback(() => {
    api<{ items: Record<string, StatusEntry> }>("/api/status").then((r) => setMap(new Map(Object.entries(r.items)))).catch(() => {});
  }, []);
  usePolling(pull, 60_000);

  const refresh = useCallback(() => { api("/api/status/refresh", { method: "POST" }).catch(() => {}).finally(() => setTimeout(pull, 2500)); }, [pull]);
  return { map, live, refresh };
}

export function useWidget(url: string | null, ms = 20_000): WidgetResponse | null {
  const [w, setW] = useState<WidgetResponse | null>(null);
  const load = useCallback(() => {
    if (!url) return;
    api<WidgetResponse>(url).then(setW).catch((e: ApiError) => setW({ state: "error", type: "", data: null, error: { code: "request", message: e.message } }));
  }, [url]);
  usePolling(load, ms, [url]);
  return w;
}
