#!/usr/bin/env bash
# Builds the web bundles, puts them into the Spring Boot apps (hub and Docs), and packages the jars.
# Local use (no Docker needed): scripts/build-all.sh   [--skip-tests]
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.11/bin:$PATH"

echo "==> web: install, test, build"
(cd web && { [ -d node_modules ] || npm ci; } && npm test && npm run build)

echo "==> copying bundles into the apps"
for m in hub-internal hub-public docs-internal docs-public; do rm -rf "$m/src/main/resources/static" && mkdir -p "$m/src/main/resources/static"; done
cp -r web/dist/internal/.      hub-internal/src/main/resources/static/
cp -r web/dist/public/.        hub-public/src/main/resources/static/
cp -r web/dist/docs-internal/. docs-internal/src/main/resources/static/
cp -r web/dist/docs-public/.   docs-public/src/main/resources/static/

echo "==> java: package"
if [ "${1:-}" = "--skip-tests" ]; then mvn -B -q -DskipTests package; else mvn -B -q verify; fi
ls -la hub-internal/target/hub-internal-*.jar hub-public/target/hub-public-*.jar docs-internal/target/docs-internal-*.jar docs-public/target/docs-public-*.jar | grep -v original | awk '{print $5, $9}'
