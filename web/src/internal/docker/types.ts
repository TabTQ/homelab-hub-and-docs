// Shapes of /api/admin/docker/** (hub-internal docker/DockerController.java).

export interface DockerHost { id: string; label: string; host: string; user: string }
export interface DockerConfig { enabled: boolean; hosts: DockerHost[]; maxLogLines: number }

export interface Container {
  id: string; name: string; image: string; state: string; status: string; health: "healthy" | "unhealthy" | "starting" | null;
  ports: string; created: string; runningFor: string; project: string | null; service: string | null; stack: string | null;
  protected: boolean; protectedReason: string | null; canUpdate: boolean; canRemove: boolean;
}

export interface Stats { id: string; name: string; cpuPercent: number | null; memPercent: number | null; memUsage: string; netIO: string; blockIO: string; pids: string }

export interface StackRow { name: string; managed: boolean; workdir: string | null; containers: number; running: number }
export interface StackFiles { name: string; compose: string; env: string }
export interface StackVersion { id: number; ts: string; content: string }

export interface Prepared { token: string; expiresAt: string; title: string; detail: string; confirm: "click" | "type"; word: string | null; warnings: string[] }
export interface Confirmed { ok: boolean; message: string; jobId?: string }
export interface Job { id: string; hostId: string; title: string; state: "running" | "ok" | "failed"; started: string; ended: string | null; message: string | null; output: string }
export interface AuditRow { id: number; ts: string; hostId: string; target: string; action: string; result: string; detail: string | null; clientIp: string }

export interface RunSpec {
  image: string; name: string; ports: string[]; volumes: { source: string; target: string; readOnly: boolean }[];
  env: { key: string; value: string }[]; restart: string; network: string; command: string[];
}

/** Per-host result of a list call: the data, or the host's own error message. */
export type HostResult<T> = { status: "loading" } | { status: "ok"; data: T } | { status: "error"; message: string; code?: string };

/** GET /hosts/{h}/cleanup/preview: only what no container (running or stopped) uses. */
export interface CleanupImage { id: string; tags: string[]; size: string; created: string }
export interface CleanupNetwork { name: string; driver: string; created: string }
export interface CleanupPreview { images: CleanupImage[]; networks: CleanupNetwork[] }
export interface CleanupSelection { images: string[]; networks: string[] }
