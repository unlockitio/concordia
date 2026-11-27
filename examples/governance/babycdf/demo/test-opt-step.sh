#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Offline tests for the optional-round dispatch in run-demo.sh: prerequisites,
# refusal codes, marker isolation from the main steps, `all` ignoring optional
# rounds, and cleanup coverage. No ledger, container, network or dpm run: dpm
# is stubbed and only the orchestration is exercised.
#
#   sh examples/governance/babycdf/demo/test-opt-step.sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/babycdf-opt-test-XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
PASS=0
FAIL=0
ok() { PASS=$((PASS + 1)); printf '  ok    %s\n' "$1"; }
no() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

# Harness: the real run_opt_step and render_step, with a stub dpm/python3 and a
# throwaway STATE. Nothing from the user's demo is touched.
STATE="$WORK/state"
mkdir -p "$STATE" "$WORK/bin" "$WORK/raw"
cat > "$WORK/bin/dpm" <<'STUB'
#!/bin/sh
# Record the invocation and write the output file the runner expects.
printf '%s\n' "$*" >> "$STUBLOG"
for a in "$@"; do
  case "$PREV" in --output-file) printf '"stub"\n' > "$a" ;; esac
  PREV=$a
done
exit 0
STUB
chmod +x "$WORK/bin/dpm"
# The real package-gate.py runs here, so the fixture supplies what it reads:
# a demo-dar.json whose sha256 matches a stub DAR file. This keeps the gate in
# the tested path rather than stubbing it away.
STUB_DAR="$WORK/stub.dar"
printf 'stub dar bytes\n' > "$STUB_DAR"
STUB_SHA=$(shasum -a 256 "$STUB_DAR" | awk '{print $1}')
seed_gate_fixture() {
  printf '{"main_package_id":"stubpkg","sha256":"%s"}\n' "$STUB_SHA" > "$STATE/demo-dar.json"
  printf '"stub-run"\n' > "$STATE/run-id.json"
}
seed_gate_fixture

build_harness() {
  # A private HERE: the real scripts the runner calls, plus a stub DAR at the
  # exact path the package gate and dpm arguments expect.
  mkdir -p "$WORK/here/.daml/dist"
  cp "$HERE/package-gate.py" "$WORK/here/package-gate.py"
  cp "$HERE/format-log.py" "$WORK/here/format-log.py"
  cp "$STUB_DAR" "$WORK/here/.daml/dist/cap-example-babycdf-demo-0.1.0.dar"
  cat > "$WORK/harness.sh" <<HARNESS
set -eu
HERE=$WORK/here
STATE=$STATE
RAW_DIR=$WORK/raw
LEDGER_PORT=41099
PAUSE=0
# Exact mock for the main step's new process-presence precondition only.
compose() { case "\$*" in 'ps --status running -q intake') echo synthetic-intake;; 'logs --no-color --tail 30 intake') echo '[DISCOVERY] synthetic test report';; *) return 2;; esac; }
export STUBLOG=$WORK/dpm-calls.txt
babycdf_build_root() { BABYCDF_BUILD_ROOT=$WORK/build; mkdir -p "\$BABYCDF_BUILD_ROOT"; }
HARNESS
  awk '/^raw_log_path\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
  awk '/^render_step\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
  awk '/^agent_log_summary\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
  cp "$HERE/summarize-agent-log.py" "$WORK/here/summarize-agent-log.py"
  awk '/^run_step\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
  awk '/^run_opt_step\(\) \{/,/^\}/' "$HERE/run-demo.sh" >> "$WORK/harness.sh"
  printf '\n"$@"\n' >> "$WORK/harness.sh"
  for fn in raw_log_path render_step run_step run_opt_step; do
    grep -q "^$fn() {" "$WORK/harness.sh" || { echo "FAIL: $fn not extracted"; exit 1; }
  done
}
build_harness

run_h() {
  : > "$WORK/dpm-calls.txt"
  PATH="$WORK/bin:$PATH" sh "$WORK/harness.sh" "$@"
}

# --- 1. prerequisite: refuses before step 2 ----------------------------
rm -f "$STATE"/*
CODE=0
run_h run_opt_step 1 >"$WORK/pre.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 2 ]; then ok 'opt-step 1 before step 2 exits 2'
else no "opt-step 1 before step 2 exits 2 (got $CODE)"; fi
if grep -q 'needs step 2' "$WORK/pre.out"; then ok 'explains the step-2 prerequisite'
else no 'explains the step-2 prerequisite'; fi
if [ -s "$WORK/dpm-calls.txt" ]; then no 'dpm must not be invoked when refused'
else ok 'dpm never invoked when refused'; fi
if [ -e "$STATE/opt-step-1.attempted" ]; then no 'no marker written on refusal'
else ok 'no marker written on refusal'; fi

# --- 2. unknown / missing optional round -------------------------------
seed_gate_fixture
printf '"x"\n' > "$STATE/step-2.json"
CODE=0; run_h run_opt_step 99 >"$WORK/unk.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 2 ] && grep -q 'Unknown optional round' "$WORK/unk.out"; then
  ok 'unknown optional round exits 2 with a clear message'
else no "unknown optional round exits 2 (got $CODE)"; fi
CODE=0; run_h run_opt_step '' >"$WORK/none.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 2 ] && grep -q 'Usage' "$WORK/none.out"; then ok 'missing argument exits 2 with usage'
else no "missing argument exits 2 with usage (got $CODE)"; fi

# --- 3. happy path: runs, writes its own marker and result -------------
CODE=0; run_h run_opt_step 1 >"$WORK/run.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 0 ]; then ok 'opt-step 1 runs after step 2'
else no "opt-step 1 runs after step 2 (exit $CODE)"; fi
if grep -q 'staleRuleChange' "$WORK/dpm-calls.txt"; then ok 'invokes the staleRuleChange script'
else no 'invokes the staleRuleChange script'; fi
[ -f "$STATE/opt-step-1.json" ] && ok 'writes opt-step-1.json' || no 'writes opt-step-1.json'
[ -f "$STATE/opt-step-1.attempted" ] && ok 'writes opt-step-1.attempted' || no 'writes opt-step-1.attempted'
[ -f "$WORK/raw/opt-step-1.log" ] && ok 'uses its own raw log name' || no 'uses its own raw log name'
[ -f "$WORK/raw/step-1.log" ] && no 'must not collide with step-1 raw log' || ok 'no collision with step-1 raw log'

# --- 4. marker isolation: optional must not satisfy or block a main step
#
# The main steps key off step-N.json / step-N.attempted only. An optional
# marker must neither count as a main prerequisite nor block a main step.
if [ -e "$STATE/step-1.attempted" ] || [ -e "$STATE/step-3.attempted" ]; then
  no 'optional round must not create main-step markers'
else
  ok 'optional round creates no main-step markers'
fi
# Main step 3 needs step-2.json, which exists; it must still run even though
# the optional round has already run.
CODE=0; run_h run_step 3 >"$WORK/main3.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 2 ] && [ ! -e "$STATE/step-3.attempted" ]; then ok 'retired main step 3 refuses before mutation'
else no "retired main step 3 must refuse (exit $CODE)"; fi
if grep -q 'proposalVote' "$WORK/dpm-calls.txt"; then no 'retired source step must not invoke proposalVote'
else ok 'retired source step invokes no source voting script'; fi

# --- 5. once per network -----------------------------------------------
CODE=0; run_h run_opt_step 1 >"$WORK/again.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 2 ] && grep -q 'already ran or was attempted' "$WORK/again.out"; then
  ok 're-running the optional round exits 2'
else no "re-running the optional round exits 2 (got $CODE)"; fi
if grep -q 'down then up' "$WORK/again.out"; then ok 'gives the same reset guidance as a main step'
else no 'gives the same reset guidance as a main step'; fi

# --- 6. partial failure: marker stays, result does not -----------------
rm -f "$STATE/opt-step-1.json" "$STATE/opt-step-1.attempted"
cat > "$WORK/bin/dpm" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$STUBLOG"
printf 'simulated script failure\n' >&2
exit 7
STUB
chmod +x "$WORK/bin/dpm"
CODE=0; run_h run_opt_step 1 >"$WORK/fail.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 7 ]; then ok 'script failure propagates exit 7'
else no "script failure propagates exit 7 (got $CODE)"; fi
[ -e "$STATE/opt-step-1.attempted" ] && ok 'attempt marker retained after failure' \
  || no 'attempt marker retained after failure'
[ -e "$STATE/opt-step-1.json" ] && no 'no result file on failure' || ok 'no result file on failure'
CODE=0; run_h run_opt_step 1 >"$WORK/after-fail.out" 2>&1 || CODE=$?
if [ "$CODE" -eq 2 ]; then ok 'after a failed attempt it refuses until down/up'
else no "after a failed attempt it refuses (got $CODE)"; fi

# --- 7. `all` ignores the optional rounds ------------------------------
if grep -q 'Optional rounds are not part of all' "$HERE/run-demo.sh"; then
  ok 'all announces that optional rounds are excluded'
else no 'all announces that optional rounds are excluded'; fi
# The `all` block may MENTION optional rounds (it tells the user they are
# excluded); what it must not do is CALL one. Match an invocation, not a word.
if awk '/^  all\)/,/^    ;;/' "$HERE/run-demo.sh" \
     | grep -vE "^\s*printf|^\s*echo|^\s*#" | grep -q 'run_opt_step'; then
  no 'all must not invoke an optional round'
else ok 'all does not invoke an optional round'
fi
# `all` runs the Architecture rule vote and stops there. It must not reach a
# source vote or a coupon allocation, which are explicit operator commands.
# This asserts the EFFECTIVE behaviour: the former `for n in 2 3 4 5` loop was
# unreachable dead code after `run_step 2; exit $?` and advertised an
# aggregate journey the automatic-vote policy forbids.
ALL_BLOCK=$(awk '/^  all\)/,/^    ;;/' "$HERE/run-demo.sh")
if printf '%s' "$ALL_BLOCK" | grep -q 'run_step 2' \
   && printf '%s' "$ALL_BLOCK" | grep -q 'exit \$?'; then
  ok 'all runs step 2 and stops there'
else no 'all runs step 2 and stops there'; fi
if printf '%s' "$ALL_BLOCK" | grep -vE "^\s*printf|^\s*echo|^\s*#" \
     | grep -qE 'run_step [345]|for n in'; then
  no 'all must not run a source vote or coupon allocation'
else ok 'all runs no source vote or coupon allocation'; fi

# --- 8. cleanup covers the optional markers ----------------------------
for target in 'opt-step-1.json' 'opt-step-1.attempted' 'opt-step-1-next-' 'opt-step-2.json' 'opt-step-2.attempted' 'opt-step-2-next-' 'opt-step-3.json' 'opt-step-3.attempted' 'opt-step-3-next-' 'opt-step-4.json' 'opt-step-4.attempted' 'opt-step-4-next-' 'opt-step-5.json' 'opt-step-5.attempted' 'opt-step-5-next-'; do
  COUNT=$(grep -c -- "$target" "$HERE/run-demo.sh" || true)
  if [ "$COUNT" -ge 2 ]; then ok "cleanup covers $target in both up and down"
  else no "cleanup covers $target in both up and down (found $COUNT)"; fi
done

# --- 9. dispatcher exposes the command --------------------------------
if grep -qE '^  opt-step\) run_opt_step' "$HERE/run-demo.sh"; then ok 'opt-step is dispatched'
else no 'opt-step is dispatched'; fi
if grep -q 'opt-step N' "$HERE/run-demo.sh"; then ok 'help documents opt-step'
else no 'help documents opt-step'; fi

# Standalone optionals use bootstrap only, and shuffled order cannot affect
# the main prerequisite. Restore the successful stub after failure tests.
cat > "$WORK/bin/dpm" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$STUBLOG"
PREV=''
for a in "$@"; do
  case "$PREV" in --output-file) printf '"stub"\n' > "$a" ;; esac
  PREV=$a
done
STUB
chmod +x "$WORK/bin/dpm"
for order in '2' '3' '4' '5' '5 3 2 4'; do
  STATE="$WORK/state-$(echo "$order" | tr ' ' '-')"
  mkdir -p "$STATE"
  seed_gate_fixture
  printf '"bootstrap"\n' > "$STATE/bootstrap.json"
  build_harness
  for n in $order; do
    CODE=0; run_h run_opt_step "$n" >"$WORK/optional-$n.out" 2>&1 || CODE=$?
    if [ "$CODE" -eq 2 ] && [ ! -f "$STATE/opt-step-$n.json" ] && [ ! -f "$STATE/opt-step-$n.attempted" ] && [ ! -s "$WORK/dpm-calls.txt" ]; then
      ok "retired source optional $n refuses before mutation in order [$order]"
    else no "retired source optional $n must not submit (exit $CODE)"; fi
    [ ! -e "$STATE/step-2.json" ] && ok 'optional does not advance main markers' || no 'optional advanced main markers'
  done
  CODE=0; run_h run_step 6 >"$WORK/retired.out" 2>&1 || CODE=$?
  if [ "$CODE" -eq 2 ] && [ ! -s "$WORK/dpm-calls.txt" ]; then ok 'retired step 6 fails closed without dpm'
  else no 'retired step 6 fails closed'; fi
done

printf '\npassed %s, failed %s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1

# Integrated source polling is not another optional voting step.
grep -q -- 'Intake --integrated-demo' "$HERE/compose/agents/entrypoint.sh"
if grep -q -- 'GitVoteBallot\|--mirror-ballots\|--scripted-ballots' "$HERE/compose/agents/entrypoint.sh"; then
  echo 'FAIL: integrated launcher must not submit source ballots'; exit 1
fi
