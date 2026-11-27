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
  # Raw logs live under the gitignored build root, never in the repository.
  if [ -z "$RAW_DIR" ]; then
    babycdf_build_root "$HERE/.." || return 2
    RAW_DIR="$BABYCDF_BUILD_ROOT/demo-logs"
  fi
  case "$1" in
    2) NUMBER=2; INPUT="$STATE/bootstrap.json"; SCRIPT=ruleVote ;;
    3) NUMBER=3; INPUT="$STATE/bootstrap.json"; SCRIPT=proposalVote ;;
    4) NUMBER=4; INPUT="$STATE/bootstrap.json"
       # Step 4 takes an optional explicit round selector: a full round id or
       # the bare trigger comment id. Without it the script proceeds only when
       # exactly one round is eligible, and otherwise refuses with the list.
       #
       # The handoff entry points run exactly the same vote as milestoneVote
       # and milestoneVoteFor; they differ only in returning the machine-read
       # identity of the approval the round produced, so step 5 can act on
       # that approval instead of re-deriving one from a subject constant.
       if [ -n "${2:-}" ]; then
         SCRIPT=milestoneVoteHandoffFor
         STEP4_ROUND=$2
       else
         SCRIPT=milestoneVoteHandoff
       fi ;;
    5) NUMBER=5; INPUT="$STATE/step-4.json"; SCRIPT=couponAllocate ;;
    6) echo 'Step 6 was split into independent optional rounds; use opt-step 2, 3, 4 or 5. Nothing was run.' >&2; return 2 ;;
    *) echo 'Steps 2-5 are implemented; pass a step number in that range.' >&2; return 2 ;;
  esac
  echo "EXPERIMENTAL step $NUMBER: host submits as synthetic opener/voters, not agent user." >&2
  if [ ! -f "$INPUT" ] || [ -e "$STATE/step-$NUMBER.json" ] || [ -e "$STATE/step-$NUMBER.attempted" ]; then
    echo "Refused step $NUMBER: prerequisite $INPUT must exist; step-$NUMBER.json and step-$NUMBER.attempted must not exist. After an uncertain attempt, reconcile read-only manually; do not automatically retry, recast, or reset the network." >&2
    return 2
  fi
  if [ "$NUMBER" = 3 ] || [ "$NUMBER" = 4 ]; then
    echo 'Manual existing-round vote: ledger proof requires Architecture step 2 or later and controls resume; bootstrap alone is not readiness. PENDING is not approval. No Intake request or round opening. Existing attempt markers are retained.' >&2
  fi
  if [ "$NUMBER" = 3 ] || [ "$NUMBER" = 4 ]; then
    agent_log_summary intake
  fi
  if [ "$NUMBER" = 5 ]; then
    echo 'Step 5 allocates a sandbox Development Fund coupon citing ONLY the approval step 4 actually produced. It is not a mint and not a payment, and it never substitutes an illustrative milestone.' >&2
    # The step-4 result IS the step-5 input: it carries the approval identity
    # that step 5 binds. A step 4 that recorded votes without an executable
    # approval carries none, and step 5 refuses on it rather than choosing.
    #
    # run-id.json is passed so the handoff is bound to THIS network's run.
    # The Daml side cannot do this: findAnchorAtLeast matches on demoRunId
    # alone, so a handoff naming another valid run awaiting step 5 would bind
    # to that run and allocate against its approval. This runs before the
    # prevalidation script and before any marker or lock exists, so a
    # mismatch, a missing run-id.json or a malformed one refuses without
    # leaking a marker, a lock, or a submission.
    if ! python3 "$HERE/step5-handoff.py" "$INPUT" "$STATE/run-id.json" >/dev/null; then
      echo "Refused step 5: $INPUT is not a usable step-4 handoff for this network's run." >&2
      echo 'Nothing was run and no marker was written.' >&2
      return 2
    fi
  fi
  python3 "$HERE/package-gate.py" "$STATE" "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar"
  # Step 5 only: prove the handoff still binds to live ledger state BEFORE the
  # one-shot marker exists, so a definite pre-submission refusal cannot strand
  # a permanent marker. This is a read-only script: it submits nothing and
  # allocates no party. It cannot close the time-of-check/time-of-use window,
  # so couponAllocate re-runs the identical binding internally and that second
  # check is the authoritative one.
  if [ "$NUMBER" = 5 ]; then
    echo 'Prevalidating the step-4 handoff read-only before any marker is written.' >&2
    if ! render_step "step-5-prevalidation" \
      dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
      --script-name Cap.Examples.BabyCdf.Live:couponAllocatePrevalidate \
      --input-file "$INPUT" \
      --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
      --upload-dar true; then
      echo 'Refused step 5: the step-4 handoff does not bind to an active approval.' >&2
      echo 'Nothing was submitted and no marker was written, so a corrected' >&2
      echo 'step 4 or a read-only reconciliation can still proceed.' >&2
      return 2
    fi
  fi
  # Steps 4 and 5: hold an exclusive per-step lock for the whole attempt, so a
  # recovery cannot run while a submission may be in flight and two runs of the
  # SAME step cannot overlap. One helper, step_lock_acquire, owns the atomic
  # mkdir; the trap is installed IMMEDIATELY after acquiring, so no failure
  # between here and the dpm invocation can leak the lock.
  #
  # Step 5 needs this for the same reason step 4 does, and the one-shot
  # requirement is why: the guard above runs before any lock exists, so two
  # simultaneous `step 5` invocations could both pass it and both allocate a
  # coupon from a single approval. The Daml side cannot serialize them either,
  # because the allocation submit happens before the anchor advance. The anchor
  # is deliberately NOT moved before the allocation: doing so would strand a
  # retry after a failed allocation, which is worse than this lock. Scope is
  # stated plainly: cooperative and process-scoped, so it excludes concurrent
  # runner invocations, which is the supported case, and cannot exclude an
  # external ledger client, which is explicitly unsupported.
  if [ "$NUMBER" = 4 ] || [ "$NUMBER" = 5 ]; then
    step_lock_acquire "$NUMBER" || return 2
    trap 'step_cleanup' EXIT
    trap 'step_cleanup signal; exit 2' HUP INT TERM
    # Close the time-of-check/time-of-use window. The one-shot guard above ran
    # before the lock existed, so a concurrent run of this step may have
    # completed in between and written the marker or the result. Both are
    # rechecked here, now that the lock is held, and a late loser refuses
    # instead of running a second vote or a second allocation.
    if [ -e "$STATE/step-$NUMBER.attempted" ] || [ -e "$STATE/step-$NUMBER.json" ]; then
      echo "Refused step $NUMBER: another step-$NUMBER run completed while this one" >&2
      echo 'was starting, so an attempt marker or result already exists.' >&2
      echo 'Reconcile read-only; nothing was created or changed here.' >&2
      return 2
    fi
  fi
  # Everything that can still fail is prepared BEFORE the one-shot marker is
  # written, so a failure here does not leave a marker that blocks every
  # later retry. The raw log is append-only across runs, so the attempt is
  # bound to a byte offset in the configured log, never to a guessed path.
  STEP_LOG="$RAW_DIR/step-$NUMBER.log"
  if ! mkdir -p "$RAW_DIR" 2>/dev/null || ! : >> "$STEP_LOG" 2>/dev/null; then
    echo "Refused step $NUMBER: cannot open $STEP_LOG for append." >&2
    return 2
  fi
  STEP_META=$(mktemp "$STATE/step-$NUMBER-meta-XXXXXXXX") || {
    echo "Refused step $NUMBER: cannot create attempt metadata." >&2
    return 2
  }
  if ! python3 -c 'import json,os,sys; p=sys.argv[2]; json.dump({"log_path": os.path.realpath(p), "log_offset": os.path.getsize(p), "step": int(sys.argv[3])}, open(sys.argv[1], "w"))' \
      "$STEP_META" "$STEP_LOG" "$NUMBER"; then
    rm -f -- "$STEP_META"
    echo "Refused step $NUMBER: cannot record attempt metadata." >&2
    return 2
  fi
  NEXT=$(mktemp "$STATE/step-$NUMBER-next-XXXXXXXX") || {
    rm -f -- "$STEP_META"
    echo "Refused step $NUMBER: cannot create the output file." >&2
    return 2
  }
  # Only now, with the log open and metadata and output files in hand, is the
  # one-shot marker written, together with the metadata that bounds it.
  mv "$STEP_META" "$STATE/step-$NUMBER.attempted.attempt"
  : > "$STATE/step-$NUMBER.attempted"
  export BABYCDF_STEP4_LOG="$STEP_LOG"
  trap 'rm -f -- "${NEXT:-}"; step_cleanup' EXIT
  trap 'step_cleanup signal; rm -f -- "${NEXT:-}"; exit 2' HUP INT TERM
  # The selector variant takes a record, so it needs its own input file. It is
  # built next to the state it describes and is never a template expansion.
  STEP_INPUT="$STATE/run-id.json"
  # Step 5's input is the step-4 handoff itself, not the bare run id: the
  # approval identity it must bind is in that file and nowhere else.
  if [ "$NUMBER" = 5 ]; then
    STEP_INPUT=$INPUT
  fi
  # Step 4 now returns a record, so the bare form needs an input record too.
  if [ "$NUMBER" = 4 ] && [ -z "${STEP4_ROUND:-}" ]; then
    STEP_INPUT="$STATE/run-id.json"
  fi
  if [ "$NUMBER" = 4 ] && [ -n "${STEP4_ROUND:-}" ]; then
    SEL_NEXT=$(mktemp "$STATE/step-4-selector-XXXXXXXX")
    python3 -c 'import json,sys; json.dump({"runId": json.load(open(sys.argv[1])), "round": sys.argv[2]}, open(sys.argv[3], "w"))' \
      "$STATE/run-id.json" "$STEP4_ROUND" "$SEL_NEXT"
    STEP_INPUT=$SEL_NEXT
    trap 'rm -f -- "${NEXT:-}" "${SEL_NEXT:-}"; step_cleanup' EXIT
    trap 'step_cleanup signal; rm -f -- "${NEXT:-}" "${SEL_NEXT:-}"; exit 2' HUP INT TERM
    printf 'Explicit round selector: %s\n' "$STEP4_ROUND" >&2
  fi
  if [ "$NUMBER" = 4 ] || [ "$NUMBER" = 5 ]; then
    # Run the whole render in a background subshell and record its pid, so the
    # lock provably covers the child's entire lifetime: step_cleanup waits for
    # this pid before releasing, and retains the lock if it cannot reap it.
    # Step 5 uses the identical pattern, so an interrupted allocation is
    # retained as uncertain exactly like an interrupted vote.
    render_step "step-$NUMBER" \
      dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
      --script-name "Cap.Examples.BabyCdf.Live:$SCRIPT" --input-file "$STEP_INPUT" \
      --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
      --upload-dar true --output-file "$NEXT" &
    STEP4_CHILD=$!
    _step4_status=0
    wait "$STEP4_CHILD" || _step4_status=$?
    STEP4_CHILD=''
    if [ "$_step4_status" -ne 0 ]; then
      return "$_step4_status"
    fi
  else
    render_step "step-$NUMBER" \
      dpm script --dar "$HERE/.daml/dist/cap-example-babycdf-demo-0.1.0.dar" \
      --script-name "Cap.Examples.BabyCdf.Live:$SCRIPT" --input-file "$STEP_INPUT" \
      --ledger-host 127.0.0.1 --ledger-port "$LEDGER_PORT" --wall-clock-time \
      --upload-dar true --output-file "$NEXT"
  fi
  mv "$NEXT" "$STATE/step-$NUMBER.json"
  step_lock_release
  trap - EXIT HUP INT TERM
  rm -f -- "${SEL_NEXT:-}"
}

# Clears ONLY the step-4 attempt marker, and only when an independent
# fail-closed check proves this network's step 4 never reached the ledger.
#
# This is not a retry framework and not a reset. It deletes exactly one
# fixed path, after proof, on an explicit operator command, and it refuses on
# any uncertainty. `down` remains the only thing that clears markers wholesale.
# Exclusive per-step lock, the single implementation, shared by steps 4 and 5.
# mkdir is atomic, so the directory IS the lock. The holder's pid is recorded
# so a lock left by a dead process is distinguishable from a live one; a lock
# whose owner cannot be determined is treated as held and never broken.
# Nothing here deletes a marker. Cooperative and process-scoped: it serializes
# concurrent runner invocations and cannot exclude an external ledger client.
#
# The lock path carries the step number, so a step-4 lock and a step-5 lock are
# distinct files and neither blocks the other. Messages name the actual step,
# so an operator reconciling a retained lock knows which submission is in
# doubt.
STEP4_LOCK=''
STEP_LOCK_NUMBER=''
step_lock_acquire() {
  _step=$1
  _lock="$STATE/step-$_step.lock"
  if mkdir "$_lock" 2>/dev/null; then
    printf '%s\n' "$$" > "$_lock/pid"
    STEP4_LOCK=$_lock
    STEP_LOCK_NUMBER=$_step
    return 0
  fi
  if [ -e "$_lock/uncertain" ]; then
    echo "Refused: $_lock records an interrupted step-$_step attempt whose ledger" >&2
    echo 'outcome is unknown, so it is retained permanently. Reconcile' >&2
    echo 'read-only; only down clears it, and that destroys the network.' >&2
    return 2
  fi
  _owner=$(cat "$_lock/pid" 2>/dev/null || echo '')
  case "$_owner" in
    '' | *[!0-9]*)
      echo "Refused: a lock exists at $_lock but its owner cannot be" >&2
      echo 'determined, so it is treated as held. Reconcile read-only.' >&2
      return 2 ;;
  esac
  if kill -0 "$_owner" 2>/dev/null; then
    echo "Refused: another step-$_step run (pid $_owner) holds $_lock." >&2
    return 2
  fi
  echo "Refused: a stale lock from pid $_owner remains at $_lock." >&2
  echo 'Its run ended without releasing, so its ledger outcome is unknown.' >&2
  echo 'Reconcile read-only; this command never clears a lock it did not take.' >&2
  return 2
}

# Release only a lock THIS process took. The pid file is removed before the
# directory, or the directory would never be empty enough to remove.
step_lock_release() {
  # An uncertain attempt never releases, regardless of caller.
  if [ -n "${STEP4_UNCERTAIN:-}" ]; then
    return 0
  fi
  if [ -n "${STEP4_LOCK:-}" ] && [ -e "$STEP4_LOCK/uncertain" ]; then
    return 0
  fi
  if [ -n "${STEP4_LOCK:-}" ]; then
    rm -f -- "$STEP4_LOCK/pid"
    rmdir "$STEP4_LOCK" 2>/dev/null || :
    STEP4_LOCK=''
    STEP_LOCK_NUMBER=''
  fi
}

# Signal-safe cleanup, deliberately fail-closed.
#
# STEP4_CHILD is the render_step SUBSHELL, not dpm itself: dpm runs inside a
# pipeline in that subshell, so reaping the subshell does NOT prove the
# submitting process died. Releasing the lock on that basis could let a
# second step 4 start while a vote is still in flight.
#
# So: any interruption while a child had been started marks the attempt
# UNCERTAIN, and an uncertain attempt NEVER releases the lock. The marker is
# both an in-shell flag and a file, so repeated cleanup (signal trap followed
# by the EXIT trap) cannot clear it, and a later run reports a stale lock
# instead of assuming the ledger outcome is known. This is retention by
# choice, in preference to supervising process groups.
STEP4_CHILD=''
STEP4_UNCERTAIN=''

step_mark_uncertain() {
  STEP4_UNCERTAIN=1
  if [ -n "${STEP4_LOCK:-}" ]; then
    printf 'interrupted while a step-%s child was running\n' "${STEP_LOCK_NUMBER:-?}" \
      >> "$STEP4_LOCK/uncertain" 2>/dev/null || :
  fi
}

step_cleanup() {
  _sig=${1:-}
  if [ -n "$_sig" ] && [ -n "${STEP4_CHILD:-}" ]; then
    # Interrupted with a child outstanding: sticky-uncertain, whatever
    # happens next. Signalling the subshell does not prove dpm stopped.
    step_mark_uncertain
    kill -TERM "$STEP4_CHILD" 2>/dev/null || :
    wait "$STEP4_CHILD" 2>/dev/null || :
  fi
  if [ -n "${STEP4_UNCERTAIN:-}" ] || \
     { [ -n "${STEP4_LOCK:-}" ] && [ -e "$STEP4_LOCK/uncertain" ]; }; then
    STEP4_UNCERTAIN=1
    printf 'Step %s was interrupted while a submission may have been in flight.\n' "${STEP_LOCK_NUMBER:-4}" >&2
    echo 'The lock is RETAINED so a later run reports it as stale, because' >&2
    echo 'reaping the render shell does not prove the submitting process' >&2
    echo 'stopped. Reconcile read-only; nothing is cleared automatically.' >&2
    STEP4_CHILD=''
    return 0
  fi
  if [ -n "${STEP4_CHILD:-}" ] && kill -0 "$STEP4_CHILD" 2>/dev/null; then
    # A child is still alive with no signal recorded: still uncertain.
    step_mark_uncertain
    STEP4_CHILD=''
    return 0
  fi
  STEP4_CHILD=''
  step_lock_release
}

recover_step4_marker() {
  if [ -z "$RAW_DIR" ]; then
    babycdf_build_root "$HERE/.." || return 2
    RAW_DIR="$BABYCDF_BUILD_ROOT/demo-logs"
  fi
  # Recovery must not run while a step-4 attempt may be in flight. The trap
  # is installed IMMEDIATELY after acquiring, so a signal cannot leak the
  # lock between here and the explicit releases below.
  #
  # This helper is step 4 ONLY, deliberately: it takes the step-4 lock and it
  # clears exactly the step-4 marker. It never touches a step-5 marker or a
  # step-5 lock. A step 5 whose outcome is uncertain has no recovery command
  # at all and stays blocked until `down`, because proving that a coupon
  # allocation never reached the ledger is not what the step-4 log checker
  # does.
  step_lock_acquire 4 || return 2
  trap 'step_cleanup' EXIT
  trap 'step_cleanup signal; exit 2' HUP INT TERM
  echo 'Checking whether step 4 can be proven to have made no ledger submission.' >&2
  # The checker reads the exact log span this attempt recorded. Metadata that
  # is missing, stale or inconsistent is a refusal, never a fallback.
  STEP4_META="$STATE/step-4.attempted.attempt"
  if [ ! -f "$STEP4_META" ]; then
    echo 'Refused: no attempt metadata beside the step-4 marker, so the log span' >&2
    echo 'cannot be established. This marker predates bounded recovery.' >&2
    echo 'The marker is retained. Reconcile read-only.' >&2
    step_lock_release
    return 2
  fi
  STEP4_LOG=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["log_path"])' "$STEP4_META" 2>/dev/null) || {
    echo 'Refused: attempt metadata is unreadable; the marker is retained.' >&2
    step_lock_release
    return 2
  }
  if ! BABYCDF_STEP4_LOG="$STEP4_LOG" python3 "$HERE/step4-marker-recovery.py"; then
    echo 'Refused: the attempt marker is retained. Reconcile read-only.' >&2
    step_lock_release
    return 2
  fi
  if [ ! -e "$STATE/step-4.attempted" ]; then
    echo 'Nothing to clear.' >&2
    step_lock_release
    return 0
  fi
  rm -f -- "$STATE/step-4.attempted" "$STEP4_META"
  step_lock_release
  printf 'Cleared %s after proof of no submission.\n' "$STATE/step-4.attempted" >&2
  printf 'Rerun step 4 with an explicit round selector:\n' >&2
  printf '  ./run-demo.sh step 4 <round-id-or-comment-id>\n' >&2
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
    rm -f -- "$STATE"/step-2-next-* "$STATE"/step-3-next-* "$STATE"/step-4-next-* "$STATE"/step-5-next-* "$STATE"/step-6-next-* "$STATE"/bootstrap-next-* "$STATE"/proposal-next-* "$STATE"/opt-step-1-next-* "$STATE"/opt-step-2-next-* "$STATE"/opt-step-3-next-* "$STATE"/opt-step-4-next-* "$STATE"/opt-step-5-next-* "$STATE"/step-4-selector-* "$STATE"/step-4.lock/pid "$STATE"/step-4.lock/uncertain "$STATE"/step-5.lock/pid "$STATE"/step-5.lock/uncertain "$STATE"/step-2.attempted.attempt "$STATE"/step-3.attempted.attempt "$STATE"/step-4.attempted.attempt "$STATE"/step-5.attempted.attempt "$STATE"/step-6.attempted.attempt
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
s=Path(sys.argv[1])
configured=json.loads((s/'bootstrap-input.json').read_text())
if not isinstance(configured,dict) or not isinstance(configured.get('input'),dict):
    raise SystemExit('Invalid bootstrap-input.json: input must be an object')
user_name=configured['input'].get('userName')
if not isinstance(user_name,str) or not user_name or any(c.isspace() for c in user_name):
    raise SystemExit('Invalid bootstrap-input.json: input.userName must be a nonblank canonical user ID')
bootstrap=json.loads((s/'bootstrap.json').read_text())
if not isinstance(bootstrap,dict) or bootstrap.get('userName') != user_name:
    raise SystemExit('Invalid bootstrap.json: userName must match bootstrap-input.json input.userName')
intake_user=bootstrap.get('intakeUserName')
if intake_user != user_name + '-intake':
    raise SystemExit('Invalid bootstrap.json: intakeUserName must match input.userName plus -intake')
opener=bootstrap.get('opener')
if not isinstance(opener,str) or not opener or any(c.isspace() for c in opener):
    raise SystemExit('Invalid bootstrap.json: opener must be a nonblank party ID')
for field,tag in (('verifiedRights','CanReadAs'),('intakeVerifiedRights','CanActAs')):
    if bootstrap.get(field) != [{'tag':tag,'value':opener}]:
        raise SystemExit(f'Invalid bootstrap.json: {field} must contain exactly {tag} opener')
(s/'run-id.json').write_text(json.dumps(user_name))
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
    rm -f -- "$STATE"/step-2-next-* "$STATE"/step-3-next-* "$STATE"/step-4-next-* "$STATE"/step-5-next-* "$STATE"/step-6-next-* "$STATE"/bootstrap-next-* "$STATE"/proposal-next-* "$STATE"/opt-step-1-next-* "$STATE"/opt-step-2-next-* "$STATE"/opt-step-3-next-* "$STATE"/opt-step-4-next-* "$STATE"/opt-step-5-next-* "$STATE"/step-4-selector-* "$STATE"/step-4.lock/pid "$STATE"/step-4.lock/uncertain "$STATE"/step-5.lock/pid "$STATE"/step-5.lock/uncertain "$STATE"/step-2.attempted.attempt "$STATE"/step-3.attempted.attempt "$STATE"/step-4.attempted.attempt "$STATE"/step-5.attempted.attempt "$STATE"/step-6.attempted.attempt
    rmdir "$STATE/step-4.lock" 2>/dev/null || :
    rmdir "$STATE/step-5.lock" 2>/dev/null || :
    ;;
  step) run_step "${2:-}" "${3:-}" ;;
  opt-step) run_opt_step "${2:-}" ;;
  recover-step4-marker) recover_step4_marker ;;
  all)
    # `all` deliberately stops after the Architecture rule vote. Steps 3, 4
    # and 5 act on EXISTING source rounds and hand real money-side mechanics
    # a cited approval, so they stay explicit operator commands rather than
    # something an aggregate command casts on the operator's behalf. This is
    # the automatic-vote policy, and restoring step 5 did not change it:
    # step 5 is available through `step 5`, never through `all`.
    #
    # The former unreachable `for n in 2 3 4 5` loop below this point was
    # removed: it could never run, and it advertised an aggregate journey the
    # policy forbids. Optional rounds are not part of all either.
    echo 'Integrated demo: only the explicit Architecture rule vote runs; source agendas remain unvoted.' >&2
    echo 'Steps 3, 4 and 5 remain explicit manual commands: all never casts source votes or allocates a coupon.' >&2
    echo 'Optional rounds are not part of all; run them explicitly with opt-step N.' >&2
    if [ ! -f "$STATE/bootstrap.json" ]; then
      echo 'Refused all: no bootstrap.json; run up first.' >&2; exit 2
    fi
    run_step 2
    exit $?
    ;;
  help)
    printf 'Usage: sh %s {up|logs|down|step N|all|help}\n' "$0"
    printf '  up        build, start Canton, bootstrap and start the offline agents\n'
    printf '  logs      follow Canton and agent logs (terminal 1)\n'
    printf '  step N    run one step, N in 2..5 (terminal 2)\n'
    printf '  step 4 R  run step 4 against one explicit round R: a full round id\n'
    printf '            or the bare trigger comment id. Without R, step 4 proceeds\n'
    printf '            only when exactly one round is eligible and otherwise\n'
    printf '            refuses with every candidate and its first failing check.\n'
    printf '  recover-step4-marker\n'
    printf '            clear ONLY the step-4 attempt marker, and only after proof\n'
    printf '            that step 4 refused before any ledger submission. Refuses on\n'
    printf '            any uncertainty. Not a reset and not an automatic retry.\n'
    printf '  opt-step N  run one OPTIONAL round, N in 1..5 (not part of all)\n'
    printf '  all       run the Architecture rule-vote journey only; source votes and the coupon allocation require explicit step 3/4/5 commands\n'
    printf '  down      stop everything and clear demo state\n'
    printf 'Startup: quiet stage summaries plus visible step-1 setup; VERBOSE=1 shows full stage logs.\n'
    printf 'Steps: 2 rule vote; 3 existing PR184 round; 4 existing milestone540 round; 5 coupon allocation.\n'
    printf 'Step 5 refuses a step-4 handoff from another run: its run id must equal the run id recorded by up.\n'
    printf 'Step 5 needs step 4 to have produced an executable approval: it reads step-4.json, re-binds that\n'
    printf '  exact approval on the ledger and refuses if it is absent, archived, mismatched or ambiguous.\n'
    printf '  A blocked or incomplete step-4 review carries no approval, so step 5 refuses rather than citing\n'
    printf '  an illustrative milestone. The refusal is prevalidated read-only before any marker is written.\n'
    printf '  It allocates a sandbox Development Fund coupon: NOT a mint and NOT a payment.\n'
    printf 'Optionals 2-5 need only bootstrap, run independently in any order, and never advance the main anchor.\n'
    printf '  2 credentials (group mismatch vs lookup privacy), 3 no quorum (Lapsed),\n'
    printf '  4 double vote (consumed and already cast), 5 outsider (visibility and proposer authorization).\n'
    printf 'Steps 3/4 never open rounds: cast missing votes, return PENDING, then explicitly resume after the existing deadline. Ledger evidence checks prerequisites.\n'
    printf 'Optional round 1 (after step 2): a rule change written against the superseded v1.\n'
    printf '  It is proposed, voted and APPROVED, then REFUSED at execution because the\n'
    printf '  agenda pinned registry v1 while the live registry is already v2. The vote is\n'
    printf '  not undone; only the application is refused. It does not advance the run,\n'
    printf '  so the main journey continues normally afterwards.\n'
    printf 'Steps 4 and 5 each hold their own exclusive process lock for the whole run, so a second run of that step,\n'
    printf '  or a recovery, cannot overlap it. Step 5 needs this to stay one-shot: without it two simultaneous step-5\n'
    printf '  commands could both allocate a coupon from the same approval.\n'
    printf 'Those locks are cooperative and process-scoped: they serialize runner commands, which is the supported case,\n'
    printf '  and cannot prevent an external ledger client from submitting, which is not supported.\n'
    printf 'recover-step4-marker is step 4 ONLY; it never clears a step-5 marker or lock. An uncertain step 5 stays\n'
    printf '  blocked until down.\n'
    printf 'A lock left behind by a dead run is reported as stale and never broken automatically.\n'
    printf 'Every step stays one-shot: an attempt marker is written before the script runs and is never cleared automatically.\n'
    printf 'A step-4 refusal that happened before any ledger submission is recoverable with recover-step4-marker, which proves no submission first.\n'
    printf 'After any attempt that may have submitted, the marker is permanent: reconcile read-only. Only down clears markers wholesale, and it destroys the network.\n'
    ;;
  *) echo 'Unknown command; use help.' >&2; exit 2 ;;
esac
