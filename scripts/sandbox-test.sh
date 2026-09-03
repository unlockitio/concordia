#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEDGER_HOST="${LEDGER_HOST:-localhost}"
LEDGER_PORT="${LEDGER_PORT:-6865}"
FRESH_SANDBOX="${FRESH_SANDBOX:-}"
RUN_DIR="$(mktemp -d)"
SANDBOX_LOG="$RUN_DIR/sandbox.log"
SANDBOX_PID=""

DARS=(
  "$ROOT/examples/governance/private-majority-vote/test/.daml/dist/cap-example-majority-vote-test-0.1.0.dar"
  "$ROOT/examples/governance/baby-dso/cap/test/.daml/dist/cap-example-babydso-test-0.1.0.dar"
  "$ROOT/examples/auctions/sealed-bid-first-price/test/.daml/dist/cap-example-sealed-first-price-test-0.1.0.dar"
  "$ROOT/examples/auctions/sealed-bid-first-price-high-trust/test/.daml/dist/cap-example-sealed-first-price-high-trust-test-0.1.0.dar"
)

# "dpm sandbox" forks a java child, so killing the wrapper pid alone leaves the
# JVM holding the port. The sandbox is started in its own process group and the
# whole group is signalled here.
sandbox_alive() {
  [[ -n "$SANDBOX_PID" ]] && kill -0 -- -"$SANDBOX_PID" 2>/dev/null
}

# Only tears down a sandbox this script started; a reused one is left running.
cleanup() {
  if [[ -z "$SANDBOX_PID" ]]; then
    return
  fi
  kill -TERM -- -"$SANDBOX_PID" 2>/dev/null || true
  for _ in $(seq 1 15); do
    sandbox_alive || break
    sleep 1
  done
  if sandbox_alive; then
    echo "sandbox did not exit, forcing" >&2
    kill -9 -- -"$SANDBOX_PID" 2>/dev/null || true
    sleep 1
  fi
  wait "$SANDBOX_PID" 2>/dev/null || true
  if lsof -nP -iTCP:"$LEDGER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "warning: something still holds port $LEDGER_PORT after shutdown" >&2
  fi
}
trap cleanup EXIT

for dar in "${DARS[@]}"; do
  if [[ ! -f "$dar" ]]; then
    echo "missing $dar — run 'dpm build --all' first" >&2
    exit 1
  fi
done

ledger_is_local() {
  case "$LEDGER_HOST" in
    localhost|127.0.0.1|::1) return 0 ;;
    *) return 1 ;;
  esac
}

port_in_use() {
  lsof -nP -iTCP:"$LEDGER_PORT" -sTCP:LISTEN >/dev/null 2>&1
}

port_holder() {
  lsof -nP -iTCP:"$LEDGER_PORT" -sTCP:LISTEN -Fc 2>/dev/null | sed -n 's/^c//p' | head -1
}

start_sandbox() {
  echo "starting sandbox, log at $SANDBOX_LOG"
  set -m   # give the sandbox its own process group so cleanup can kill the tree
  dpm sandbox \
    -C canton.parameters.clock.type=sim-clock \
    -C canton.participants.sandbox.testing-time.type=monotonic-time \
    --no-tty </dev/null >"$SANDBOX_LOG" 2>&1 &
  SANDBOX_PID=$!
  set +m

  for _ in $(seq 1 120); do
    if grep -q "sandbox is ready" "$SANDBOX_LOG" 2>/dev/null; then
      return 0
    fi
    if ! sandbox_alive; then
      echo "sandbox exited during startup" >&2
      tail -40 "$SANDBOX_LOG" >&2
      exit 1
    fi
    sleep 2
  done

  echo "sandbox did not become ready" >&2
  tail -40 "$SANDBOX_LOG" >&2
  exit 1
}

if ! ledger_is_local; then
  echo "using external ledger at $LEDGER_HOST:$LEDGER_PORT"
elif port_in_use; then
  if [[ -n "$FRESH_SANDBOX" ]]; then
    echo "port $LEDGER_PORT is already in use and FRESH_SANDBOX is set —" >&2
    echo "stop the process holding it first" >&2
    exit 1
  fi
  holder="$(port_holder)"
  if [[ "$holder" != "java" ]]; then
    echo "port $LEDGER_PORT is held by '${holder:-unknown}', which is not a sandbox —" >&2
    echo "stop it first" >&2
    exit 1
  fi
  echo "reusing the sandbox already on $LEDGER_HOST:$LEDGER_PORT"
  echo "  it must have been started with sim-clock and monotonic-time, or --static-time will fail"
  echo "  ledger state carries over between runs; set FRESH_SANDBOX=1 to refuse reuse"
else
  start_sandbox
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
