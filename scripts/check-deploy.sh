#!/usr/bin/env bash
# Static checks of the deployment files (no Docker daemon needed, only the docker CLI's `compose config`).
#  1. The Pi compose is valid with ONLY the Pi variables, has no hub-public / snapshot, and nothing public.
#  2. The Docs LXC compose is valid with only the Docs variables, and no service depends_on another
#     (each one starts, restarts and stays up on its own; the portfolio and Docs pages stay online when docs-internal is stopped).
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. Pi compose, evaluated with a clean environment containing just the Pi variables.
pi=$(env -i PATH="$PATH" HOME="$HOME" HUB_PASSWORD_HASH=x docker compose -f deploy/docker-compose.yml config)
echo "$pi" | grep -qE '^  hub-public:' && fail "the Pi compose still defines hub-public"
echo "$pi" | grep -qi 'snapshot' && fail "the Pi compose still mentions a snapshot"
echo "$pi" | grep -qE '^    ports:' && fail "the Pi compose publishes ports"
echo "$pi" | grep -qE '^  hub-internal:' || fail "the Pi compose lost hub-internal"
grep -qE 'HUB_PUBLIC_PORT|HUB_SNAPSHOT_DIR' deploy/docker-compose.yml deploy/.env.example && fail "the Pi deploy files still mention HUB_PUBLIC_PORT/HUB_SNAPSHOT_DIR"

# 2. Docs compose with only the Docs variables.
docs=$(env -i PATH="$PATH" HOME="$HOME" DOCS_PASSWORD_HASH=x TUNNEL_TOKEN=x docker compose -f deploy/docs/docker-compose.yml config)
if echo "$docs" | grep -qE '^    depends_on:'; then fail "a Docs service has depends_on; no service may depend on another (independence)"; fi
for svc in docs-internal docs-public hub-public cloudflared; do
  echo "$docs" | grep -qE "^  $svc:" || fail "the Docs compose lost $svc"
done
echo "$docs" | awk '/^  hub-public:/{f=1;next} /^  [a-z]/{f=0} f' | grep -qE '^    ports:' && fail "hub-public publishes a host port"
echo "$docs" | awk '/^  docs-public:/{f=1;next} /^  [a-z]/{f=0} f' | grep -qE '^    ports:' && fail "docs-public publishes a host port"
echo "OK: deployment files"
