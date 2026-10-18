#!/usr/bin/env bash
# Stops everything run-local.sh started.
cd "$(dirname "$0")/.."
F=".scratch/e2e-data/pids"
[ -f "$F" ] || exit 0
while read -r p; do kill "$p" 2>/dev/null || true; done < "$F"
sleep 1
while read -r p; do kill -9 "$p" 2>/dev/null || true; done < "$F"
rm -f "$F"
