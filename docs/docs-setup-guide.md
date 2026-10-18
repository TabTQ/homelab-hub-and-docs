# Docs: setup guide (LXC, Cloudflare tunnel, and everything after the code)

This is the hands-on companion to [`design-history/docs-feature-plan.md`](design-history/docs-feature-plan.md). The code is done and tested locally; this guide takes
you from "nothing on Proxmox" to "a page live at `https://page1.domain.com`, the editor on `https://docs.<tailnet>.ts.net`, and a
**Docs** button on the hub".

Placeholders used throughout (replace them with your own values, never commit the real ones):

| Placeholder | Meaning | Example |
|---|---|---|
| `domain.com` | Your domain on Cloudflare | `example.org` |
| `<tailnet>` | Your Tailscale tailnet name | `tail1234` |
| `<pi>` | The Pi's Tailscale machine name | `pi` |
| `<lxc-id>` | The new container's Proxmox ID | `120` |
| `<lxc-ip>` | The container's static LAN address | `192.168.10.50` |
| `<pi-ip>` | The Pi's LAN address | `192.168.10.101` |
| `<pve-ip>` | The Proxmox host's LAN address | `192.168.10.102` |
| `<gw-ip>` | Your router / DNS | `192.168.10.1` |
| `<lan>` | Your LAN subnet | `192.168.10.0/24` |

> Order matters in one place only: the **tunnel token** (step 6) must exist before the first `docker compose up` (step 7), because
> compose refuses to start without it. Everything else can be done in the order below.

---

## 0. What you end up with

```
Internet ──> Cloudflare (*.domain.com → "docs" tunnel) ──> cloudflared ─┐  (inside the LXC, compose network "edge")
                                                                         └─> docs-public  (no host port)
You (LAN)        ──> http://<lxc-ip>:3020 ───────────────────────────────> docs-internal (editor, login)
You (Tailscale)  ──> https://docs.<tailnet>.ts.net (tailscale serve) ────> docs-internal
Hub on the Pi    ──> only a link; the browser navigates. No connection between the Pi and the LXC.
```

Four containers run on the LXC: `docs-internal` (port 3020), `docs-public` (no port), `hub-public` (no port, the **portfolio**, see section 17) and
`cloudflared`. None of them depends on another: each starts, restarts and stays up on its own. Data lives in
`/opt/homelab-docs/data/internal` (`docs.db`, `blobs/`, `backups/`). The Pi is not touched except for one line in `dashboard.yaml`.

## 1. Before you start

- Proxmox VE **8.1 or newer** (ZFS 2.2+, so Docker's overlay storage works inside an unprivileged LXC on ZFS). Check with `pveversion`.
- A Debian 12 container template: Proxmox UI → your storage (e.g. `local`) → **CT Templates** → **Templates** → `debian-12-standard`
  → **Download**.
- Your domain is on Cloudflare (orange-cloud DNS) and you can open **Zero Trust** (one.dash.cloudflare.com).
- Tailscale: in the admin console → **DNS**, **MagicDNS** and **HTTPS Certificates** are enabled (they already are for the hub).

## 2. Create the LXC (on the Proxmox host)

### 2a. In the UI
**Create CT**:
- **General**: hostname `docs`, **Unprivileged container** ticked, **Nesting** ticked, set a root password or SSH key.
- **Template**: `debian-12-standard`.
- **Disks**: 16 GB.
- **CPU**: 2 cores. **Memory**: 2048 MB, swap 512 MB.
- **Network**: bridge `vmbr0`, IPv4 **Static** `<lxc-ip>/24`, gateway `<gw-ip>`. Tick **Firewall** (used in step 8).
- **DNS**: use host settings (or `<gw-ip>`).
- Do **not** start it yet.

### 2b. Or on the command line
```bash
pct create <lxc-id> local:vztmpl/debian-12-standard_12.*_amd64.tar.zst \
  --hostname docs --unprivileged 1 --features nesting=1,keyctl=1 \
  --cores 2 --memory 2048 --swap 512 --rootfs local-lvm:16 \
  --net0 name=eth0,bridge=vmbr0,ip=<lxc-ip>/24,gw=<gw-ip>,firewall=1 \
  --onboot 1 --ostype debian
```

### 2c. Features and the TUN device (needed by Docker and Tailscale)
Edit `/etc/pve/lxc/<lxc-id>.conf` on the Proxmox host and make sure it contains:

```
features: keyctl=1,nesting=1
lxc.cgroup2.devices.allow: c 10:200 rwm
lxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file
```

(`keyctl=1` can also be set in the UI: container → **Options** → **Features**.) Then start it: `pct start <lxc-id>`, and open a
shell with `pct enter <lxc-id>` (or SSH).

## 3. Docker inside the LXC

```bash
apt update && apt full-upgrade -y
apt install -y ca-certificates curl gnupg git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian bookworm stable" \
  > /etc/apt/sources.list.d/docker.list
apt update && apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
docker run --rm hello-world
docker info | grep -E "Storage Driver|Cgroup"
```

`Storage Driver` should be `overlay2` (or `overlayfs`). If it says `vfs`, Docker works but every image layer is a full copy: on ZFS
older than 2.2, give the LXC an ext4 mount point for `/var/lib/docker` or install `fuse-overlayfs`.

## 4. Tailscale inside the LXC (remote editing)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up --hostname=docs          # opens a login link; approve it in the admin console
tailscale serve --bg http://127.0.0.1:3020
tailscale serve status                # shows https://docs.<tailnet>.ts.net -> http://127.0.0.1:3020
```

The first visit to `https://docs.<tailnet>.ts.net` takes a few seconds while the certificate is issued. If `tailscale up` complains
about `/dev/net/tun`, re-check the two `lxc.` lines from step 2c and restart the container.

## 5. Get the code and create `deploy/docs/.env`

```bash
git clone https://github.com/TabTQ/homelab-hub-and-docs.git homelab-hub
git clone https://github.com/TabTQ/homelab-hub-and-docs.git
cd homelab-hub
mkdir -p /opt/homelab-docs/data/internal
chown -R 10003:10003 /opt/homelab-docs/data/internal         # docs-internal runs as uid 10003
cp deploy/docs/.env.example deploy/docs/.env
chmod 600 deploy/docs/.env
```

Build the images once (the first build downloads Node, Maven and the dependencies; expect several minutes):

```bash
docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env build
```

> compose checks the required variables even for `build`. If it refuses because `DOCS_PASSWORD_HASH` or `TUNNEL_TOKEN` is empty,
> put a temporary `x` in both, build, and replace them below.

Make the password hash (it can be the same password as the hub; the two stay separate):

```bash
docker run --rm -i homelab-docs-internal:local hash-password
# type the password, press Enter; it prints a hash like $2a$12$...
```

Edit `deploy/docs/.env`:

```bash
DOCS_DATA=/opt/homelab-docs/data
DOCS_PASSWORD_HASH=$$2a$$12$$...            # the hash with EVERY $ doubled
DOCS_ALLOWED_HOSTS=docs.<tailnet>.ts.net
DOCS_SECURE_COOKIES=false                   # see step 9 before switching to true
DOCS_HUB_URL=https://<pi>.<tailnet>.ts.net  # the "← Hub" link
DOCS_DOMAINS=domain.com                     # public pages must end with .domain.com
TUNNEL_TOKEN=                               # step 6
```

## 6. Cloudflare: the separate "docs" tunnel

This tunnel is new and separate from the Pi's tunnel. Leave the Pi's tunnel and the portfolio's DNS record exactly as they are.

1. **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel** → **Cloudflared** → name it `docs` → **Save tunnel**.
2. On the "Install and run connectors" screen, pick **Docker**. The command shown ends with `--token eyJ...`. Copy **only the token**
   (the long `eyJ...` string) into `TUNNEL_TOKEN=` in `deploy/docs/.env`. Do not run the command shown; compose runs `cloudflared`.
   Click **Next**.
3. **Public hostname** (the "Route traffic" step, or later: the tunnel → **Public Hostname** → **Add a public hostname**):
   - **Subdomain**: `*`
   - **Domain**: `domain.com`
   - **Path**: leave empty
   - **Service**: Type `HTTP`, URL `docs-public:3011`
   - **Additional application settings** → **HTTP Settings** → **HTTP Host Header**: leave **empty**. (If it is set, every page looks
     like the same page: docs-public picks the page by the visitor's Host header.)
   - Save.
4. **DNS** (Cloudflare dashboard → your domain → **DNS** → **Records**). Check whether the wizard created a `*` record. If not, add it:
   - Type `CNAME`, Name `*`, Target `<tunnel-id>.cfargotunnel.com` (the tunnel ID is on the tunnel's overview page), **Proxied** (orange).

   Alternative from any machine with `cloudflared` logged in: `cloudflared tunnel route dns docs "*.domain.com"`.
5. Explicit records always win over the wildcard, so the portfolio's own record keeps pointing at the Pi's tunnel. Any **new**
   subdomain you create later without its own record will land on `docs-public` and get a plain 404: give it its own record.
6. Cloudflare's free Universal SSL covers `*.domain.com` (one level). A page at `a.b.domain.com` would need an advanced certificate,
   so keep page addresses one level deep.

If the dashboard refuses `*` as a subdomain on your account, add one public hostname per page instead (`page1` → `docs-public:3011`,
and so on). No code change is needed; only the publishing step gains "add the hostname in Cloudflare".

## 7. Start it

```bash
cd /opt/homelab-hub
docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env up -d --build
docker compose -f deploy/docs/docker-compose.yml ps        # all four "running", the three apps "healthy" after ~1 min
docker logs docs-cloudflared --tail 20                     # "Registered tunnel connection" (usually 4 times)
curl -s http://127.0.0.1:3020/actuator/health              # {"status":"UP"}
ss -ltn | grep -E ':3020|:3011'                            # only 3020: docs-public and hub-public must NOT listen on the host
```

Open `http://<lxc-ip>:3020` on the LAN and `https://docs.<tailnet>.ts.net` over Tailscale, and sign in. In the tunnel's page in
Zero Trust the status should be **Healthy**.

## 8. Proxmox firewall for the LXC

Goal: the LXC can reach the internet (Cloudflare, Tailscale, image and package downloads) but **not** the Pi or the Proxmox management
ports, and only the LAN can reach port 3020.

1. **Datacenter** → **Firewall** → **Options**: Firewall `Yes` (if it was off, first add a rule allowing 8006 and 22 from `<lan>` at the
   datacenter level so you do not lock yourself out).
2. Container `docs` → **Firewall** → **Options**: Firewall `Yes`, **Input Policy** `DROP`, **Output Policy** `ACCEPT` (the drops below
   do the limiting). Make sure the network device has **Firewall** ticked (Network → net0).
3. Container `docs` → **Firewall** → **Add** these rules (top to bottom; first match wins):

| # | Direction | Action | Source | Destination | Proto | Dest. port | Comment |
|---|---|---|---|---|---|---|---|
| 1 | in | ACCEPT | `<lan>` | | tcp | 3020 | Docs editor from the LAN |
| 2 | in | ACCEPT | `<lan>` | | tcp | 22 | SSH (only if you want it) |
| 3 | out | ACCEPT | | `<gw-ip>` | udp | 53 | DNS |
| 4 | out | ACCEPT | | `<gw-ip>` | tcp | 53 | DNS |
| 5 | out | DROP | | `<pi-ip>` | | | never reach the Pi |
| 6 | out | DROP | | `<pve-ip>` | tcp | 8006,22 | never reach Proxmox management |
| 7 | out | DROP | | `<lan>` | | | the rest of the LAN (recommended) |

   Everything else outbound is allowed by the output policy (cloudflared uses 7844 udp/tcp and 443; Tailscale uses 41641 udp and
   443). Tailscale traffic to `tailscale serve` arrives on the `tailscale0` interface inside the LXC and is not affected by these rules.
4. Check from inside the LXC:
   ```bash
   curl -m 3 http://<pi-ip>:3010         # must time out
   curl -m 3 -k https://<pve-ip>:8006    # must time out
   curl -sI https://www.cloudflare.com | head -1   # must work
   ```
   And from another LAN machine: `curl http://<lxc-ip>:3020/actuator/health` works.

## 9. Secure cookies

Once you only ever open Docs through `https://docs.<tailnet>.ts.net`, set `DOCS_SECURE_COOKIES=true` and restart
(`docker compose ... up -d`). Over plain `http://<lxc-ip>:3020` login then fails (the browser refuses a Secure cookie over http), so
leave it `false` if you still use the LAN address.

## 10. The Docs button on the hub (the Pi)

1. Open the hub → **Config** → add at the top level of `dashboard.yaml`:
   ```yaml
   docs:
     url: https://docs.<tailnet>.ts.net
   ```
   Save. The **Docs** link appears in the top bar (and "Open Docs" in Ctrl+K). No restart, no deploy of anything else is needed for
   this, **but** the hub on the Pi must run the new code (it has to understand the `docs:` key): redeploy the hub from this branch
   the usual way (`scripts/build-all.sh`, then `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build` on
   the Pi). Before that, the Config page reports `docs` as an unknown key and ignores it.
2. In Docs, the **← Hub** link goes to `DOCS_HUB_URL`.

## 11. Publish the first page and check it from outside

1. In Docs: **＋** → title "Test" → write a line → **Save draft**.
2. **Publish…** → turn **Public** on → address `t1` + `.domain.com` → the check says it is free → **Publish**.
3. On a phone **on mobile data** (not Wi-Fi/Tailscale): open `https://t1.domain.com` → the page shows, with no tree or other pages.
4. `https://random.domain.com` → plain 404.
5. `curl -sI https://t1.domain.com | grep -i x-robots-tag` → `noindex, nofollow`.
6. Your portfolio still opens on its own hostname (before section 17 it is served by the Pi's tunnel; after it, by this LXC).
7. **Unpublish** in the dialog → `https://t1.domain.com` gives 404 within seconds.

## 12. Backups

- `docs-internal` writes a nightly copy of the database to `/opt/homelab-docs/data/internal/backups` (14 kept).
- Images, attachments and app files live in `/opt/homelab-docs/data/internal/blobs`, which the database copy does **not** contain.
  Back up the whole LXC: Proxmox → **Datacenter** → **Backup** → **Add**: select the `docs` container, mode **Snapshot**, a schedule
  (e.g. daily 03:00), your backup storage, retention as you like.
- To restore only Docs data: stop the stack, put back `docs.db` (and `blobs/` from the LXC backup), start the stack. The public site
  is rebuilt from the database at startup.

## 13. Updating

```bash
cd /opt/homelab-hub
git pull
docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env up -d --build
docker image prune -f        # removes the old images
```

Database migrations run automatically at startup. To roll back, `git checkout <previous-commit>` and run the same `up -d --build`
(a newer database schema is not downgraded: restore the backup taken before the update if a migration was involved).

To update `cloudflared`, change its pinned version in `deploy/docs/docker-compose.yml` and run `up -d`.

## 14. Optional: show the Docs containers on the hub's Docker page

Add the LXC to the hub's `dockerHosts:` (README, "Docker setup") to see and restart the Docs containers from the hub. This creates an
SSH path from the Pi into the LXC (the Pi → LXC direction only), and the `hubdocker` user is effectively root on the LXC. It also
needs the firewall rule `in ACCEPT <pi-ip> tcp 22`. It is a trade-off against the isolation the separate LXC gives you; skip it if
you do not need it.

## 15. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Every public hostname shows the same page | **HTTP Host Header** set in the tunnel's public hostname | Clear it (step 6.3) |
| `https://t1.domain.com` → Cloudflare error 1033 / 530 | Tunnel not connected | `docker logs docs-cloudflared`; check `TUNNEL_TOKEN`; outbound 7844/443 allowed |
| `https://t1.domain.com` → 502 | cloudflared cannot reach `docs-public` | Service must be `http://docs-public:3011` (not localhost); `docker compose ps` shows docs-public healthy |
| DNS_PROBE / NXDOMAIN for a page | No `*` record (or not proxied) | Add the wildcard CNAME (step 6.4) |
| Valid page but 404 | Not published, or the address differs | Check the page's address in Docs; the publish status in the Docs header |
| "Public site out of date" in the header | The snapshot could not be written | `docker logs docs-internal`; the named volume `docs-snapshot` must be writable by uid 10003 |
| Login: "Invalid Host header … DOCS_ALLOWED_HOSTS" | Opened by a name not listed | Add the name to `DOCS_ALLOWED_HOSTS`, restart |
| Login loops back to the sign-in form | `DOCS_SECURE_COOKIES=true` over plain http | Use the https address or set it to `false` |
| Login: "No password is set" | Empty `DOCS_PASSWORD_HASH` | Step 5; remember to double every `$` |
| `tailscale up`: cannot open /dev/net/tun | TUN lines missing | Step 2c, then restart the LXC |
| `docker run` fails with permission / apparmor / keyring errors | `nesting` or `keyctl` off | Step 2c |
| Images very slow to build, disk fills | Docker uses `vfs` | Step 3 note |
| A webapp's height is wrong in Auto mode | The app never reports it | Include `_hub/hub-embed.js` or post `{type:"hub:height", px}` |
| A webapp cannot load a library from a CDN | CDN not ticked | Webapp page → **Network access** |
| Uploads fail with "larger than 20 MB" | Asset limit | Raise `DOCS_MAX_ASSET_MB` / `DOCS_MAX_BUNDLE_MB`, restart |

Logs: `docker logs docs-internal`, `docker logs docs-public`, `docker logs docs-cloudflared`.

## 16. Final checklist

- [ ] `ss -ltn` on the LXC shows 3020 (and 22 if wanted), **not** 3011 (neither `docs-public` nor `hub-public` publishes a port).
- [ ] From the LXC, the Pi (`<pi-ip>:3010`) and Proxmox (`<pve-ip>:8006`) time out.
- [ ] `https://docs.<tailnet>.ts.net` works from the phone; the LAN address works on the LAN.
- [ ] A test page at `https://t1.domain.com` opens over mobile data; `https://random.domain.com` is 404.
- [ ] The portfolio still works on its own hostname (after section 17: served from this LXC, and `ss -ltn` on the Pi shows no 3011).
- [ ] Section 17's independence check: `docker compose ... stop docs-internal docs-public` leaves the portfolio online.
- [ ] The hub's **Docs** button opens Docs; **← Hub** comes back.
- [ ] A Proxmox backup job includes the `docs` LXC.
- [ ] Optional: note the memory of both JVMs (`docker stats --no-stream`) idle and while editing.

## 17. Move the portfolio onto this LXC (cutover runbook)

The portfolio used to be edited on the Pi's hub and served by the Pi's `hub-public` through the Pi's tunnel. It now lives here:
**edited in Docs → Portfolio**, **served by the `hub-public` container on this LXC** through the docs tunnel. The Pi stops serving it.
The Pi and the LXC never call each other: the one-time data copy below goes through your laptop.

**Independence.** The Pi and this LXC each run on their own.

| Down | Pi (hub) | Portfolio | Docs |
|---|---|---|---|
| `docs-internal` stopped | unaffected | **still served** (last snapshot) | editor down; public Docs pages still served |
| `docs-internal` + `docs-public` stopped | unaffected | **still served** | public Docs pages give 502 |
| the whole LXC | unaffected | **offline** (it lives here now) | offline |
| the Pi | n/a | unaffected | unaffected (**← Hub** just fails) |

To work on the LXC and keep the portfolio online: `docker compose -f deploy/docs/docker-compose.yml stop docs-internal docs-public`, and later
`... start docs-internal docs-public`. Stopping the **whole** LXC (or `docker compose ... down`) takes the portfolio offline.

### Before you start
- This branch is merged and the new code is on the LXC (`git pull`).
- You know the portfolio's public hostname, here `<portfolio-host>` (for example `portfolio.domain.com`), and set it in `deploy/docs/.env`:
  `DOCS_PORTFOLIO_URL=https://<portfolio-host>`.

### 1. Freeze and copy the data
1. Stop editing the portfolio on the hub. On the hub, **Config → Backup → Back up now** (or take the newest `data/hub/backups/*.db`).
2. Copy that file from the Pi to the LXC **via your laptop** (the LXC firewall blocks the Pi):
   ```bash
   scp pi:/…/data/hub/backups/hub-<date>.db ./hub-backup.db
   scp ./hub-backup.db root@<lxc-ip>:/opt/homelab-docs/data/internal/import/hub-backup.db
   ```
   (create the `import` folder first: `mkdir -p /opt/homelab-docs/data/internal/import && chown 10003 …/import`.)

### 2. Start the new stack and copy
```bash
cd /opt/homelab-hub && git pull
docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env up -d --build
docker compose -f deploy/docs/docker-compose.yml ps        # docs-internal, docs-public, hub-public, cloudflared
# stop the editor while the data is copied (hub-public and the tunnel keep running)
docker compose -f deploy/docs/docker-compose.yml stop docs-internal
docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env run --rm docs-internal \
  migrate-portfolio --from /data/import/hub-backup.db --dry-run     # prints the row counts, saves nothing
docker compose -f deploy/docs/docker-compose.yml --env-file deploy/docs/.env run --rm docs-internal \
  migrate-portfolio --from /data/import/hub-backup.db               # the real copy; every table's count is checked
docker compose -f deploy/docs/docker-compose.yml start docs-internal   # publishes the snapshot at startup
```
The command opens the backup read-only, copies every portfolio table in one transaction (ids and versions kept), and refuses to run on a
Docs portfolio that already has rows unless you add `--replace`. Any mismatch rolls everything back. You can run it again safely.

### 3. Check before switching anything
- Docs → **Portfolio**: all roles, projects, skills and homelab entries are there.
- From the LXC: `docker compose -f deploy/docs/docker-compose.yml exec hub-public curl -s http://127.0.0.1:3011/api/public/portfolio | head -c 300`
  returns JSON (`hub-public` publishes no port, so you run curl inside it; its own health check uses the same tool).
- `docker compose -f deploy/docs/docker-compose.yml ps` shows `hub-public` healthy.

### 4. Cloudflare (switch)
1. Zero Trust → Tunnels → the **Pi's tunnel** → remove the public hostname `<portfolio-host>`.
2. Tunnels → the **docs** tunnel → add the public hostname `<portfolio-host>` → service `HTTP` `hub-public:3011`. Leave "HTTP Host Header" empty.
   An explicit hostname wins over the `*.domain.com` wildcard.
3. DNS: the `<portfolio-host>` CNAME now points to `<docs-tunnel-id>.cfargotunnel.com` (proxied). Cloudflare updates it when you add the
   hostname; check it in DNS.

### 5. Check from outside
- On mobile data open `https://<portfolio-host>`: the portfolio shows.
- `https://random.domain.com` is still a plain 404 (from `docs-public`), and `https://t1.domain.com` (a published Docs page) still opens.
- Edit something small in Docs → Portfolio; the public page shows it within a few seconds.

### 6. The Pi
1. Deploy the new hub on the Pi (it has no portfolio, no `hub-public`). Add the portfolio link to `dashboard.yaml`:
   ```yaml
   docs:
     url: https://docs.<tailnet>.ts.net
     portfolioUrl: https://<portfolio-host>
   ```
2. Remove the old container and image if they are left over: `docker rm -f hub-public`.
3. If the Pi's tunnel serves nothing else, stop it: `sudo systemctl disable --now cloudflared` (or remove its container). Otherwise leave it running.
4. On the Pi, `ss -ltn | grep 3011` shows nothing. The dashboard's **Portfolio** card now opens Docs.

### 7. Independence check
```bash
docker compose -f deploy/docs/docker-compose.yml stop docs-internal docs-public
# the portfolio still opens on https://<portfolio-host>; the Pi's dashboard is unaffected; the Docs link just shows an error page
docker compose -f deploy/docs/docker-compose.yml start docs-internal docs-public
```

### Rollback
`hub.db` on the Pi still holds the portfolio exactly as it was (nothing was deleted). Redeploy the previous hub release (with `hub-public`),
and point `<portfolio-host>` back at the Pi's tunnel (reverse step 4). Edits made in Docs after the cutover would have to be redone.

### Things to know
- Backups: the portfolio now lives in `docs.db` (nightly `VACUUM INTO` plus the LXC backup). The hub's backups no longer contain current portfolio data.
- The LXC runs one more JVM (`hub-public`, `-Xmx160m`): check `docker stats --no-stream`; 2 GB is enough on paper.
- Old links: the hub's `#/portfolio` and an installed app's old "Portfolio" shortcut now land on the dashboard, whose card links to Docs.
- To drop the unused portfolio tables from `hub.db` later, do it as a separate step once you are happy (the schema only moves forward).
