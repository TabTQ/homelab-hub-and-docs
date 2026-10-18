#!/usr/bin/env bash
# Runs the hub stack and the Docs stack locally, then the browser tests against them. Needs a prior scripts/build-all.sh.
#   scripts/e2e.sh          both
#   scripts/e2e.sh docs     only the Docs tests (no hub stack)
set -uo pipefail
cd "$(dirname "$0")/.."
CODE=0
if [ "${1:-}" != docs ]; then
  ./scripts/run-local.sh || { echo "hub stack failed to start"; ./scripts/stop-local.sh; exit 1; }
  (cd web && node --test --test-reporter=spec e2e/e2e.mjs) || CODE=$?
  ./scripts/stop-local.sh
fi
./scripts/run-local-docs.sh || { echo "docs stack failed to start"; ./scripts/stop-local-docs.sh; exit 1; }
(cd web && node --test --test-reporter=spec e2e/docs.e2e.mjs) || CODE=$?
./scripts/stop-local-docs.sh
exit $CODE
