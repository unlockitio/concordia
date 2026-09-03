#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEDGER_HOST="${LEDGER_HOST:-localhost}"
LEDGER_PORT="${LEDGER_PORT:-6865}"
RUN_DIR="$(mktemp -d)"
SANDBOX_LOG="$RUN_DIR/sandbox.log"
SANDBOX_PID=""

DARS=(
  "$ROOT/examples/governance/private-majority-vote/test/.daml/dist/cap-example-majority-vote-test-0.1.0.dar"
  "$ROOT/examples/governance/baby-dso/cap/test/.daml/dist/cap-example-babydso-test-0.1.0.dar"
  "$ROOT/examples/auctions/sealed-bid-first-price/test/.daml/dist/cap-example-sealed-first-price-test-0.1.0.dar"
  "$ROOT/examples/auctions/sealed-bid-first-price-high-trust/test/.daml/dist/cap-example-firstprice-test-0.1.0.dar"
)

cleanup() {
  if [[ -z "$SANDBOX_PID" ]]; then
    return
  fi
  kill "$SANDBOX_PID" 2>/dev/null || true
  for _ in $(seq 1 15); do
    kill -0 "$SANDBOX_PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$SANDBOX_PID" 2>/dev/null; then
    echo "sandbox did not exit, forcing" >&2
    kill -9 "$SANDBOX_PID" 2>/dev/null || true
  fi
  wait "$SANDBOX_PID" 2>/dev/null || true
}
trap cleanup EXIT

for dar in "${DARS[@]}"; do
  if [[ ! -f "$dar" ]]; then
    echo "missing $dar — run 'dpm build --all' first" >&2
    exit 1
  fi
done

if lsof -nP -iTCP:"$LEDGER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $LEDGER_PORT is already in use — stop the process holding it first" >&2
  exit 1
fi

echo "starting sandbox, log at $SANDBOX_LOG"
dpm sandbox \
  -C canton.parameters.clock.type=sim-clock \
  -C canton.participants.sandbox.testing-time.type=monotonic-time \
  --no-tty >"$SANDBOX_LOG" 2>&1 &
SANDBOX_PID=$!

for _ in $(seq 1 120); do
  if grep -q "sandbox is ready" "$SANDBOX_LOG" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$SANDBOX_PID" 2>/dev/null; then
    echo "sandbox exited during startup" >&2
    tail -40 "$SANDBOX_LOG" >&2
    exit 1
  fi
  sleep 2
done

if ! grep -q "sandbox is ready" "$SANDBOX_LOG" 2>/dev/null; then
  echo "sandbox did not become ready" >&2
  tail -40 "$SANDBOX_LOG" >&2
  exit 1
fi

echo "sandbox ready on $LEDGER_HOST:$LEDGER_PORT"

failed=0
for dar in "${DARS[@]}"; do
  name="$(basename "$dar")"
  echo "=== $name ==="
  if dpm script \
    --dar "$dar" \
    --all \
    --ledger-host "$LEDGER_HOST" \
    --ledger-port "$LEDGER_PORT" \
    --static-time \
    --upload-dar true
  then
    echo "=== $name ok ==="
  else
    echo "=== $name FAILED ===" >&2
    failed=1
  fi
done

exit "$failed"
