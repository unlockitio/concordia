#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PACKAGE=$(CDPATH= cd -- "$HERE/.." && pwd)
COMPOSE="$HERE/compose/compose.yaml"
PROJECT=babydro-live
export BABYDRO_LEDGER_PORT=${BABYDRO_LEDGER_PORT:-41100}
compose() { docker compose -p "$PROJECT" -f "$COMPOSE" "$@"; }
containers() { docker ps -aq --filter "label=com.docker.compose.project=$PROJECT"; }
usage() {
  echo 'Usage: sh demo/run-demo.sh up|step N|all|test|logs|status|down|help'
  echo 'Steps (same ledger; execute in order):'
  echo '  1 Genesis, founders and direct voting-right issuance'
  echo '  2 Credential readback and holder visibility'
  echo '  3 Membership vote, invitation, consent and admission'
  echo '  4 Name credentials, private ballots, tally, ruling and archive'
  echo '  5 post-resolution readback: public Ruling visible, ballots already consumed'
  echo '  6 Policy motion and enactment'
  echo 'all runs 1..6 sequentially; test is the separate regression suite.'
}
STEP=${2:-}
case "${1:-help}" in
  step) case "$STEP" in 1|2|3|4|5|6) ;; *) usage >&2; exit 2;; esac ;;
esac
case "${1:-help}" in
  help|-h|--help) usage; exit 0 ;;
  up|test|logs|status|down|step|all) ACTION=$1 ;;
  *) usage >&2; exit 2 ;;
esac
if [ "$ACTION" = step ]; then
  [ "$#" -eq 2 ] || { usage >&2; exit 2; }
else
  [ "$#" -le 1 ] || { usage >&2; exit 2; }
fi
case "$BABYDRO_LEDGER_PORT" in ''|*[!0-9]*) echo 'Invalid BABYDRO_LEDGER_PORT' >&2; exit 2;; esac
[ "$BABYDRO_LEDGER_PORT" -gt 1023 ] && [ "$BABYDRO_LEDGER_PORT" -le 65535 ] || exit 2
VERSION=$(docker compose version --short | tr -d v)
MAJOR=${VERSION%%.*}; REST=${VERSION#*.}; MINOR=${REST%%.*}; PATCH=${REST#*.}; PATCH=${PATCH%%-*}
if [ "$MAJOR" -lt 2 ] || { [ "$MAJOR" -eq 2 ] && { [ "$MINOR" -lt 24 ] || { [ "$MINOR" -eq 24 ] && [ "$PATCH" -lt 4 ]; }; }; }; then
  echo 'Docker Compose >=2.24.4 required for !override' >&2; exit 2
fi
case "$ACTION" in
  all) case "${BABYDRO_PAUSE:-2}" in ''|*[!0-9]*) echo 'Invalid BABYDRO_PAUSE: use a non-negative integer' >&2; exit 2;; esac ;;
  up)
    case "${BABYDRO_READY_TIMEOUT:-180}" in ''|*[!0-9]*|0) echo 'Invalid BABYDRO_READY_TIMEOUT: use positive integer seconds' >&2; exit 2;; esac
    export BABYDRO_READY_TIMEOUT=${BABYDRO_READY_TIMEOUT:-180}
    ;;
esac
STATE=${BABYDRO_STATE_DIR:-"$HERE/.state"}
mkdir -p "$STATE"
# Atomic lock covers every operation, including preparation and shutdown.
LOCK="$STATE/lifecycle.lock"
mkdir "$LOCK" 2>/dev/null || { echo "BabyDRO lifecycle operation already active ($LOCK)" >&2; exit 1; }
trap 'rmdir "$LOCK"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
STATE="$STATE/story"
mkdir -p "$STATE"
run_step() {
  n=$1
  [ -n "$(containers)" ] || { echo 'Start BabyDRO with up first' >&2; return 1; }
  [ ! -e "$STATE/step-$n.attempted" ] || { echo "Step $n already attempted; use down/up for a fresh story" >&2; return 1; }
  if [ "$n" -gt 1 ]; then
    previous=$((n - 1))
    [ -f "$STATE/step-$previous.json" ] || { echo "Missing prerequisite: run step $previous first" >&2; return 1; }
  fi
  DAR=${BABYDRO_DAR:-"$HERE/.daml/dist/cap-example-babydro-demo-0.1.0.dar"}
  [ -f "$DAR" ] || { echo 'Build missing; run up first' >&2; return 1; }
  echo "--- BabyDRO step $n | synthetic sandbox parties; host drives all actors ---"
  : > "$STATE/step-$n.attempted"
  if [ "$n" -eq 1 ]; then
    dpm script --dar "$DAR" --script-name "Cap.Examples.BabyDro.Steps:step$n" --ledger-host 127.0.0.1 --ledger-port "$BABYDRO_LEDGER_PORT" --wall-clock-time --upload-dar true --output-file "$STATE/step-$n.next"
  else
    dpm script --dar "$DAR" --script-name "Cap.Examples.BabyDro.Steps:step$n" --input-file "$STATE/step-$previous.json" --ledger-host 127.0.0.1 --ledger-port "$BABYDRO_LEDGER_PORT" --wall-clock-time --upload-dar true --output-file "$STATE/step-$n.next"
  fi
  mv "$STATE/step-$n.next" "$STATE/step-$n.json"
  echo "--- step $n complete; ledger references saved ---"
}
case "$ACTION" in
  step) run_step "$STEP" ;;
  all)
    for n in 1 2 3 4 5 6; do
      [ ! -e "$STATE/step-$n.attempted" ] || { echo 'all requires a fresh story; use down/up' >&2; exit 1; }
    done
    for n in 1 2 3 4 5 6; do
      run_step "$n"
      [ "$n" -eq 6 ] || sleep "${BABYDRO_PAUSE:-2}"
    done
    ;;
  up)
    [ -z "$(containers)" ] || { echo 'BabyDRO containers already exist; refusing concurrent/replacement startup' >&2; exit 1; }
    sh "$HERE/compose/prepare-cips.sh"
    sh "$PACKAGE/prepare-dependencies.sh"
    . "$PACKAGE/build-root.sh"
    babydro_build_root "$PACKAGE"
    MANIFEST=$(babydro_manifest_dir "$BABYDRO_BUILD_ROOT" "$PACKAGE/impl" "$HERE")
    (cd "$MANIFEST" && DAML_PACKAGE="$PACKAGE/impl" dpm build --enable-multi-package=yes --multi-package-path "$MANIFEST")
    (cd "$MANIFEST" && DAML_PACKAGE="$HERE" dpm build --enable-multi-package=yes --multi-package-path "$MANIFEST")
    compose config --quiet
    # Even failed readiness may leave a new ledger; never reuse old references.
    mv "$STATE" "$STATE-previous-$(date +%Y%m%d%H%M%S)-$$"
    mkdir -p "$STATE"
    compose up -d --build --wait --wait-timeout "${BABYDRO_READY_TIMEOUT:-180}" canton
    echo "BabyDRO ready on 127.0.0.1:$BABYDRO_LEDGER_PORT; run step 1 or all next."
    ;;
  test)
    [ -n "$(containers)" ] || { echo 'Start BabyDRO with up first' >&2; exit 1; }
    DAR=${BABYDRO_DAR:-"$HERE/.daml/dist/cap-example-babydro-demo-0.1.0.dar"}
    [ -f "$DAR" ] || { echo 'Build missing; run up first' >&2; exit 1; }
    # Explicit regression allowlist: Steps scripts never run as tests.
    for script in journey keepSecond checkedCaseAuthority currentElectorate failedArchive conflicted motionIsolation withdrawalAndRecast foreignBallotRejected rightRoutes reAdmissionRetiresOldRight expiredRightRejected stateMismatchRejected nWayDispute nWayRollback ballotContentPrivacy resolverCannotForge genericInterfaces; do
      dpm script --dar "$DAR" --script-name "Cap.Examples.BabyDro.Demo:$script" --ledger-host 127.0.0.1 --ledger-port "$BABYDRO_LEDGER_PORT" --wall-clock-time --upload-dar true
    done
    dpm script --dar "$DAR" --script-name Cap.Examples.BabyDro.PrivacyProof:resolverProjection --ledger-host 127.0.0.1 --ledger-port "$BABYDRO_LEDGER_PORT" --wall-clock-time --upload-dar true
    ;;
  logs) compose logs --no-color --tail 100 canton ;;
  status) compose ps ;;
  down)
    # Only this fixed project; never --volumes or --remove-orphans.
    compose down --timeout 30
    ;;
esac
