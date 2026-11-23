#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Offline tests for ledger.sh. Every test runs against a stub `docker` on PATH
# that records its invocations and replays fixtures, so this suite NEVER
# contacts the live ledger, never starts a container, and never mutates
# anything. Run it from anywhere:
#
#   sh examples/governance/babycdf/demo/test-ledger.sh
set -eu

# shellcheck disable=SC1007  # `CDPATH= cd` is the intended env prefix
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LEDGER="$HERE/ledger.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/test-ledger.XXXXXX")
trap 'rm -rf "$WORK"' EXIT HUP INT TERM

PASS=0
FAIL=0

ok() {
  PASS=$((PASS + 1))
  printf '  ok    %s\n' "$1"
}

bad() {
  FAIL=$((FAIL + 1))
  printf '  FAIL  %s\n' "$1"
  [ $# -ge 2 ] && printf '        %s\n' "$2"
}

check_eq() {
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$3', got '$2'"; fi
}

check_contains() {
  if printf '%s' "$2" | grep -q -- "$3"; then ok "$1"; else
    bad "$1" "expected to contain '$3', got: $(printf '%s' "$2" | head -c 200)"
  fi
}

check_not_contains() {
  if printf '%s' "$2" | grep -q -- "$3"; then
    bad "$1" "expected NOT to contain '$3'"
  else ok "$1"; fi
}

# ---------------------------------------------------------------- the harness

# A stub `docker` that appends every invocation to $WORK/docker-calls and
# replays a fixture chosen by $STUB_MODE. The real docker is never on PATH.
make_stub_docker() {
  cat >"$WORK/bin/docker" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"$DOCKER_CALLS"
# `docker compose -p <project> ps -q <svc>` answers with a container id so the
# demo looks up; the argument vector is otherwise passed through untouched.
case "$*" in
  'compose -p '*' ps -q canton') printf 'stub-container-id\n'; exit 0 ;;
esac
# `docker image inspect` keeps the grpc client probe quiet.
case "$1 $2" in
  'image inspect') exit 0 ;;
esac
# The handler receives the full argument vector, so it can tell a GET from a
# POST and which endpoint was addressed.
exec "$STUB_HANDLER" "$@"
STUB
  chmod 755 "$WORK/bin/docker"
}

# Rebuilds a clean sandbox: stub PATH, fake state files, empty call log.
reset_env() {
  rm -rf "${WORK:?}/bin" "${WORK:?}/demo"
  mkdir -p "$WORK/bin" "$WORK/demo/compose/state"
  cp "$LEDGER" "$WORK/demo/ledger.sh"
  cp "$HERE/http-acs-pages.py" "$WORK/demo/http-acs-pages.py"
  chmod 755 "$WORK/demo/ledger.sh"
  cat >"$WORK/demo/compose/state/bootstrap.json" <<'JSON'
{"opener":"opener-1::1220aa","member":"member-a::1220aa","governor":"00gov","registry":"00reg","rule":"00rule","userName":"babycdf-agent","step":1}
JSON
  printf '41000 member-a::1220aa 00pkg 00gov babycdf-agent\n' >"$WORK/demo/compose/state/readback"
  : >"$WORK/docker-calls"
  make_stub_docker
  DOCKER_CALLS="$WORK/docker-calls"
  export DOCKER_CALLS
  # jq and the core utilities stay real; only docker is stubbed.
  PATH="$WORK/bin:/usr/bin:/bin:/usr/sbin:/sbin"
  export PATH
}

# Runs ledger.sh under the stub sandbox. `set -e` must not abort this script
# when the subject exits non-zero, and a command substitution would swallow
# the status, so the output goes to a file and the status into $RC.
# Sets the globals $out and $RC. Command substitution is deliberately avoided:
# it runs in a subshell, so an exit status assigned there would be lost, and
# `set -e` would abort this suite the moment the subject exits non-zero.
RC=0
out=''
run_ledger() {
  RC=0
  ( cd "$WORK/demo" && sh ./ledger.sh "$@" ) >"$WORK/out" 2>&1 || RC=$?
  out=$(cat "$WORK/out")
}

docker_called() {
  [ -s "$WORK/docker-calls" ]
}

echo 'ledger.sh offline test suite (stubbed docker; the live ledger is never contacted)'

# ------------------------------------------------- 1. allowlist, read-only

echo
echo 'Allowlist: disallowed operations are refused before docker runs'

# Each of these must exit 2 AND leave the docker call log empty.
for case_spec in \
  'grpc|CommandService/SubmitAndWait|submit' \
  'grpc|CommandSubmissionService/Submit|async submit' \
  'grpc|admin.PartyManagementService/AllocateParty|allocate party' \
  'grpc|admin.PackageManagementService/UploadDarFile|DAR upload' \
  'grpc|admin.UserManagementService/CreateUser|user creation' \
  'grpc|admin.UserManagementService/GrantUserRights|rights grant' \
  'grpc|interactive.InteractiveSubmissionService/ExecuteSubmission|interactive submission' \
  'grpc|admin.ParticipantPruningService/Prune|pruning'
do
  OLDIFS=$IFS
  IFS='|'
  # shellcheck disable=SC2086  # deliberate IFS split of the fixture record
  set -- $case_spec
  IFS=$OLDIFS
  transport=$1; method=$2; label=$3
  reset_env
  run_ledger "$transport" raw "$method" '{}'
  rc=$RC
  check_eq "refuses $label (exit 2)" "$rc" "2"
  check_contains "refuses $label (message)" "$out" "not in the read-only allowlist"
  if docker_called; then
    bad "refuses $label BEFORE docker" "docker was invoked: $(cat "$WORK/docker-calls")"
  else
    ok "refuses $label before docker is invoked"
  fi
done

# HTTP POST to a mutating endpoint.
for case_spec in \
  '/v2/commands/submit-and-wait|command submission' \
  '/v2/dars|DAR upload' \
  '/v2/parties/external/allocate|external party allocation' \
  '/v2/interactive-submission/execute|interactive submission'
do
  OLDIFS=$IFS
  IFS='|'
  # shellcheck disable=SC2086  # deliberate IFS split of the fixture record
  set -- $case_spec
  IFS=$OLDIFS
  path=$1; label=$2
  reset_env
  run_ledger http raw "$path" '{}'
  rc=$RC
  check_eq "refuses POST $label (exit 2)" "$rc" "2"
  if docker_called; then
    bad "refuses POST $label BEFORE docker" "docker was invoked"
  else
    ok "refuses POST $label before docker is invoked"
  fi
done

# A GET outside the allowlist is refused too.
reset_env
run_ledger http raw /v2/commands/completions
check_eq 'refuses non-allowlisted GET (exit 2)' "$RC" "2"
if docker_called; then bad 'refuses GET before docker'; else ok 'refuses non-allowlisted GET before docker'; fi

# Allowed operations DO reach docker (proving the allowlist is not refusing all).
echo
echo 'Allowlist: permitted operations are dispatched'
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
printf '{"offset":"91"}\n91'
H
chmod 755 "$WORK/bin/handler"
STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http raw /v2/version
if docker_called; then ok 'allowed GET /v2/version reaches docker'; else bad 'allowed GET reached docker' 'no docker call recorded'; fi

reset_env
STUB_HANDLER="$WORK/bin/handler2"; export STUB_HANDLER
cat >"$WORK/bin/handler2" <<'H'
#!/bin/sh
printf '{"offset":"91"}'
H
chmod 755 "$WORK/bin/handler2"
run_ledger grpc raw StateService/GetLedgerEnd '{}'
if docker_called; then ok 'allowed gRPC GetLedgerEnd reaches docker'; else bad 'allowed gRPC reached docker' 'no docker call'; fi

# ------------------------------------------------------ 2. paging completeness

echo
echo 'Paging: HTTP update stream is assembled completely beyond one page'

# Fixture: 250 transactions at offsets 2,4,...,500, each with a CreatedEvent,
# plus an ArchivedEvent at offset 400, far beyond the first page of 100.
# The handler honours beginExclusive and ?limit, exactly like Canton's take().
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
# Distinguish the calls ledger.sh makes: ledger-end GET, then update POSTs.
# GETs must not block reading stdin, so the body is read only for POSTs.
case "$*" in
  *'/v2/state/ledger-end'*) printf '{"offset":500}\n200'; exit 0 ;;
  *'/v2/state/active-contracts'*) printf '[]\n200'; exit 0 ;;
esac
body=$(cat 2>/dev/null || true)
begin=$(printf '%s' "$body" | jq -r '.beginExclusive // 0')
limit=100
for a in "$@"; do
  case "$a" in *limit=*) limit=${a##*limit=}; limit=${limit%%&*} ;; esac
done
jq -n --argjson b "$begin" --argjson lim "$limit" '
  [ range(1; 251) | . * 2 ]
  | map(select(. > $b))
  | .[0:$lim]
  | map({ update: { Transaction: { value: {
      offset: .,
      events: ( [ { CreatedEvent: { contractId: ("cid" + (.|tostring)), packageName: "pkg",
                                    templateId: ("p:M:T" + (.|tostring)), offset: .,
                                    createArgument: {} } } ]
                + (if . == 400 then [ { ArchivedEvent: { contractId: "cid400", packageName: "pkg",
                                                        templateId: "p:M:TArchived", offset: . } } ] else [] end) )
    } } } })'
printf '\n200'
H
chmod 755 "$WORK/bin/handler"
STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER

run_ledger http history
rc=$RC
check_eq 'paged history exits 0' "$rc" "0"
lines=$(printf '%s' "$out" | grep -c '^  offset ' || true)
check_eq 'paged history returns all 250 transactions' "$lines" "250"
check_contains 'paged history includes the last offset (500)' "$out" 'offset 500'
check_contains 'paged history includes an archive beyond page 1 (400)' "$out" 'offset 400  Created,Archived'

# The same fixture through `archived` must find the far-page archive.
run_ledger http archived
check_contains 'archived finds the contract archived at offset 400' "$out" 'archived@400'
check_eq 'archived exits 0' "$RC" "0"

# A smaller page size must produce the identical total (more pages, same data).
out=$(cd "$WORK/demo" && BABYCDF_PAGE_LIMIT=7 sh ./ledger.sh http history 2>&1)
lines=$(printf '%s' "$out" | grep -c '^  offset ' || true)
check_eq 'page size 7 still returns all 250 transactions' "$lines" "250"

# Paging must refuse to loop when the server stops advancing the offset.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
case "$*" in
  *'/v2/state/ledger-end'*) printf '{"offset":500}\n200'; exit 0 ;;
esac
# Always returns the same offset, never advancing.
printf '[{"update":{"Transaction":{"value":{"offset":2,"events":[{"CreatedEvent":{"contractId":"c","packageName":"p","templateId":"a:b:c","offset":2,"createArgument":{}}}]}}}}]\n200'
H
chmod 755 "$WORK/bin/handler"
STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http history
rc=$RC
check_contains 'non-advancing pages fail loudly' "$out" 'no progress'
if [ "$rc" = "0" ]; then bad 'non-advancing pages exit non-zero' "got 0"; else ok 'non-advancing pages exit non-zero'; fi

echo
echo 'Paging: gRPC collects the full bounded stream'
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
body=$(cat 2>/dev/null || true)
case "$*" in
  *GetLedgerEnd*) printf '{"offset":"500"}'; exit 0 ;;
esac
begin=$(printf '%s' "$body" | jq -r '.begin_exclusive // 0')
# grpcurl emits concatenated objects, one per streamed message.
jq -n -c --argjson b "$begin" '
  [ range(1; 251) | . * 2 ] | map(select(. > $b)) | .[0:100]
  | .[] | { transaction: { offset: (.|tostring),
      events: [ { created: { contract_id: ("cid" + (.|tostring)), package_name: "pkg",
                             template_id: {entity_name: "T"}, offset: (.|tostring),
                             create_arguments: {fields: []} } } ] } }'
H
chmod 755 "$WORK/bin/handler"
STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger grpc history
lines=$(printf '%s' "$out" | grep -c '^  offset ' || true)
check_eq 'gRPC history returns all 250 transactions' "$lines" "250"
check_contains 'gRPC history reaches the last offset' "$out" 'offset 500'

# ------------------------------------------------- 3. transport failure modes

echo
echo 'Transport failures never become successful empty results'

# (a) HTTP 500 from the endpoint.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
printf '{"error":"boom"}\n500'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http status
rc=$RC
if [ "$rc" = "0" ]; then bad 'HTTP 500 exits non-zero' 'got exit 0'; else ok 'HTTP 500 exits non-zero'; fi
check_contains 'HTTP 500 is reported' "$out" 'HTTP 500'

# (b) HTTP 404 on a query POST, as a wrong path would give.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
case "$*" in
  *ledger-end*) printf '{"offset":91}\n200'; exit 0 ;;
esac
printf '{"errors":["nope"],"status":404}\n404'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http contracts
rc=$RC
if [ "$rc" = "0" ]; then bad 'HTTP 404 exits non-zero' 'got exit 0'; else ok 'HTTP 404 exits non-zero'; fi
check_not_contains 'HTTP 404 does not print an empty inventory' "$out" 'Active contracts visible'
check_contains 'HTTP 404 is reported' "$out" 'HTTP 404'

# (c) curl itself fails (connection refused).
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
echo 'curl: (7) Failed to connect' >&2
exit 7
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http status
rc=$RC
if [ "$rc" = "0" ]; then bad 'curl failure exits non-zero' 'got exit 0'; else ok 'curl failure exits non-zero'; fi
check_contains 'curl failure is reported' "$out" 'transport failed'

# (d) Malformed, non-JSON HTTP body with a 200 status.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
printf '<html>proxy error</html>\n200'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http status
rc=$RC
if [ "$rc" = "0" ]; then bad 'malformed JSON exits non-zero' 'got exit 0'; else ok 'malformed JSON exits non-zero'; fi
check_contains 'malformed JSON is reported' "$out" 'Malformed JSON'

# (e) grpcurl exits non-zero (dial failure).
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
echo 'Failed to dial target host: connection refused' >&2
exit 1
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger grpc status
rc=$RC
if [ "$rc" = "0" ]; then bad 'grpcurl failure exits non-zero' 'got exit 0'; else ok 'grpcurl failure exits non-zero'; fi
check_contains 'grpcurl failure is reported' "$out" 'gRPC call'

# (f) grpcurl exits 0 but emits garbage.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
printf 'not json at all'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger grpc status
rc=$RC
if [ "$rc" = "0" ]; then bad 'garbage gRPC output exits non-zero' 'got exit 0'; else ok 'garbage gRPC output exits non-zero'; fi
check_contains 'garbage gRPC output is reported' "$out" 'Malformed JSON'

# (g) A non-numeric ledger end must not silently become offset 0.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
cat >/dev/null 2>&1 || true
printf '{"unexpected":true}\n200'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http contracts
rc=$RC
if [ "$rc" = "0" ]; then bad 'missing ledger end exits non-zero' 'got exit 0'; else ok 'missing ledger end exits non-zero'; fi
check_contains 'missing ledger end is reported' "$out" 'numeric ledger end'

# ------------------------------- 3b. offset checkpoints in the update stream

echo
echo 'Update pages carry checkpoints as well as transactions'

# Canton 3.5.11 GetUpdatesResponse.update is a oneOf: Transaction,
# OffsetCheckpoint, Reassignment or TopologyTransaction. A live run of this
# demo returns an OffsetCheckpoint at offset 160, beyond the last transaction
# at 158, so these shapes are real, not hypothetical.
#
# Fixture layout, paged at 2 responses per page:
#   offsets 2,4    transactions (one create each)
#   offset  6      checkpoint only  (intermediate: must still advance)
#   offset  8      transaction carrying the ARCHIVE of the contract from 2
#   offset 10      transaction with an empty event list
#   offset 12      checkpoint only  (final: completes the range)
write_checkpoint_handler() {
  cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
case "$*" in
  *'/v2/state/ledger-end'*) printf '{"offset":12}\n200'; exit 0 ;;
  *'/v2/state/active-contracts'*) printf '[]\n200'; exit 0 ;;
esac
body=$(cat 2>/dev/null || true)
begin=$(printf '%s' "$body" | jq -r '.beginExclusive // 0')
limit=2
for a in "$@"; do
  case "$a" in *limit=*) limit=${a##*limit=}; limit=${limit%%&*} ;; esac
done
jq -n --argjson b "$begin" --argjson lim "$limit" '
  [ { off: 2,  kind: "tx",   events: [ { CreatedEvent: { contractId: "c2", packageName: "pkg",
        templateId: "p:M:Thing", offset: 2, createArgument: {} } } ] },
    { off: 4,  kind: "tx",   events: [ { CreatedEvent: { contractId: "c4", packageName: "pkg",
        templateId: "p:M:Thing", offset: 4, createArgument: {} } } ] },
    { off: 6,  kind: "ckpt" },
    { off: 8,  kind: "tx",   events: [ { ArchivedEvent: { contractId: "c2", packageName: "pkg",
        templateId: "p:M:Thing", offset: 8 } } ] },
    { off: 10, kind: "tx",   events: [] },
    { off: 12, kind: "ckpt" } ]
  | map(select(.off > $b)) | .[0:$lim]
  | map(if .kind == "ckpt"
        then { update: { OffsetCheckpoint: { value: { offset: .off, synchronizerTimes: [] } } } }
        else { update: { Transaction: { value: { offset: .off, events: .events } } } }
        end)'
printf '\n200'
H
  chmod 755 "$WORK/bin/handler"
  STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
}

reset_env
write_checkpoint_handler
run_ledger http history
check_eq 'checkpointed stream exits 0' "$RC" "0"
check_contains 'transaction before the checkpoint is kept' "$out" 'offset 2'
check_contains 'transaction after an intermediate checkpoint is reached' "$out" 'offset 8'
check_not_contains 'checkpoint offsets are not reported as events' "$out" 'offset 6'
lines=$(printf '%s' "$out" | grep -c '^  offset ' || true)
check_eq 'only event-bearing transactions are listed' "$lines" "3"

# The archive at offset 8 sits beyond a checkpoint-only page: reaching it
# proves the cursor advanced on checkpoint metadata, not on event offsets.
reset_env
write_checkpoint_handler
run_ledger http archived
check_eq 'archived across a checkpoint exits 0' "$RC" "0"
check_contains 'archive beyond a checkpoint-only page is found' "$out" 'created@2'
check_contains 'archive beyond a checkpoint-only page reports its offset' "$out" 'archived@8'

# Same fixture over gRPC, where the oneof arm is offset_checkpoint.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
case "$*" in
  *GetLedgerEnd*) printf '{"offset":"12"}'; exit 0 ;;
  *GetActiveContracts*) cat >/dev/null 2>&1 || true; exit 0 ;;
esac
body=$(cat 2>/dev/null || true)
begin=$(printf '%s' "$body" | jq -r '.begin_exclusive // 0')
jq -n -c --argjson b "$begin" '
  [ { off: 2,  kind: "tx",   events: [ { created: { contract_id: "c2", package_name: "pkg",
        template_id: { entity_name: "Thing" }, offset: "2", create_arguments: { fields: [] } } } ] },
    { off: 4,  kind: "tx",   events: [ { created: { contract_id: "c4", package_name: "pkg",
        template_id: { entity_name: "Thing" }, offset: "4", create_arguments: { fields: [] } } } ] },
    { off: 6,  kind: "ckpt" },
    { off: 8,  kind: "tx",   events: [ { archived: { contract_id: "c2", package_name: "pkg",
        template_id: { entity_name: "Thing" }, offset: "8" } } ] },
    { off: 10, kind: "tx",   events: [] },
    { off: 12, kind: "ckpt" } ]
  | map(select(.off > $b)) | .[0:2]
  | .[]
  | if .kind == "ckpt"
    then { offset_checkpoint: { offset: (.off | tostring), synchronizer_times: [] } }
    else { transaction: { offset: (.off | tostring), events: .events } }
    end'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger grpc history
check_eq 'gRPC checkpointed stream exits 0' "$RC" "0"
check_contains 'gRPC reaches past an intermediate checkpoint' "$out" 'offset 8'
GRPC_CKPT=$out
reset_env
write_checkpoint_handler
run_ledger http history
if [ "$out" = "$GRPC_CKPT" ]; then ok 'http and grpc agree across checkpoints'
else bad 'http and grpc agree across checkpoints' 'outputs differ'; fi

# A page with no updates at all completes the range without an error.
reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
case "$*" in
  *'/v2/state/ledger-end'*) printf '{"offset":99}\n200'; exit 0 ;;
esac
cat >/dev/null 2>&1 || true
printf '[]\n200'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http history
check_eq 'an empty page completes the range' "$RC" "0"
check_contains 'empty range still prints its header' "$out" 'Transactions visible to the opener'

# ------------------------------------- 3c. raw update streams must be bounded

echo
echo 'raw update streams are bounded to the captured ledger end'

bounded_handler() {
  reset_env
  cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
case "$*" in
  *'/v2/state/ledger-end'*) printf '{"offset":160}\n200'; exit 0 ;;
  *GetLedgerEnd*) printf '{"offset":"160"}'; exit 0 ;;
esac
body=$(cat 2>/dev/null || true)
# Echo the body back so the test can assert what the server would have seen.
printf '%s' "$body" >"$SEEN"
printf '[]\n200'
H
  chmod 755 "$WORK/bin/handler"
  SEEN="$WORK/seen.json"; export SEEN
  STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
}

bounded_handler
run_ledger http raw /v2/updates '{"beginExclusive":0,"updateFormat":{}}'
check_eq 'missing endInclusive is accepted' "$RC" "0"
seen=$(cat "$WORK/seen.json" 2>/dev/null || echo '{}')
check_eq 'missing endInclusive is injected from the captured end' \
  "$(printf '%s' "$seen" | jq -r '.endInclusive')" "160"

bounded_handler
run_ledger http raw /v2/updates '{}'
check_eq 'an empty body is bounded too' \
  "$(cat "$WORK/seen.json" 2>/dev/null | jq -r '.endInclusive')" "160"

bounded_handler
run_ledger grpc raw UpdateService/GetUpdates '{"begin_exclusive":0}'
check_eq 'grpc missing end_inclusive is injected' \
  "$(cat "$WORK/seen.json" 2>/dev/null | jq -r '.end_inclusive')" "160"

bounded_handler
run_ledger http raw /v2/updates '{"beginExclusive":0,"endInclusive":99999}'
check_eq 'an end beyond the captured snapshot is refused' "$RC" "2"
check_contains 'the refusal names the captured end' "$out" 'beyond the captured ledger end (160)'

bounded_handler
run_ledger http raw /v2/updates '{"beginExclusive":"abc","endInclusive":10}'
check_eq 'a non-integer begin offset is refused' "$RC" "2"

bounded_handler
run_ledger http raw /v2/updates '{"beginExclusive":50,"endInclusive":10}'
check_eq 'begin after end is refused' "$RC" "2"

bounded_handler
run_ledger http raw /v2/updates 'not json'
check_eq 'a malformed body is refused' "$RC" "2"

# Non-stream endpoints are passed through untouched.
bounded_handler
run_ledger http raw /v2/package-vetting/list '{}'
check_eq 'a non-stream body is left alone' "$(cat "$WORK/seen.json" 2>/dev/null | jq -r '.endInclusive // "absent"')" "absent"

# ------------------------------- 3d. raw dependency order and friendly errors

echo
echo 'raw checks the allowlist first, then dependencies, then executes'

# Denied raw must still invoke zero Docker even when the demo is absent.
reset_env
rm -f "$WORK/demo/compose/state/bootstrap.json"
run_ledger grpc raw CommandService/SubmitAndWait '{}'
check_eq 'denied raw without state still exits 2' "$RC" "2"
if docker_called; then bad 'denied raw invokes no docker' 'docker was called'; else ok 'denied raw invokes no docker'; fi

# Allowed raw without bootstrap state fails friendly, not with a raw error.
reset_env
rm -f "$WORK/demo/compose/state/bootstrap.json"
run_ledger http raw /v2/version
check_contains 'allowed raw without state explains how to start the demo' "$out" 'run-demo.sh up'
if [ "$RC" = "0" ]; then bad 'allowed raw without state exits non-zero' 'got 0'; else ok 'allowed raw without state exits non-zero'; fi

# Allowed raw with the demo stopped fails friendly.
reset_env
cat >"$WORK/bin/docker" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"$DOCKER_CALLS"
case "$*" in
  'compose -p '*' ps -q canton') exit 0 ;;   # prints nothing: not running
esac
exit 0
STUB
chmod 755 "$WORK/bin/docker"
run_ledger http raw /v2/version
check_contains 'allowed raw with the demo stopped is friendly' "$out" 'not running'
if [ "$RC" = "0" ]; then bad 'allowed raw with the demo stopped exits non-zero' 'got 0'; else ok 'allowed raw with the demo stopped exits non-zero'; fi

# Allowed grpc raw with no client image available explains the options.
reset_env
cat >"$WORK/bin/docker" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"$DOCKER_CALLS"
case "$*" in
  'compose -p '*' ps -q canton') printf 'stub-container-id\n'; exit 0 ;;
  'image inspect'*) exit 1 ;;
  'pull'*) exit 1 ;;
esac
exit 0
STUB
chmod 755 "$WORK/bin/docker"
run_ledger grpc raw StateService/GetLedgerEnd '{}'
check_contains 'a missing grpc client image is explained' "$out" 'gRPC client image'
check_contains 'the explanation offers the http fallback' "$out" 'HTTP transport instead'
if [ "$RC" = "0" ]; then bad 'a missing grpc client image exits non-zero' 'got 0'; else ok 'a missing grpc client image exits non-zero'; fi

# ----------------------- 3e. a late failure must not become an empty success

echo
echo 'A failure after a successful ledger-end read still fails'

reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
case "$*" in
  *GetLedgerEnd*) printf '{"offset":"160"}'; exit 0 ;;
esac
cat >/dev/null 2>&1 || true
echo 'Failed to dial target host: connection refused' >&2
exit 1
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger grpc votes
if [ "$RC" = "0" ]; then bad 'a late grpc failure in count_template exits non-zero' 'got exit 0'; else ok 'a late grpc failure in count_template exits non-zero'; fi
check_not_contains 'a late grpc failure prints no zero counts' "$out" 'AgendaProposal         0'

reset_env
cat >"$WORK/bin/handler" <<'H'
#!/bin/sh
case "$*" in
  *'/v2/state/ledger-end'*) printf '{"offset":160}\n200'; exit 0 ;;
esac
cat >/dev/null 2>&1 || true
printf '{"errors":["nope"],"status":500}\n500'
H
chmod 755 "$WORK/bin/handler"; STUB_HANDLER="$WORK/bin/handler"; export STUB_HANDLER
run_ledger http credentials
if [ "$RC" = "0" ]; then bad 'a late http failure in credentials exits non-zero' 'got exit 0'; else ok 'a late http failure in credentials exits non-zero'; fi

# ------------------------------------------------------------ 4. CLI contract

echo
echo 'CLI contract'
reset_env
run_ledger help
check_eq 'help exits 0' "$RC" "0"
check_contains 'help documents both transports' "$out" 'http|grpc'
check_contains 'help documents the query-only raw hatch' "$out" 'query-only escape hatch'

run_ledger
check_eq 'no arguments exits 0 with usage' "$RC" "0"

run_ledger bogus
check_eq 'unknown command exits 2' "$RC" "2"

run_ledger grpc bogus
check_eq 'unknown command with transport exits 2' "$RC" "2"

run_ledger ftp status
check_eq 'unknown transport exits 2' "$RC" "2"

# The demo-not-running path stays friendly.
reset_env
cat >"$WORK/bin/docker" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"$DOCKER_CALLS"
case "$1 $2" in
  'compose -p') exit 0 ;;   # `ps -q` prints nothing: not running
  'image inspect') exit 0 ;;
esac
exit 0
STUB
chmod 755 "$WORK/bin/docker"
run_ledger status
check_contains 'not-running prints a friendly message' "$out" 'run-demo.sh up'
if [ "$RC" = "0" ]; then bad 'not-running exits non-zero' 'got 0'; else ok 'not-running exits non-zero'; fi

echo
printf 'passed %s, failed %s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
