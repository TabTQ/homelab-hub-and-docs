#!/usr/bin/env bash
# Starts the Docs side on this machine for end-to-end testing: docs-internal on 3920 (Docs editor AND portfolio editor),
# docs-public on 3921 (Docs pages) and hub-public on 3911 (the public portfolio), in .scratch/docs-e2e. It does not need the hub stack. Nothing here touches Cloudflare or the Docs LXC. Needs a prior scripts/build-all.sh.
# docs-public and hub-public answer on host ports here only for the tests; in production they publish none (deploy/docs/docker-compose.yml).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"
D="$ROOT/.scratch/docs-e2e"
"$ROOT/scripts/stop-local-docs.sh" >/dev/null 2>&1 || true
rm -rf "$D" && mkdir -p "$D/data" "$D/snapshot" "$D/portfolio-snapshot" "$D/stage"
DI=$(ls "$ROOT"/docs-internal/target/docs-internal-*.jar | grep -v original | head -1)
DP=$(ls "$ROOT"/docs-public/target/docs-public-*.jar | grep -v original | head -1)
HP=$(ls "$ROOT"/hub-public/target/hub-public-*.jar | grep -v original | head -1)
HASH=$(printf 'local-test-pass\n' | java -jar "$DI" hash-password)

# The portfolio: import the real export into a staging database, then copy it into the Docs database with migrate-portfolio.
# That exercises both commands end to end (the same path as the production cutover, which copies a hub backup).
EXPORT="$ROOT/.scratch/export-out/portfolio-export.v1.json"
if [ -f "$EXPORT" ]; then
  echo "==> importing the portfolio, then migrating it into the Docs database"
  DOCS_DATA_DIR="$D/stage" java -jar "$DI" import --export "$EXPORT" \
    --decisions "$ROOT/tools/export-portfolio/skill-mapping-decisions.accepted.csv" --extras "$ROOT/tools/export-portfolio/import-extras.json" | tail -3
  DOCS_DATA_DIR="$D/data" java -jar "$DI" migrate-portfolio --from "$D/stage/docs.db" | tail -3
else
  echo "==> no portfolio export at $EXPORT: starting with an empty portfolio (the portfolio browser tests will fail)"
fi

# docs-internal is started through a small script so the tests can stop it and start it again (the independence test).
cat > "$D/start-internal.sh" <<EOS
#!/usr/bin/env bash
cd "$ROOT"
export JAVA_HOME="$JAVA_HOME" PATH="$PATH"
DOCS_DATA_DIR="$D/data" DOCS_SNAPSHOT_DIR="$D/snapshot" DOCS_PORTFOLIO_SNAPSHOT_DIR="$D/portfolio-snapshot" DOCS_PORTFOLIO_URL=http://127.0.0.1:3911/ \\
DOCS_PORT=3920 DOCS_PASSWORD_HASH='$HASH' DOCS_DOMAINS=example.test DOCS_HUB_URL=http://127.0.0.1:3910/ \\
  exec java -Xmx256m -jar "$DI" --docs.gc-delay-seconds=1 >>"$D/internal.log" 2>&1
EOS
chmod +x "$D/start-internal.sh"
"$D/start-internal.sh" & echo $! >> "$D/pids"
DOCS_SNAPSHOT_DIR="$D/snapshot" DOCS_PUBLIC_PORT=3921 java -Xmx128m -jar "$DP" >"$D/public.log" 2>&1 & echo $! >> "$D/pids"
HUB_PUBLIC_PORT=3911 HUB_SNAPSHOT_DIR="$D/portfolio-snapshot" java -Xmx192m -jar "$HP" >"$D/portfolio-public.log" 2>&1 & echo $! >> "$D/pids"

for i in $(seq 1 90); do
  curl -s -m 1 -o /dev/null http://127.0.0.1:3920/actuator/health && curl -s -m 1 -o /dev/null http://127.0.0.1:3921/api/public/health && curl -s -m 1 -o /dev/null http://127.0.0.1:3911/api/public/health && break
  sleep 0.5
done
echo "docs-internal: $(curl -s -m 3 http://127.0.0.1:3920/actuator/health)"
echo "docs-public:   $(curl -s -m 3 http://127.0.0.1:3921/api/public/health)"
echo "hub-public:    $(curl -s -m 3 http://127.0.0.1:3911/api/public/health)"
echo "password for Docs: local-test-pass   (data: $D)"
