#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE="$HERE/compose/compose.yaml"
STATE="$HERE/compose/state"
LEDGER_PORT=${LEDGER_PORT:-41000}
export LEDGER_PORT
PAUSE=${PAUSE:-5}
case "$PAUSE" in
  '' | *[!0-9]*) echo 'PAUSE must be a non-negative integer number of seconds.' >&2; exit 2 ;;
esac
compose() {
  if [ ! -f "$HERE/compose/.cips/8e36c20e5106014efb8348f749d9535afbc48921/cn-credentials-standard/demo/compose.yaml" ]; then
    printf 'Pinned Compose source missing; run sh %s/compose/prepare-cips.sh first.\n' "$HERE" >&2
    return 2
  fi
  docker compose --profile agents -f "$COMPOSE" "$@"
}
# One step, with the same prerequisite, marker and package gate for `step` and
# `all`. Keeping a single implementation stops the two paths from drifting.
run_step() {
  case "$1" in
    2) NUMBER=2; INPUT="$STATE/bootstrap.json"; SCRIPT=ruleVote ;;
    3) NUMBER=3; INPUT="$STATE/step-2.json"; SCRIPT=proposalVote ;;
    4) NUMBER=4; INPUT="$STATE/step-3.json"; SCRIPT=milestoneVote ;;
    5) NUMBER=5; INPUT="$STATE/step-4.json"; SCRIPT=couponAllocate ;;
    6) NUMBER=6; INPUT="$STATE/step-5.json"; SCRIPT=blockedPaths ;;
    *) echo 'Steps 2-6 are implemented; pass a step number in that range.' >&2; return 2 ;;
  esac
  echo "EXPERIMENTAL step $NUMBER: host submits as synthetic opener/voters, not agent user." >&2
  if [ ! -f "$INPUT" ] || [ -e "$STATE/step-$NUMBER.json" ] || [ -e "$STATE/step-$NUMBER.attempted" ]; then
    echo "Refused step $NUMBER: prerequisite $INPUT must exist; step-$NUMBER.json and step-$NUMBER.attempted must not exist. After an attempt, run down/up." >&2
    return 2
  fi
  python3 "$HERE/package-gate.py" "$STATE" "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar"
  : > "$STATE/step-$NUMBER.attempted"
  NEXT=$(mktemp "$STATE/step-$NUMBER-next-XXXXXXXX")
  trap 'rm -f -- "$NEXT"' EXIT HUP INT TERM
  dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
    --script-name "Cap.Examples.BabyCdf.Live:$SCRIPT" --input-file "$STATE/run-id.json" \
    --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
    --upload-dar true --output-file "$NEXT"
  mv "$NEXT" "$STATE/step-$NUMBER.json"
  trap - EXIT HUP INT TERM
}
case "${1:-help}" in
  up)
    : "${BABYCDF_BUILD_ROOT:?Set an approved external temporary directory}"
    case "$BABYCDF_BUILD_ROOT" in /*) ;; *) echo 'BABYCDF_BUILD_ROOT must be absolute' >&2; exit 2;; esac
    [ -d "$BABYCDF_BUILD_ROOT" ] || { echo 'BABYCDF_BUILD_ROOT must exist' >&2; exit 2; }
    BUILD=$(CDPATH= cd -- "$BABYCDF_BUILD_ROOT" && pwd -P)
    REPO=$(CDPATH= cd -- "$HERE/../../../.." && pwd -P)
    case "$BUILD/" in "$REPO/"*) echo 'BABYCDF_BUILD_ROOT must be outside Concordia' >&2; exit 2;; esac
    sh "$HERE/compose/prepare-cips.sh"
    if [ -n "$(compose ps -q credential-canton)" ]; then
      echo 'Already running: down first before a second up (dedicated user creation is not idempotent).' >&2
      exit 2
    fi
    rm -f -- "$HERE/compose/state/readback" "$HERE/compose/state/bootstrap.json" "$STATE/step-2.json" "$STATE/step-3.json" "$STATE/step-4.json" "$STATE/step-5.json" "$STATE/step-6.json" "$STATE/step-2.attempted" "$STATE/step-3.attempted" "$STATE/step-4.attempted" "$STATE/step-5.attempted" "$STATE/step-6.attempted" "$STATE/demo-dar.json" "$STATE/demo-inspect.json" "$STATE/run-id.json" "$STATE/bootstrap-input.json"
    # Interrupted steps can leave their atomic temp file behind; these are exact
    # fixed-prefix demo paths, never a recursive delete.
    rm -f -- "$STATE"/step-2-next-* "$STATE"/step-3-next-* "$STATE"/step-4-next-* "$STATE"/step-5-next-* "$STATE"/step-6-next-* "$STATE"/bootstrap-next-* "$STATE"/proposal-next-*
    python3 -B "$HERE/test_readback.py"
    sh "$HERE/compose/agents/test-entrypoint.sh"
    mkdir -p "$STATE"
    FUND_REPO=${BABYCDF_FUND_REPO:-"$REPO/../canton-dev-fund"}
    if ! git -C "$FUND_REPO" cat-file -e f591787a772848e4537aba5d8ba02be1764647da; then
      echo 'Pinned proposal blob missing; set BABYCDF_FUND_REPO before starting Canton.' >&2
      exit 2
    fi
    BLOB_NEXT=$(mktemp "$STATE/proposal-next-XXXXXXXX")
    git -C "$FUND_REPO" cat-file blob f591787a772848e4537aba5d8ba02be1764647da > "$BLOB_NEXT"
    chmod 644 "$BLOB_NEXT"
    mv "$BLOB_NEXT" "$STATE/proposal-blob.txt"
    sh "$HERE/../prepare-dependencies.sh"
    (cd "$BUILD" && DAML_PACKAGE="$HERE/../impl" dpm build --enable-multi-package=no)
    (cd "$BUILD" && DAML_PACKAGE="$HERE" dpm build --enable-multi-package=no)
    if ! compose up -d --build --wait --wait-timeout 180 credential-canton; then
      echo 'Readiness failed: inspect compose logs credential-canton, docker stats --no-stream and docker ps -a; run down before retry.' >&2
      exit 1
    fi
    mkdir -p "$STATE"
    NEXT=$(mktemp "$STATE/bootstrap-next-XXXXXXXX.json")
    printf '{"userName":"babycdf-agent","windowSeconds":60}\n' > "$STATE/bootstrap-input.json"
    printf '[INIT] network healthy; uploading DAR, allocating synthetic parties and verifying dedicated user rights\n'
    dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
      --script-name Cap.Examples.BabyCdf.Live:bootstrap --input-file "$STATE/bootstrap-input.json" \
      --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
      --upload-dar true --output-file "$NEXT"
    mv "$NEXT" "$STATE/bootstrap.json"
    dpm inspect-dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" --json > "$STATE/demo-inspect.json"
    python3 - "$STATE" "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" <<'PY'
import sys,json,hashlib
from pathlib import Path
s,d=map(Path,sys.argv[1:]); info=json.loads((s/'demo-inspect.json').read_text())
(s/'demo-dar.json').write_text(json.dumps(dict(main_package_id=info['main_package_id'],sha256=hashlib.sha256(d.read_bytes()).hexdigest())))
PY
    python3 - "$STATE" <<'PY'
import json,sys
from pathlib import Path
s=Path(sys.argv[1]); (s/'run-id.json').write_text(json.dumps(json.loads((s/'bootstrap-input.json').read_text())['userName']))
PY
    dpm inspect-dar "$HERE/../impl/.daml/dist/cap-example-babycdf-0.1.0.dar" --json > "$STATE/impl-package.json"
    python3 "$HERE/publish-readback.py" "$STATE" "$LEDGER_PORT" "$STATE/impl-package.json"
    printf '[INIT] dedicated user rights verified; readback published; starting read-only offline Advisory and Reviewer.\n'
    compose up -d --build --wait --wait-timeout 60 advisory reviewer
    ;;
  logs) compose logs -f --timestamps ;;
  down)
    compose down
    rm -f -- "$HERE/compose/state/readback" "$HERE/compose/state/bootstrap.json" "$STATE/step-2.json" "$STATE/step-3.json" "$STATE/step-4.json" "$STATE/step-5.json" "$STATE/step-6.json" "$STATE/step-2.attempted" "$STATE/step-3.attempted" "$STATE/step-4.attempted" "$STATE/step-5.attempted" "$STATE/step-6.attempted" "$STATE/demo-dar.json" "$STATE/demo-inspect.json" "$STATE/run-id.json" "$STATE/bootstrap-input.json"
    # Interrupted steps can leave their atomic temp file behind; these are exact
    # fixed-prefix demo paths, never a recursive delete.
    rm -f -- "$STATE"/step-2-next-* "$STATE"/step-3-next-* "$STATE"/step-4-next-* "$STATE"/step-5-next-* "$STATE"/step-6-next-* "$STATE"/bootstrap-next-* "$STATE"/proposal-next-*
    ;;
  step) run_step "${2:-}" ;;
  all)
    # A fresh network is required: bootstrap must exist and no later step may
    # have run or been attempted, so `all` can never resume a partial journey.
    if [ ! -f "$STATE/bootstrap.json" ]; then
      echo 'Refused all: no bootstrap.json; run up first.' >&2; exit 2
    fi
    for n in 2 3 4 5 6; do
      if [ -e "$STATE/step-$n.json" ] || [ -e "$STATE/step-$n.attempted" ]; then
        echo "Refused all: step $n already ran or was attempted on this network; run down then up for a fresh network." >&2
        exit 2
      fi
    done
    printf 'Running steps 2-6 sequentially with %s seconds between steps.\n' "$PAUSE" >&2
    for n in 2 3 4 5 6; do
      printf -- '--- all: starting step %s ---\n' "$n" >&2
      run_step "$n"
      if [ "$n" != 6 ]; then
        printf -- '--- all: step %s done; pausing %s seconds ---\n' "$n" "$PAUSE" >&2
        sleep "$PAUSE"
      else
        printf -- '--- all: step %s done; steps 2-6 complete ---\n' "$n" >&2
      fi
    done
    ;;
  help)
    printf 'Usage: sh %s {up|logs|down|step N|all|help}\n' "$0"
    printf '  up        build, start Canton, bootstrap and start the offline agents\n'
    printf '  logs      follow Canton and agent logs (terminal 1)\n'
    printf '  step N    run one step, N in 2..6 (terminal 2)\n'
    printf '  all       run steps 2-6 in order, PAUSE=%s seconds between them\n' "$PAUSE"
    printf '  down      stop everything and clear demo state\n'
    printf 'Steps: 2 rule vote, 3 proposal vote, 4 milestone, 5 coupon allocation, 6 blocked paths.\n'
    printf 'Each step runs once per network; after any attempt run down then up.\n'
    ;;
  *) echo 'Unknown command; use help.' >&2; exit 2 ;;
esac
