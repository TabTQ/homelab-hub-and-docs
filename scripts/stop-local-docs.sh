#!/usr/bin/env bash
# Stops everything run-local-docs.sh started.
cd "$(dirname "$0")/.."
F=".scratch/docs-e2e/pids"
[ -f "$F" ] || exit 0
while read -r p; do kill "$p" 2>/dev/null || true; done < "$F"
sleep 1
while read -r p; do kill -9 "$p" 2>/dev/null || true; done < "$F"
rm -f "$F"
