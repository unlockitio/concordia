#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE="$HERE/compose/compose.yaml"
STATE="$HERE/compose/state"
LEDGER_PORT=${LEDGER_PORT:-41000}
export LEDGER_PORT
. "$HERE/../build-root.sh"
. "$HERE/up-stage.sh"
VERBOSE=${VERBOSE:-0}
PAUSE=${PAUSE:-5}
case "$PAUSE" in
  '' | *[!0-9]*) echo 'PAUSE must be a non-negative integer number of seconds.' >&2; exit 2 ;;
esac
compose() {
  if [ ! -f "$HERE/compose/.cips/8e36c20e5106014efb8348f749d9535afbc48921/cn-credentials-standard/demo/compose.yaml" ]; then
    printf 'Pinned Compose source missing; run sh %s/compose/prepare-cips.sh first.\n' "$HERE" >&2
    return 2
  fi
  # Prepend only explicitly selected overlays; preserve command arguments.
  if [ "${BABYCDF_LLM_ENABLED:-0}" = 1 ] && [ -n "${LLM_API_KEY_FILE:-}" ]; then
    case "$LLM_API_KEY_FILE" in
      /*) ;;
      *) echo 'LLM_API_KEY_FILE must be an absolute host path.' >&2; return 2 ;;
    esac
    set -- -f "$HERE/compose/llm-auth.yaml" "$@"
  fi
  if [ -n "${INTAKE_GITHUB_TOKEN_FILE:-}" ]; then
    case "$INTAKE_GITHUB_TOKEN_FILE" in
      /*) ;;
      *) echo 'INTAKE_GITHUB_TOKEN_FILE must be an absolute host path.' >&2; return 2 ;;
    esac
    set -- -f "$HERE/compose/intake-auth.yaml" -f "$HERE/compose/agents-github-auth.yaml" "$@"
  fi
  docker compose -p babycdf-live --profile agents -f "$COMPOSE" "$@"
}

# Presentation rendering of Daml Script output.
#
# The SDK wraps every `debug` payload in a source location and an escaped
# string literal, which is unreadable on a projector. format-log.py unwraps it
# and withholds the technical [ACTION]/[TIMING] lines unless asked for them.
#
# Three properties are deliberate and must not regress:
#
#   * The raw log is a PRECONDITION, not a side effect, and it is independent
#     of the formatter. The directory is created and the file is actually
#     opened for append BEFORE the mutating command is launched. If that
#     cannot be done the step is refused and the command is never invoked,
#     because running a ledger mutation whose output cannot be recorded is
#     worse than not running it.
#   * dpm's exit code is preserved. POSIX sh has no `pipefail`, so piping dpm
#     into anything would report the LAST stage's status and a failed step
#     would look successful. The status is carried out through a status file.
#   * The complete stream is always captured, whether or not the formatter is
#     available. Without python3 the raw log is written by `tee` and the
#     unformatted output still reaches the terminal.
#
# DEMO_VERBOSE=1 prints the technical lines on the terminal as well.
RAW_DIR=${BABYCDF_RAW_LOG_DIR:-}

# Creates the raw-log directory and proves the log file is writable by opening
# it for append. Echoes the path on success; returns non-zero after explaining
# the problem, with nothing executed.
raw_log_path() {
  _rl_label=$1
  if [ -z "${RAW_DIR:-}" ]; then
    printf 'Refused: no raw-log directory configured.\n' >&2
    return 2
  fi
  if [ ! -d "$RAW_DIR" ] && ! mkdir -p "$RAW_DIR" 2>/dev/null; then
    printf 'Refused: cannot create the raw-log directory %s.\n' "$RAW_DIR" >&2
    printf 'Every step must be fully recorded, so nothing was run.\n' >&2
    return 2
  fi
  _rl_file="$RAW_DIR/$_rl_label.log"
  # The open is the test. A readable/writable check can pass while the open
  # still fails (a directory in the way, a read-only mount, a full disk), so
  # the file is genuinely opened for append here, before anything is launched.
  if ! : >> "$_rl_file" 2>/dev/null; then
    printf 'Refused: cannot open the raw log %s for append.\n' "$_rl_file" >&2
    printf 'Every step must be fully recorded, so nothing was run.\n' >&2
    return 2
  fi
  printf '%s\n' "$_rl_file"
}

render_step() {
  _label=$1
  shift
  # PRECONDITION FIRST: no ledger-mutating command runs until the raw log is
  # open. `|| return` propagates the refusal with the command un-invoked.
  _raw=$(raw_log_path "$_label") || return 2
  _fmt="$HERE/format-log.py"
  _use_fmt=yes
  if [ ! -r "$_fmt" ] || ! command -v python3 >/dev/null 2>&1; then
    _use_fmt=no
    printf '[INIT] formatter unavailable; showing raw SDK output (still fully logged)\n' >&2
  fi
  _status=$(mktemp "${TMPDIR:-/tmp}/babycdf-status-XXXXXXXX") || {
    printf 'Refused: cannot create a status file; nothing was run.\n' >&2
    return 2
  }
  _args=''
  if [ "${DEMO_SETUP_FILTER:-0}" = 1 ] && [ "${VERBOSE:-0}" != 1 ]; then
    _args='--hide-sdk-info'
  fi
  if [ -n "${DEMO_VERBOSE:-}" ] && [ "${DEMO_VERBOSE}" != 0 ]; then
    _args="$_args --verbose"
  fi
  # 2>&1 so a real error is rendered in place rather than racing the stream.
  #
  # The status dance matters. POSIX sh has no `pipefail`, so the pipeline's own
  # status is the LAST stage's, and a failed step would look successful. The
  # command's status is therefore written to a file and re-raised afterwards.
  # `_inner=0; ... || _inner=$?` is used rather than a bare `$?` because this
  # script runs under `set -e`: without the `||` guard the subshell would abort
  # the instant dpm failed and the status would never be recorded, and with a
  # bare `printf "$?"` after a guard the status read would be the guard's.
  #
  # Both branches capture the complete stream. With the formatter, --raw does
  # it; without it, `tee` does, so the fallback is not a silent loss of record.
  if [ "$_use_fmt" = yes ]; then
    # shellcheck disable=SC2086  # _args is a script-controlled literal
    { { _inner=0; "$@" 2>&1 || _inner=$?; printf '%s\n' "$_inner" > "$_status"; } \
      | python3 "$_fmt" --raw "$_raw" $_args; }
  else
    { { _inner=0; "$@" 2>&1 || _inner=$?; printf '%s\n' "$_inner" > "$_status"; } \
      | tee -a "$_raw"; }
  fi
  _code=$(cat "$_status" 2>/dev/null || echo 1)
  # Cleanup must not mask the command's status: rm's own result is discarded.
  rm -f "$_status" 2>/dev/null || :
  case "$_code" in
    '' | *[!0-9]*) _code=1 ;;
  esac
  printf 'Full technical log: %s\n' "$_raw" >&2
  return "$_code"
}
# One step, with the same prerequisite, marker and package gate for `step` and
# `all`. Keeping a single implementation stops the two paths from drifting.
agent_log_summary() {
  # Full current-project log, spooled to disk so producer failure is not hidden
  # by a successful summarizer in a POSIX pipeline. Never touches step state.
  (
    agent=${1:-intake}
    case "$agent" in intake|reviewer) ;; *) printf '[DISCOVERY] agent summary unavailable (invalid_agent)\n'; exit 0;; esac
    summary_tmp=$(mktemp -d "${TMPDIR:-/tmp}/babycdf-summary-XXXXXXXX") || { printf '[DISCOVERY] intake summary unavailable (temporary_storage)\n'; exit 0; }
    trap 'rm -f -- "$summary_tmp/log"; rmdir "$summary_tmp"' EXIT HUP INT TERM
    if ! compose logs --no-color "$agent" > "$summary_tmp/log" 2>/dev/null; then
      printf '[DISCOVERY] %s summary unavailable (log_read_failed)\n' "$agent"
      exit 0
    fi
    python3 "$HERE/summarize-agent-log.py" "$agent" < "$summary_tmp/log" 2>/dev/null || printf '[DISCOVERY] %s summary unavailable (summary_failed)\n' "$agent"
    exit 0
  )
}

run_step() {
  case "$1" in 5) echo 'Step 5 remains retired. Nothing was run.' >&2; return 2;; esac
  # Raw logs live under the gitignored build root, never in the repository.
  if [ -z "$RAW_DIR" ]; then
    babycdf_build_root "$HERE/.." || return 2
    RAW_DIR="$BABYCDF_BUILD_ROOT/demo-logs"
  fi
  case "$1" in
    2) NUMBER=2; INPUT="$STATE/bootstrap.json"; SCRIPT=ruleVote ;;
    3) NUMBER=3; INPUT="$STATE/bootstrap.json"; SCRIPT=proposalVote ;;
    4) NUMBER=4; INPUT="$STATE/bootstrap.json"; SCRIPT=milestoneVote ;;
    5) NUMBER=5; INPUT="$STATE/step-4.json"; SCRIPT=couponAllocate ;;
    6) echo 'Step 6 was split into independent optional rounds; use opt-step 2, 3, 4 or 5. Nothing was run.' >&2; return 2 ;;
    *) echo 'Steps 2-5 are implemented; pass a step number in that range.' >&2; return 2 ;;
  esac
  echo "EXPERIMENTAL step $NUMBER: host submits as synthetic opener/voters, not agent user." >&2
  if [ ! -f "$INPUT" ] || { [ "$NUMBER" != 3 ] && [ "$NUMBER" != 4 ] && { [ -e "$STATE/step-$NUMBER.json" ] || [ -e "$STATE/step-$NUMBER.attempted" ]; }; }; then
    if [ "$NUMBER" = 3 ] || [ "$NUMBER" = 4 ]; then
      echo "Refused manual step $NUMBER: bootstrap prerequisite $INPUT must exist. Existing markers are retained; do not reset the network to resume a round." >&2
    else
      echo "Refused step $NUMBER: prerequisite $INPUT must exist; step-$NUMBER.json and step-$NUMBER.attempted must not exist. After an attempt, run down/up." >&2
    fi
    return 2
  fi
  if [ "$NUMBER" = 3 ] || [ "$NUMBER" = 4 ]; then
    echo 'Manual existing-round vote: ledger proof requires Architecture step 2 or later and controls resume; bootstrap alone is not readiness. PENDING is not approval. No Intake request or round opening. Existing attempt markers are retained.' >&2
  fi
  if [ "$NUMBER" = 3 ] || [ "$NUMBER" = 4 ]; then
    agent_log_summary intake
  fi
  python3 "$HERE/package-gate.py" "$STATE" "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar"
  : > "$STATE/step-$NUMBER.attempted"
  NEXT=$(mktemp "$STATE/step-$NUMBER-next-XXXXXXXX")
  trap 'rm -f -- "$NEXT"' EXIT HUP INT TERM
  render_step "step-$NUMBER" \
    dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
    --script-name "Cap.Examples.BabyCdf.Live:$SCRIPT" --input-file "$STATE/run-id.json" \
    --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
    --upload-dar true --output-file "$NEXT"
  mv "$NEXT" "$STATE/step-$NUMBER.json"
  trap - EXIT HUP INT TERM
}

# One OPTIONAL round. These are side demonstrations, not part of the journey:
#
#   * They do not advance the run anchor, so the main steps are unaffected and
#     `step 3` still works after `opt-step 1`.
#   * Their markers and outputs use an `opt-step-N` prefix of their own, so
#     they can never satisfy or block a main step's prerequisite, and their raw
#     log cannot collide with `step-1` or the bootstrap log.
#   * `all` ignores them entirely.
run_opt_step() {
  case "$1" in 2|3|4|5) echo 'Optional source ballot fixtures are retired in integrated mode. Nothing was run.' >&2; return 2;; esac
  if [ -z "$RAW_DIR" ]; then
    babycdf_build_root "$HERE/.." || return 2
    RAW_DIR="$BABYCDF_BUILD_ROOT/demo-logs"
  fi
  case "$1" in
    1) OPT=1; SCRIPT=staleRuleChange; OPT_PREREQ=step-2.json
       OPT_TITLE='stale rule change: approved by vote, refused at execution' ;;
    2) OPT=2; SCRIPT=credentialRefusals; OPT_PREREQ=bootstrap.json
       OPT_TITLE='credentials: asserted group mismatch; foreign lookup privacy, not issuer authority' ;;
    3) OPT=3; SCRIPT=noQuorum; OPT_PREREQ=bootstrap.json
       OPT_TITLE='no quorum: two core YES, five absent; Lapsed, zero executions' ;;
    4) OPT=4; SCRIPT=doubleVote; OPT_PREREQ=bootstrap.json
       OPT_TITLE='double vote: consumed ballot and asserted already-cast refusal' ;;
    5) OPT=5; SCRIPT=outsiderRefusals; OPT_PREREQ=bootstrap.json
       OPT_TITLE='outsider: visibility separated from unauthorized proposer; live ballot owner control' ;;
    '') echo 'Usage: ./run-demo.sh opt-step N   (optional rounds 1..5)' >&2; return 2 ;;
    *) echo "Unknown optional round '$1'; choose 1..5." >&2; return 2 ;;
  esac
  echo "OPTIONAL round $OPT: $OPT_TITLE" >&2
  echo "EXPERIMENTAL: host submits as synthetic opener/voters, not agent user." >&2
  # Needs a run that has reached step 2, because it acts against the activated
  # v2 rule book. The anchor itself is checked in the script; this is the
  # cheap local check that gives a clear refusal before starting a JVM.
  if [ ! -f "$STATE/$OPT_PREREQ" ]; then
    if [ "$OPT" = 1 ]; then
      echo "Refused optional round $OPT: needs step 2 to have completed (no $STATE/$OPT_PREREQ)." >&2
    else
      echo "Refused optional round $OPT: needs bootstrap (no $STATE/$OPT_PREREQ); no main voting step is required." >&2
    fi
    return 2
  fi
  if [ -e "$STATE/opt-step-$OPT.json" ] || [ -e "$STATE/opt-step-$OPT.attempted" ]; then
    echo "Refused optional round $OPT: it already ran or was attempted on this network." >&2
    echo 'Each round runs once per network; after an attempt run down then up.' >&2
    return 2
  fi
  python3 "$HERE/package-gate.py" "$STATE" "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar"
  : > "$STATE/opt-step-$OPT.attempted"
  NEXT=$(mktemp "$STATE/opt-step-$OPT-next-XXXXXXXX")
  trap 'rm -f -- "$NEXT"' EXIT HUP INT TERM
  render_step "opt-step-$OPT" \
    dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
    --script-name "Cap.Examples.BabyCdf.Live:$SCRIPT" --input-file "$STATE/run-id.json" \
    --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
    --upload-dar true --output-file "$NEXT"
  mv "$NEXT" "$STATE/opt-step-$OPT.json"
  trap - EXIT HUP INT TERM
}
case "${1:-help}" in
  up)
    credential_validity_seconds=$(python3 "$HERE/credential_validity.py" "${BABYCDF_CREDENTIAL_VALIDITY:-30d}") || exit $?
    case "${BABYCDF_STARTUP_TIMEOUT:-600}" in ''|*[!0-9]*|0*|?????*) echo 'BABYCDF_STARTUP_TIMEOUT must be 180..1800 seconds' >&2; exit 2;; esac
    [ "${BABYCDF_STARTUP_TIMEOUT:-600}" -ge 180 ] && [ "${BABYCDF_STARTUP_TIMEOUT:-600}" -le 1800 ] || { echo 'BABYCDF_STARTUP_TIMEOUT must be 180..1800 seconds' >&2; exit 2; }
    if [ "${BABYCDF_LLM_ENABLED:-0}" = 1 ] && [ -n "${LLM_API_KEY_FILE:-}" ]; then
      case "$LLM_API_KEY_FILE" in
        /*) ;;
        *) echo 'BLOCKED: LLM file secret requires an absolute readable regular non-symlink file; see LLM.md.' >&2; exit 2 ;;
      esac
      if [ ! -f "$LLM_API_KEY_FILE" ] || [ -L "$LLM_API_KEY_FILE" ] || [ ! -r "$LLM_API_KEY_FILE" ]; then
        echo 'BLOCKED: LLM file secret requires an absolute readable regular non-symlink file; see LLM.md.' >&2
        exit 2
      fi
    fi
    # Fail before build-root creation, cleanup, stages, containers or publication.
    # This checks metadata/access only, never reads or prints credential contents.
    if [ "${INTAKE_DISCOVERY_MODE:-live}" != fixture ] || [ -n "${INTAKE_GITHUB_TOKEN_FILE:-}" ]; then
      case "${INTAKE_GITHUB_TOKEN_FILE:-}" in
        /*) ;;
        *) echo 'BLOCKED: live Intake requires an absolute readable regular token file; see GITHUB-TOKEN.md.' >&2; exit 2 ;;
      esac
      if [ ! -f "$INTAKE_GITHUB_TOKEN_FILE" ] || [ -L "$INTAKE_GITHUB_TOKEN_FILE" ] || [ ! -r "$INTAKE_GITHUB_TOKEN_FILE" ]; then
        echo 'BLOCKED: live Intake requires an absolute readable regular non-symlink token file; see GITHUB-TOKEN.md.' >&2
        exit 2
      fi
    fi
    # Shared contract with prepare-dependencies.sh: unset defaults to the
    # gitignored .build; an explicit value must be absolute and already exist,
    # and an invalid one creates nothing.
    babycdf_build_root "$HERE/.." || exit 2
    python3 "$HERE/check-ledger-ports.py" || exit 2
    BUILD="$BABYCDF_BUILD_ROOT"
    REPO=$(CDPATH= cd -- "$HERE/../../../.." && pwd -P)
    RAW_DIR="$BUILD/demo-logs/up-$(date +%Y%m%d-%H%M%S)-$$"
    printf '[up] Full stage logs: %s\n' "$RAW_DIR"
    up_stage cips 'Exporting pinned CIPS' sh "$HERE/compose/prepare-cips.sh"
    if [ -n "$(compose ps -q canton)" ]; then
      echo 'Already running: down first before a second up (dedicated user creation is not idempotent).' >&2
      exit 2
    fi
    if docker volume inspect babycdf-live_intake-journal >/dev/null 2>&1; then
      echo 'Existing Intake journal volume: run dedicated demo down first; never delete individual markers.' >&2
      exit 2
    fi
    rm -f -- "$HERE/compose/state/readback" "$STATE/readback-intake" "$STATE/intake-demo-package" "$STATE/impl-package.json" "$HERE/compose/state/bootstrap.json" "$STATE/step-2.json" "$STATE/step-3.json" "$STATE/step-4.json" "$STATE/step-5.json" "$STATE/step-6.json" "$STATE/step-2.attempted" "$STATE/step-3.attempted" "$STATE/step-4.attempted" "$STATE/step-5.attempted" "$STATE/step-6.attempted" "$STATE/demo-dar.json" "$STATE/demo-inspect.json" "$STATE/run-id.json" "$STATE/bootstrap-input.json" "$STATE/opt-step-1.json" "$STATE/opt-step-1.attempted" "$STATE/opt-step-2.json" "$STATE/opt-step-2.attempted" "$STATE/opt-step-3.json" "$STATE/opt-step-3.attempted" "$STATE/opt-step-4.json" "$STATE/opt-step-4.attempted" "$STATE/opt-step-5.json" "$STATE/opt-step-5.attempted"
    # Interrupted steps can leave their atomic temp file behind; these are exact
    # fixed-prefix demo paths, never a recursive delete.
    rm -f -- "$STATE"/step-2-next-* "$STATE"/step-3-next-* "$STATE"/step-4-next-* "$STATE"/step-5-next-* "$STATE"/step-6-next-* "$STATE"/bootstrap-next-* "$STATE"/proposal-next-* "$STATE"/opt-step-1-next-* "$STATE"/opt-step-2-next-* "$STATE"/opt-step-3-next-* "$STATE"/opt-step-4-next-* "$STATE"/opt-step-5-next-*
    up_stage intake-packaging-tests 'Checking Intake packaging offline' python3 -B "$HERE/test-intake-packaging.py"
    up_stage readback-tests 'Checking readback' python3 -B "$HERE/test_readback.py"
    up_stage entrypoint-tests 'Checking entrypoint and locales' sh "$HERE/compose/agents/test-entrypoint.sh"
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
    up_stage dependencies 'Checking checksums and building credentials DAR' sh "$HERE/../prepare-dependencies.sh"
    # dpm resolves the nearest ancestor multi-package.yaml. When the build root
    # is inside Concordia that is the repository root, which does not list these
    # packages. Each build gets its own isolated, script-owned manifest
    # directory, so a caller-owned $BUILD/multi-package.yaml is never touched and
    # a concurrent dependency preparation cannot alter this one. The root
    # manifest and `dpm build --all` are unaffected.
    IMPL_DIR=$(CDPATH= cd -- "$HERE/../impl" && pwd -P)
    DEMO_DIR=$(CDPATH= cd -- "$HERE" && pwd -P)
    STEP_MANIFEST=$(babycdf_manifest_dir "$BUILD" "$IMPL_DIR" "$DEMO_DIR")
    # Run from the isolated manifest directory. dpm resolves an ancestor
    # multi-package.yaml from the CWD too, so building from $BUILD would pick up
    # a caller-owned manifest sitting there.
    (cd "$STEP_MANIFEST" && up_stage impl-build 'Building BabyCDF implementation DAR' env DAML_PACKAGE="$IMPL_DIR" \
      dpm build --enable-multi-package=yes --multi-package-path "$STEP_MANIFEST")
    (cd "$STEP_MANIFEST" && up_stage demo-build 'Building BabyCDF demo DAR' env DAML_PACKAGE="$DEMO_DIR" \
      dpm build --enable-multi-package=yes --multi-package-path "$STEP_MANIFEST")
    up_stage canton 'Starting Canton (wait for healthy)' compose up -d --build --wait --wait-timeout "${BABYCDF_STARTUP_TIMEOUT:-600}" canton
    mkdir -p "$STATE"
    NEXT=$(mktemp "$STATE/bootstrap-next-XXXXXXXX.json")
    python3 - "$HERE/../rules" "$STATE/bootstrap-input.json" "$credential_validity_seconds" <<'PY'
import hashlib, json, sys
from pathlib import Path
root=Path(sys.argv[1])
def ref(version):
    path=root/f'rule-v{version}.json'
    return dict(location=f'rules/rule-v{version}.json',commit=None,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
Path(sys.argv[2]).write_text(json.dumps(dict(input=dict(userName='babycdf-agent',windowSeconds=60,initialRuleRef=ref(1),proposedRuleRef=ref(2)),credentialValiditySeconds=int(sys.argv[3])))+'\n')
PY
    printf '[INIT] network healthy; uploading DAR, allocating synthetic parties and verifying dedicated user rights\n'
    DEMO_SETUP_FILTER=1 render_step step-1 \
      dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
      --script-name Cap.Examples.BabyCdf.Live:bootstrapConfigured --input-file "$STATE/bootstrap-input.json" \
      --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
      --upload-dar true --output-file "$NEXT"
    mv "$NEXT" "$STATE/bootstrap.json"
    up_inspect_demo() {
      dpm inspect-dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" --json > "$STATE/demo-inspect.json"
    }
    up_stage demo-inspection 'Checking demo package' up_inspect_demo
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
    up_inspect_impl() {
      dpm inspect-dar "$HERE/../impl/.daml/dist/cap-example-babycdf-0.1.0.dar" --json > "$STATE/impl-package.json"
    }
    up_stage impl-inspection 'Checking implementation package' up_inspect_impl
    up_stage readback-publication 'Publishing verified reader and Intake metadata' python3 "$HERE/publish-readback.py" "$STATE" "$LEDGER_PORT" "$STATE/impl-package.json" "$STATE/demo-inspect.json"
    up_stage agents 'Starting Intake opener and merged read-only Reviewer' compose up -d --build --wait --wait-timeout 60 intake reviewer
    ;;
  logs) compose logs -f --timestamps ;;
  down)
    # Dedicated Compose project volume only; never delete arbitrary host journals.
    compose down --volumes
    rm -f -- "$HERE/compose/state/readback" "$STATE/readback-intake" "$STATE/intake-demo-package" "$STATE/impl-package.json" "$HERE/compose/state/bootstrap.json" "$STATE/step-2.json" "$STATE/step-3.json" "$STATE/step-4.json" "$STATE/step-5.json" "$STATE/step-6.json" "$STATE/step-2.attempted" "$STATE/step-3.attempted" "$STATE/step-4.attempted" "$STATE/step-5.attempted" "$STATE/step-6.attempted" "$STATE/demo-dar.json" "$STATE/demo-inspect.json" "$STATE/run-id.json" "$STATE/bootstrap-input.json" "$STATE/opt-step-1.json" "$STATE/opt-step-1.attempted" "$STATE/opt-step-2.json" "$STATE/opt-step-2.attempted" "$STATE/opt-step-3.json" "$STATE/opt-step-3.attempted" "$STATE/opt-step-4.json" "$STATE/opt-step-4.attempted" "$STATE/opt-step-5.json" "$STATE/opt-step-5.attempted"
    # Interrupted steps can leave their atomic temp file behind; these are exact
    # fixed-prefix demo paths, never a recursive delete.
    rm -f -- "$STATE"/step-2-next-* "$STATE"/step-3-next-* "$STATE"/step-4-next-* "$STATE"/step-5-next-* "$STATE"/step-6-next-* "$STATE"/bootstrap-next-* "$STATE"/proposal-next-* "$STATE"/opt-step-1-next-* "$STATE"/opt-step-2-next-* "$STATE"/opt-step-3-next-* "$STATE"/opt-step-4-next-* "$STATE"/opt-step-5-next-*
    ;;
  step) run_step "${2:-}" ;;
  opt-step) run_opt_step "${2:-}" ;;
  all)
    echo 'Integrated demo: only the explicit Architecture rule vote runs; source agendas remain unvoted.' >&2
    run_step 2
    exit $?
    # A fresh network is required: bootstrap must exist and no later step may
    # have run or been attempted, so `all` can never resume a partial journey.
    if [ ! -f "$STATE/bootstrap.json" ]; then
      echo 'Refused all: no bootstrap.json; run up first.' >&2; exit 2
    fi
    for n in 2 3 4 5; do
      if [ -e "$STATE/step-$n.json" ] || [ -e "$STATE/step-$n.attempted" ]; then
        echo "Refused all: step $n already ran or was attempted on this network; run down then up for a fresh network." >&2
        exit 2
      fi
    done
    printf 'Running steps 2-5 sequentially with %s seconds between steps.\n' "$PAUSE" >&2
    printf 'Optional rounds are not part of all; run them explicitly with opt-step N.\n' >&2
    for n in 2 3 4 5; do
      printf -- '--- all: starting step %s ---\n' "$n" >&2
      run_step "$n"
      if [ "$n" != 5 ]; then
        printf -- '--- all: step %s done; pausing %s seconds ---\n' "$n" "$PAUSE" >&2
        sleep "$PAUSE"
      else
        printf -- '--- all: step %s done; steps 2-5 complete ---\n' "$n" >&2
      fi
    done
    ;;
  help)
    printf 'Usage: sh %s {up|logs|down|step N|all|help}\n' "$0"
    printf '  up        build, start Canton, bootstrap and start the offline agents\n'
    printf '  logs      follow Canton and agent logs (terminal 1)\n'
    printf '  step N    run one step, N in 2..5 (terminal 2)\n'
    printf '  opt-step N  run one OPTIONAL round, N in 1..5 (not part of all)\n'
    printf '  all       run the Architecture rule-vote journey only; source votes require explicit step 3/4 commands\n'
    printf '  down      stop everything and clear demo state\n'
    printf 'Startup: quiet stage summaries plus visible step-1 setup; VERBOSE=1 shows full stage logs.\n'
    printf 'Steps: 2 rule vote; 3 existing PR184 round; 4 existing milestone540 round; 5 retired.\n'
    printf 'Optionals 2-5 need only bootstrap, run independently in any order, and never advance the main anchor.\n'
    printf '  2 credentials (group mismatch vs lookup privacy), 3 no quorum (Lapsed),\n'
    printf '  4 double vote (consumed and already cast), 5 outsider (visibility and proposer authorization).\n'
    printf 'Steps 3/4 never open rounds: cast missing votes, return PENDING, then explicitly resume after the existing deadline. Ledger evidence checks prerequisites.\n'
    printf 'Optional round 1 (after step 2): a rule change written against the superseded v1.\n'
    printf '  It is proposed, voted and APPROVED, then REFUSED at execution because the\n'
    printf '  agenda pinned registry v1 while the live registry is already v2. The vote is\n'
    printf '  not undone; only the application is refused. It does not advance the run,\n'
    printf '  so the main journey continues normally afterwards.\n'
    printf 'Step 2 remains one-shot. For steps 3/4 retain markers and rerun the same command: authoritative ledger checkpoints control safe resume.\n'
    ;;
  *) echo 'Unknown command; use help.' >&2; exit 2 ;;
esac
