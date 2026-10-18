#!/usr/bin/env bash
# Starts the hub stack on this machine for end-to-end testing (the portfolio and Docs are scripts/run-local-docs.sh; the two are independent). Nothing here touches the Pi or the real Proxmox:
# a mock Proxmox answers, Wake-on-LAN goes to a local UDP port, and the data lives in .scratch/e2e-data.
#   scripts/run-local.sh            fresh data, e2e dashboard
#   CONFIG=real scripts/run-local.sh   use the converted real dashboard (widgets will report missing secrets)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
D="$ROOT/.scratch/e2e-data"
"$ROOT/scripts/stop-local.sh" >/dev/null 2>&1 || true
rm -rf "$D" && mkdir -p "$D/config" "$D/images"
cp -r "$ROOT/.scratch/local-data/images/." "$D/images/" 2>/dev/null || true
INT=$(ls "$ROOT"/hub-internal/target/hub-internal-*.jar | grep -v original | head -1)

if [ "${CONFIG:-e2e}" = real ]; then cp "$ROOT/.scratch/real/dashboard.yaml" "$D/config/dashboard.yaml"; else cp "$ROOT/web/e2e/dashboard.e2e.yaml" "$D/config/dashboard.yaml"; fi
printf 'MOCK_PVE=mock-secret\n' > "$D/secrets.env" && chmod 600 "$D/secrets.env"

HASH=$(printf 'local-test-pass\n' | java -jar "$INT" hash-password)
python3 "$ROOT/tools/mock-proxmox/mock_proxmox.py" 8907 >"$D/mock.log" 2>&1 & echo $! >> "$D/pids"
python3 "$ROOT/tools/mock-proxmox/wol_listener.py" 9099 "$D/wol.log" >"$D/wol-listener.log" 2>&1 & echo $! >> "$D/pids"

# Terminal and Docker pages: a throwaway SSH server on 127.0.0.1:2222 with keys generated per run (tools/mock-ssh), and a
# fake docker CLI behind it (tools/mock-docker). Its host key is pinned into the e2e dashboard.yaml. Without paramiko
# both pages stay off and their browser tests skip.
TERMINAL=false
python3 "$ROOT/tools/mock-ssh/mock_ssh.py" 2222 "$D" >"$D/mock-ssh.log" 2>&1 & echo $! >> "$D/pids"
for i in $(seq 1 40); do [ -f "$D/ssh-mock/fingerprint" ] && break; sleep 0.25; done
if [ -f "$D/ssh-mock/fingerprint" ]; then
  TERMINAL=true
  cat >> "$D/config/dashboard.yaml" <<YAML

terminals:
  - id: pi
    label: Mock Pi
    host: 127.0.0.1
    port: 2222
    user: hubshell
    key: hub_terminal_ed25519
    hostKey: "$(cat "$D/ssh-mock/fingerprint")"

dockerHosts:
  - id: pi
    label: Mock Pi
    host: 127.0.0.1
    port: 2222
    user: hubdocker
    key: hub_docker_ed25519
    hostKey: "$(cat "$D/ssh-mock/fingerprint")"
    protect: [pihole]
  - id: media
    label: Media LXC (unreachable)
    host: 127.0.0.1
    port: 2299
    user: hubdocker
    key: hub_docker_ed25519
    hostKey: "$(cat "$D/ssh-mock/fingerprint")"
YAML
else
  echo "==> mock SSH server not available ($(tail -1 "$D/mock-ssh.log" 2>/dev/null)): the Terminal and Docker pages stay off"
fi

HUB_DATA_DIR="$D" HUB_PORT=3910 HUB_PASSWORD_HASH="$HASH" \
PROXMOX_API_URL=http://127.0.0.1:8907/api2/json PROXMOX_API_TOKEN='hub@pve!local=mock' PROXMOX_TLS_MODE=system \
HUB_WOL_TARGET=127.0.0.1 HUB_WOL_PORT=9099 HUB_WOL_DELAY_MILLIS=0 HUB_ACTIONS_TASK_POLL_MILLIS=300 \
HUB_TERMINAL_ENABLED=$TERMINAL HUB_TERMINAL_IDLE_SECONDS=12 HUB_TERMINAL_WARN_SECONDS=5 HUB_DOCKER_ENABLED=$TERMINAL \
  java -Xmx256m -jar "$INT" >"$D/internal.log" 2>&1 & echo $! >> "$D/pids"

for i in $(seq 1 90); do
  curl -s -m 1 -o /dev/null http://127.0.0.1:3910/actuator/health && break
  sleep 0.5
done
echo "internal: $(curl -s -m 3 http://127.0.0.1:3910/actuator/health)"
echo "password for the dashboard: local-test-pass   (data: $D)"
