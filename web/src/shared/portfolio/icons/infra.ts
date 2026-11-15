import { ci, type IconDef } from "./shape";

const C = "Infrastructure";
export const INFRA_ICONS: Record<string, IconDef> = {
  docker: { l: "Docker", c: C, d: "M2.5 13h18.5c-.3 4-3.5 7.5-9.5 7.5-5.5 0-8.2-3-9-7.5z M6 13v-3h3v3 M9 13v-3h3v3 M12 13v-3h3v3 M9 10V7h3v3 M21 13c0-1.5-1-2-2-2" },
  proxmox: { l: "Proxmox VE", c: C, d: "M5 4l14 16 M19 4L5 20 M3.5 4h3.5 M17 4h3.5 M3.5 20h3.5 M17 20h3.5" },
  lxc: { l: "LXC container", c: C, d: "M3 3h18v18H3z M7 7h10v10H7z M10.5 10.5h3v3h-3z" },
  backup: { l: "Backup server", c: C, d: "M4 12a8 8 0 1 0 2.5-5.8 M4 4v4h4 M12 8v4l3 2" },
  linux: { l: "Linux", c: C, d: "M12 3c-2.5 0-3.5 2-3.5 4.5 0 1.5-.5 2.5-1.5 4-1.2 1.8-2 3.5-2 5.5 0 1 .5 1.5 1.5 2l2 .5c.5 1 1.5 1.5 3.5 1.5s3-.5 3.5-1.5l2-.5c1-.5 1.5-1 1.5-2 0-2-.8-3.7-2-5.5-1-1.5-1.5-2.5-1.5-4C15.5 5 14.5 3 12 3z M10.5 7.5h.01 M13.5 7.5h.01 M10.5 10l1.5 1 1.5-1" },
  tailscale: { l: "Tailscale", c: C, d: `${ci(6, 6, 1)}${ci(12, 6, 1)}${ci(18, 6, 1)}${ci(6, 18, 1)}${ci(12, 18, 1)}${ci(18, 18, 1)}`, f: "M6 10.8h.01M12 10.8h.01M18 10.8h.01M6 13.2h.01M12 13.2h.01M18 13.2h.01" },
  prometheus: { l: "Prometheus", c: C, d: "M12 2c1.5 2.5 4 4 4 7a4 4 0 0 1-8 0c0-1.5.8-2.5 1.5-3.5.3 1 .8 1.5 1.5 1.5 0-2 0-3.5 1-5z M8 14h8l-1.5 3h-5z M10.5 17l.5 4h2l.5-4" },
  grafana: { l: "Grafana", c: C, d: `${ci(12, 12, 3.5)}M12 2.5v3 M12 18.5v3 M2.5 12h3 M18.5 12h3 M5.3 5.3l2.1 2.1 M16.6 16.6l2.1 2.1 M5.3 18.7l2.1-2.1 M16.6 7.4l2.1-2.1` },
  exporter: { l: "Metrics exporter", c: C, d: "M4 18a8 8 0 0 1 16 0 M12 18l4.5-5.5 M12 18h.01 M4 18h2 M18 18h2" },
  server: { l: "Server", c: C, d: "M3 4h18v6H3z M3 14h18v6H3z M7 7h.01 M7 17h.01 M11 7h6 M11 17h6" },
  watchtower: { l: "Watchtower", c: C, d: "M8 21l2-14h4l2 14 M6 7h12 M10 3h4v4 M10 12h4 M9 17h6" },
  network: { l: "Network", c: C, d: "M9 3h6v4H9z M12 7v5 M5 12h14 M5 12v3 M12 12v3 M19 12v3 M3 15h4v4H3z M10 15h4v4h-4z M17 15h4v4h-4z" },
  chip: { l: "Single board computer", c: C, d: "M7 7h10v10H7z M10 3v4 M14 3v4 M10 17v4 M14 17v4 M3 10h4 M3 14h4 M17 10h4 M17 14h4 M10 11h4v2h-4z" },
  download: { l: "Downloads", c: C, d: "M12 3v12 M7.5 10.5L12 15l4.5-4.5 M4 17v4h16v-4" },
  doc: { l: "Documents", c: C, d: "M6 3h9l4 4v14H6z M15 3v4h4 M9 12h7 M9 16h7 M9 8h3" },
};
