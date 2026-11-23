#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Offline tests for run-demo.sh's render_step: exit-code propagation, raw-log
# completeness, verbose behaviour and streaming. No ledger, container, network
# or dpm invocation: render_step is driven with stub commands.
#
#   sh examples/governance/babycdf/demo/test-render-step.sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/babycdf-render-test-XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); printf '  ok    %s\n' "$1"; }
no() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

# Extract render_step and raw_log_path from run-demo.sh, so the test exercises
# the real implementation rather than a copy.
cat > "$WORK/harness.sh" <<'HARNESS'
set -eu
HERE=$RENDER_HERE
RAW_DIR=$RENDER_RAW_DIR
HARNESS
awk '/^raw_log_path\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
awk '/^render_step\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
printf '\n"$@"\n' >> "$WORK/harness.sh"
# Both functions must have been found, or the tests would silently pass.
grep -q '^raw_log_path() {' "$WORK/harness.sh" || { echo 'FAIL: raw_log_path not extracted'; exit 1; }
grep -q '^render_step() {' "$WORK/harness.sh" || { echo 'FAIL: render_step not extracted'; exit 1; }

run_case() {
  RENDER_HERE="$HERE" RENDER_RAW_DIR="$WORK/raw" sh "$WORK/harness.sh" "$@"
}

# --- fixtures -----------------------------------------------------------
cat > "$WORK/ok-cmd" <<'CMD'
#!/bin/sh
printf 'local DAR bytes unchanged since bootstrap; main=abc\n'
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][PHASE] \\n########## PROPOSAL ##########\\"\n'
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][BUSINESS] Rule v1 -> v2.\\"\n'
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][ACTION] opener exercises ResolveAgenda\\"\n'
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][EVIDENCE] VoteRecord active: 7 (3 YES, 0 NO, 4 ABSENT)\\"\n'
exit 0
CMD
cat > "$WORK/fail-cmd" <<'CMD'
#!/bin/sh
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][BUSINESS] starting\\"\n'
printf 'Script execution failed: Aborted: boom\n' >&2
exit 7
CMD
chmod +x "$WORK/ok-cmd" "$WORK/fail-cmd"

# --- 1. success path ----------------------------------------------------
OUT=$(run_case render_step step-2 "$WORK/ok-cmd" 2>"$WORK/err.txt") || no 'success exit 0'
case "$OUT" in
  *'PROPOSAL'*) ok 'phase heading rendered' ;;
  *) no "phase heading rendered: $OUT" ;;
esac
case "$OUT" in
  *'Rule v1 -> v2.'*) ok 'business line rendered' ;;
  *) no 'business line rendered' ;;
esac
case "$OUT" in
  *'VoteRecord active: 7 (3 YES, 0 NO, 4 ABSENT)'*) ok 'ledger evidence rendered' ;;
  *) no 'ledger evidence rendered' ;;
esac
case "$OUT" in
  *'[DA.'*) no 'SDK prefix removed' ;;
  *) ok 'SDK prefix removed' ;;
esac
case "$OUT" in
  *'\"'*) no 'escaped quotes removed' ;;
  *) ok 'escaped quotes removed' ;;
esac
case "$OUT" in
  *ResolveAgenda*) no 'technical ACTION withheld by default' ;;
  *) ok 'technical ACTION withheld by default' ;;
esac
case "$OUT" in
  *'local DAR bytes unchanged'*) ok 'non-debug line kept' ;;
  *) no 'non-debug line kept' ;;
esac

# --- 2. raw log completeness -------------------------------------------
RAW="$WORK/raw/step-2.log"
if [ -f "$RAW" ]; then ok 'raw log written'; else no 'raw log written'; fi
if grep -q '\[DA.Internal.Prelude:557\]' "$RAW"; then ok 'raw log keeps SDK prefix'
else no 'raw log keeps SDK prefix'; fi
if grep -q 'ResolveAgenda' "$RAW"; then ok 'raw log keeps ACTION lines'
else no 'raw log keeps ACTION lines'; fi
if grep -q 'Full technical log' "$WORK/err.txt"; then ok 'raw log path printed'
else no 'raw log path printed'; fi

# --- 3. exit code propagation (the pipefail trap) ----------------------
CODE=0
run_case render_step step-fail "$WORK/fail-cmd" >"$WORK/fail.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 7 ]; then ok 'dpm exit code 7 propagated through the pipeline'
else no "dpm exit code propagated (got $CODE, expected 7)"; fi
if grep -q 'Aborted: boom' "$WORK/fail.out"; then ok 'real error text still visible'
else no 'real error text still visible'; fi

# --- 4. verbose --------------------------------------------------------
VOUT=$(DEMO_VERBOSE=1 run_case render_step step-verbose "$WORK/ok-cmd" 2>/dev/null)
case "$VOUT" in
  *ResolveAgenda*) ok 'DEMO_VERBOSE=1 shows technical lines' ;;
  *) no 'DEMO_VERBOSE=1 shows technical lines' ;;
esac
VZERO=$(DEMO_VERBOSE=0 run_case render_step step-zero "$WORK/ok-cmd" 2>/dev/null)
case "$VZERO" in
  *ResolveAgenda*) no 'DEMO_VERBOSE=0 stays concise' ;;
  *) ok 'DEMO_VERBOSE=0 stays concise' ;;
esac

# --- 5. streaming: output appears before the command finishes ----------
cat > "$WORK/slow-cmd" <<'CMD'
#!/bin/sh
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][PHASE] \\n########## EARLY ##########\\"\n'
sleep 3
printf '[DA.Internal.Prelude:557]: \\"[STEP 2][PHASE] \\n########## LATE ##########\\"\n'
CMD
chmod +x "$WORK/slow-cmd"
( run_case render_step step-stream "$WORK/slow-cmd" > "$WORK/stream.out" 2>/dev/null ) &
STREAM_PID=$!
sleep 1
if grep -q 'EARLY' "$WORK/stream.out" 2>/dev/null; then
  ok 'headings stream as actions run (EARLY seen before command exit)'
else
  no 'headings stream as actions run'
fi
if grep -q 'LATE' "$WORK/stream.out" 2>/dev/null; then
  no 'LATE must not appear yet'
else
  ok 'later output not yet present, so this is genuine streaming'
fi
wait "$STREAM_PID" 2>/dev/null || true

# --- 6. raw capture is a PRECONDITION: the command must never run ------
#
# A marker command records that it was invoked. If the raw log cannot be
# opened, the marker must not exist: running a ledger mutation whose output
# cannot be recorded is the defect these cases guard.
cat > "$WORK/marker-cmd" <<CMD
#!/bin/sh
printf 'invoked\n' > "$WORK/marker.flag"
printf 'output from the command\n'
CMD
chmod +x "$WORK/marker-cmd"

check_refused() {
  _name=$1; _dir=$2
  rm -f "$WORK/marker.flag"
  _code=0
  RENDER_HERE="$HERE" RENDER_RAW_DIR="$_dir" sh "$WORK/harness.sh" \
    render_step step-x "$WORK/marker-cmd" >"$WORK/$_name.out" 2>&1 || _code=$?
  if [ "$_code" -eq 0 ]; then no "$_name: must refuse (exit was 0)"; return; fi
  if [ -f "$WORK/marker.flag" ]; then
    no "$_name: THE COMMAND RAN despite an unusable raw log"
  else
    ok "$_name: refused and the command was never invoked (exit $_code)"
  fi
  if grep -qi 'refused' "$WORK/$_name.out"; then ok "$_name: explains the refusal"
  else no "$_name: explains the refusal"; fi
}

# A directory that cannot be created: a plain file sits at the path.
printf 'not a directory\n' > "$WORK/blocker"
check_refused 'file-in-place-of-dir' "$WORK/blocker/sub"

# An unwritable directory: mkdir succeeds nowhere, open must fail.
mkdir -p "$WORK/ro"
chmod 500 "$WORK/ro"
if [ -w "$WORK/ro" ]; then
  printf '  skip  unwritable-dir (running with write override, e.g. as root)\n'
else
  check_refused 'unwritable-dir' "$WORK/ro"
fi
chmod 700 "$WORK/ro" 2>/dev/null || true

# The log path is a directory, so opening it for append must fail.
mkdir -p "$WORK/dirlog/step-x.log"
check_refused 'log-path-is-a-directory' "$WORK/dirlog"

# An empty RAW_DIR must also refuse rather than writing somewhere arbitrary.
check_refused 'empty-raw-dir' ''

# --- 7. formatter/python unavailable: still fully captured -------------
#
# The fallback must keep the complete record and the exit code; only the
# presentation is lost.
STUBBIN="$WORK/stubbin"
mkdir -p "$STUBBIN"
# A PATH holding every tool render_step needs EXCEPT python3, so the only
# thing missing is the formatter's interpreter. Omitting something else would
# test the wrong failure.
for tool in sh cat tee mktemp rm mkdir printf grep sed awk env chmod ls; do
  _p=$(command -v "$tool" 2>/dev/null) && ln -sf "$_p" "$STUBBIN/$tool" 2>/dev/null || true
done
# Guard the fixture itself: without these the cases below would "fail" for a
# reason that has nothing to do with the formatter being absent.
for tool in mkdir tee cat mktemp; do
  [ -x "$STUBBIN/$tool" ] || { echo "FAIL: fixture PATH lacks $tool"; exit 1; }
done
[ -x "$STUBBIN/python3" ] && { echo 'FAIL: fixture PATH must not have python3'; exit 1; }

run_no_python() {
  _name=$1; shift
  rm -f "$WORK/marker.flag"
  _code=0
  env -i PATH="$STUBBIN" HOME="$HOME" TMPDIR="${TMPDIR:-/tmp}" \
    RENDER_HERE="$HERE" RENDER_RAW_DIR="$WORK/raw-nopy" \
    sh "$WORK/harness.sh" render_step "$_name" "$@" >"$WORK/$_name.out" 2>&1 || _code=$?
  return "$_code"
}

NOPY=0
run_no_python nopy-ok "$WORK/ok-cmd" || NOPY=$?
if [ "$NOPY" -eq 0 ]; then ok 'no-python3: step still succeeds'
else no "no-python3: step still succeeds (exit $NOPY)"; fi
if [ -f "$WORK/raw-nopy/nopy-ok.log" ]; then
  ok 'no-python3: raw log still written'
  if grep -q '\[DA.Internal.Prelude:557\]' "$WORK/raw-nopy/nopy-ok.log"; then
    ok 'no-python3: raw log holds the complete original stream'
  else
    no 'no-python3: raw log holds the complete original stream'
  fi
else
  no 'no-python3: raw log still written'
fi
if grep -q 'Full technical log' "$WORK/nopy-ok.out"; then ok 'no-python3: path still printed'
else no 'no-python3: path still printed'; fi
if grep -q 'formatter unavailable' "$WORK/nopy-ok.out"; then ok 'no-python3: says so plainly'
else no 'no-python3: says so plainly'; fi
# Output still reaches the terminal, unformatted.
if grep -q 'PROPOSAL' "$WORK/nopy-ok.out"; then ok 'no-python3: output still shown'
else no 'no-python3: output still shown'; fi

NOPYFAIL=0
run_no_python nopy-fail "$WORK/fail-cmd" || NOPYFAIL=$?
if [ "$NOPYFAIL" -eq 7 ]; then ok 'no-python3: exit code 7 still propagated'
else no "no-python3: exit code 7 still propagated (got $NOPYFAIL)"; fi

# A missing formatter file, with python3 present.
MISSING=0
RENDER_HERE="$WORK/no-such-dir" RENDER_RAW_DIR="$WORK/raw-nofmt" \
  sh "$WORK/harness.sh" render_step nofmt "$WORK/fail-cmd" >"$WORK/nofmt.out" 2>&1 || MISSING=$?
if [ "$MISSING" -eq 7 ]; then ok 'missing formatter: exit code 7 propagated'
else no "missing formatter: exit code 7 propagated (got $MISSING)"; fi
if [ -s "$WORK/raw-nofmt/nofmt.log" ]; then ok 'missing formatter: raw log still written'
else no 'missing formatter: raw log still written'; fi

printf '\npassed %s, failed %s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
