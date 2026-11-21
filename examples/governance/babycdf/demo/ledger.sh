#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Read-only viewer for the babycdf-live demo ledger, over either transport:
#
#   http   HTTP JSON Ledger API v2, container-internal port 7575, reached with
#          `docker compose exec` because compose.yaml deliberately does not
#          publish 7575 to the host.
#   grpc   gRPC Ledger API v2, reached with a short-lived `docker run --rm`
#          grpcurl container.
#
# Read-only is enforced, not merely intended: every call, including the `raw`
# escape hatch, must match the query-only allowlists below, which are checked
# before Docker is invoked. This script never creates, exercises, submits,
# allocates or uploads anything, and never starts or stops a container.
#
# To reach the JSON API from the host without `exec`, you would publish 7575 on
# loopback; see DEMO.md. That change is NOT applied and is not needed here.
set -eu

# shellcheck disable=SC1007  # `CDPATH= cd` is the intended env prefix, as in run-demo.sh
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
STATE="$HERE/compose/state"
PROJECT=${BABYCDF_PROJECT:-babycdf-live}
SERVICE=canton
API=http://localhost:7575
LEDGER_PORT=${LEDGER_PORT:-41000}
# Pinned to the immutable digest verified against this demo (grpcurl, linux/arm64,
# fullstorydev/grpcurl:latest as of 2026-11-18). A tag can be re-pointed; a
# digest cannot. Override for a mirror or another architecture.
GRPCURL_IMAGE=${GRPCURL_IMAGE:-fullstorydev/grpcurl@sha256:085e183ca334eb4e81ca81ee12cbb2b2737505d1d77f5e33dabc5d066593d998}
# Page size for update paging. The HTTP endpoint applies `take(limit)` and the
# server also caps it, so paging is mandatory for completeness, not optional.
PAGE_LIMIT=${BABYCDF_PAGE_LIMIT:-100}
MAX_PAGES=${BABYCDF_MAX_PAGES:-1000}

TRANSPORT=http

# POSIX sh runs every command substitution in a subshell, so a plain `exit`
# inside one would end only that subshell and let the caller carry on with an
# empty string. Fatal errors therefore also raise a flag file that outlives the
# subshell; the paging loop and the final dispatch both honour it, so no
# transport failure can be mistaken for an empty result.
FATAL_FLAG=''
die() {
  printf '%s\n' "$*" >&2
  # Written, not truncated: the flag is detected by content, so that it works
  # even when `die` ran inside a subshell that the caller cannot observe.
  [ -n "$FATAL_FLAG" ] && printf 'fatal\n' >"$FATAL_FLAG"
  exit 1
}

fatal_raised() {
  [ -n "$FATAL_FLAG" ] && [ -s "$FATAL_FLAG" ]
}

# Refusals of non-query operations exit 2, distinct from runtime failures.
refuse() {
  printf '%s\n' "$*" >&2
  # Like die, but reserving exit 2 for policy refusals. The flag is written so
  # that a refusal raised inside a command substitution still fails the run.
  [ -n "$FATAL_FLAG" ] && printf 'refused\n' >"$FATAL_FLAG"
  exit 2
}

# ------------------------------------------------------- read-only allowlists

# gRPC: fully-qualified method names, minus the com.daml.ledger.api.v2 prefix.
# Every entry was confirmed read-only against the 3.5.11 reflection descriptors.
grpc_method_allowed() {
  case "$1" in
    StateService/GetLedgerEnd | \
    StateService/GetActiveContracts | \
    StateService/GetConnectedSynchronizers | \
    StateService/GetLatestPrunedOffsets | \
    UpdateService/GetUpdates | \
    UpdateService/GetUpdatesPage | \
    EventQueryService/GetEventsByContractId | \
    admin.PartyManagementService/ListKnownParties | \
    admin.UserManagementService/ListUsers | \
    PackageService/ListPackages | \
    PackageService/ListVettedPackages | \
    PackageService/GetPackageStatus | \
    VersionService/GetLedgerApiVersion) return 0 ;;
    *) return 1 ;;
  esac
}

# HTTP GET: read-only paths. Trailing path segments are allowed only where the
# endpoint is itself a lookup, such as /v2/packages/<id>/status.
http_get_allowed() {
  case "$1" in
    /v2/version | /v2/parties | /v2/users | /v2/packages | /v2/idps | \
    /v2/state/ledger-end | /v2/state/connected-synchronizers | \
    /v2/state/latest-pruned-offsets | /v2/authenticated-user | \
    /livez | /readyz | /docs/openapi) return 0 ;;
    /v2/packages/*/status | /v2/parties/participant-id) return 0 ;;
    *) return 1 ;;
  esac
}

# HTTP POST: query endpoints only. Submission, allocation, DAR upload, user and
# rights mutation, interactive submission and pruning are all absent by design.
http_post_allowed() {
  case "${1%%\?*}" in
    /v2/state/active-contracts | /v2/state/active-contracts-page | \
    /v2/updates | /v2/updates/flats | /v2/updates/get-updates-page | \
    /v2/events/events-by-contract-id | /v2/package-vetting/list) return 0 ;;
    *) return 1 ;;
  esac
}

# ------------------------------------------------------------------ transports

# The container is addressed by Compose project name only. `-f compose.yaml` is
# deliberately NOT passed: that file `extends` a pinned CIPS fragment under
# compose/.cips/<sha>/, so loading it from another working directory fails when
# the fragment is absent, while `-p` alone resolves the running container from
# anywhere. Verified from /tmp and from a directory holding another compose.yaml.
compose() {
  docker compose -p "$PROJECT" "$@"
}

# POSIX sh has no pipefail, so a failing transport piped into jq would otherwise
# surface as a successful empty result. Every call therefore goes through one of
# the checked helpers below: output and status are captured first, validated,
# and only then handed to jq.
TMPDIR_RUN=${TMPDIR:-/tmp}
scratch() {
  mktemp "$TMPDIR_RUN/ledger-sh.XXXXXX"
}

# Created once per run. A subshell inherits the EXIT trap, so cleaning up
# unconditionally would delete the flag in the very subshell that just raised
# it, before the parent could observe it. The cleanup therefore only runs in
# the process that created the flag.
FATAL_FLAG=$(mktemp "$TMPDIR_RUN/ledger-sh-fatal.XXXXXX")
MAIN_PID=$$
cleanup() {
  [ "$$" = "$MAIN_PID" ] && rm -f "$FATAL_FLAG"
  return 0
}
trap cleanup EXIT HUP INT TERM

# Wraps a subcommand so that a failure raised inside any command substitution
# becomes a non-zero exit for the whole script.
guarded() {
  "$@"
  if fatal_raised; then
    # A refusal exits 2; any other fatal exits 1.
    if [ "$(cat "$FATAL_FLAG" 2>/dev/null)" = refused ]; then
      exit 2
    fi
    exit 1
  fi
}

# Checked HTTP GET. Fails on transport error, on HTTP >= 400, and on a body
# that is not JSON (the OpenAPI doc and /readyz are the documented exceptions).
http_get() {
  http_get_allowed "$1" ||
    refuse "Refused: GET $1 is not in the read-only allowlist.
This viewer only performs queries. See './ledger.sh help'."
  _body=$(scratch)
  _code=0
  compose exec -T "$SERVICE" curl -sS -o /dev/stdout -w '\n%{http_code}' "$API$1" \
    >"$_body" 2>"$_body.err" || _code=$?
  if [ "$_code" -ne 0 ]; then
    _msg=$(cat "$_body.err" 2>/dev/null)
    rm -f "$_body" "$_body.err"
    die "HTTP transport failed for GET $1 (curl exit $_code): ${_msg:-no output}"
  fi
  _status=$(tail -n1 "$_body")
  _out=$(sed '$d' "$_body")
  rm -f "$_body" "$_body.err"
  case "$_status" in
    2*) : ;;
    *) die "HTTP $_status for GET $1: $(printf '%s' "$_out" | head -c 300)" ;;
  esac
  case "$1" in
    /livez | /readyz | /docs/openapi) printf '%s\n' "$_out"; return 0 ;;
  esac
  printf '%s' "$_out" | jq -e . >/dev/null 2>&1 ||
    die "Malformed JSON from GET $1: $(printf '%s' "$_out" | head -c 300)"
  printf '%s\n' "$_out"
}

# Checked HTTP POST; the body arrives on stdin and goes to `curl -d @-`, so JSON
# built by jq is never re-parsed by the shell and party ids cannot break it.
http_post() {
  http_post_allowed "$1" ||
    refuse "Refused: POST $1 is not in the read-only allowlist.
Only query endpoints are permitted (active-contracts, updates,
events-by-contract-id, package-vetting/list). See './ledger.sh help'."
  _body=$(scratch)
  _code=0
  compose exec -T "$SERVICE" curl -sS -o /dev/stdout -w '\n%{http_code}' \
    -X POST "$API$1" -H 'Content-Type: application/json' -d @- \
    >"$_body" 2>"$_body.err" || _code=$?
  if [ "$_code" -ne 0 ]; then
    _msg=$(cat "$_body.err" 2>/dev/null)
    rm -f "$_body" "$_body.err"
    die "HTTP transport failed for POST $1 (curl exit $_code): ${_msg:-no output}"
  fi
  _status=$(tail -n1 "$_body")
  _out=$(sed '$d' "$_body")
  rm -f "$_body" "$_body.err"
  case "$_status" in
    2*) : ;;
    *) die "HTTP $_status for POST $1: $(printf '%s' "$_out" | head -c 300)" ;;
  esac
  printf '%s' "$_out" | jq -e . >/dev/null 2>&1 ||
    die "Malformed JSON from POST $1: $(printf '%s' "$_out" | head -c 300)"
  printf '%s\n' "$_out"
}

# Checked gRPC call. grpcurl exits non-zero on dial and status errors; its
# streaming output is a concatenation of JSON objects, which is validated with
# `jq -e -s` before any consumer sees it.
#
# Network: host.docker.internal reaches the published loopback port and is the
# path verified on Docker Desktop for macOS. On Linux that name may be absent,
# so BABYCDF_GRPC_VIA=container joins the participant's own network namespace
# and talks to port 6865 directly, which needs no published port at all.
grpc_call() {
  grpc_method_allowed "$1" ||
    refuse "Refused: gRPC method $1 is not in the read-only allowlist.
Submission, allocation, DAR upload, user and rights mutation, interactive
submission and pruning are not available here. See './ledger.sh help'."
  _body=$(scratch)
  if [ "${BABYCDF_GRPC_VIA:-host}" = container ]; then
    _cid=$(compose ps -q "$SERVICE" 2>/dev/null || true)
    [ -n "$_cid" ] || die 'Cannot resolve the Canton container for BABYCDF_GRPC_VIA=container.'
    set -- "$1" --network "container:$_cid" 127.0.0.1:6865
  else
    set -- "$1" --add-host host.docker.internal:host-gateway "host.docker.internal:$LEDGER_PORT"
  fi
  _method=$1
  shift
  _net1=$1 _net2=$2 _target=$3
  _code=0
  docker run --rm -i "$_net1" "$_net2" "$GRPCURL_IMAGE" \
    -plaintext -d @ "$_target" "com.daml.ledger.api.v2.$_method" \
    >"$_body" 2>"$_body.err" || _code=$?
  if [ "$_code" -ne 0 ]; then
    _msg=$(head -c 400 "$_body.err" 2>/dev/null)
    rm -f "$_body" "$_body.err"
    die "gRPC call $_method failed (grpcurl exit $_code): ${_msg:-no output}"
  fi
  rm -f "$_body.err"
  # An empty stream is legitimate; anything non-empty must parse as JSON.
  if [ -s "$_body" ] && ! jq -e -s . <"$_body" >/dev/null 2>&1; then
    _msg=$(head -c 300 "$_body")
    rm -f "$_body"
    die "Malformed JSON from gRPC $_method: $_msg"
  fi
  cat "$_body"
  rm -f "$_body"
}

need_deps() {
  command -v docker >/dev/null 2>&1 ||
    die 'docker not found. Install Docker Desktop, or start it, then retry.'
  command -v jq >/dev/null 2>&1 ||
    die 'jq not found. Install it (macOS: brew install jq) then retry.'
}

need_grpc_client() {
  docker image inspect "$GRPCURL_IMAGE" >/dev/null 2>&1 && return 0
  printf 'Fetching the gRPC client image (one-off, runs with --rm)...\n' >&2
  docker pull "$GRPCURL_IMAGE" >/dev/null 2>&1 || die "Could not obtain the gRPC client image '$GRPCURL_IMAGE'.
With no network access, either pre-load it:   docker pull $GRPCURL_IMAGE
or point at a local mirror:                   GRPCURL_IMAGE=<ref> $0 grpc ...
or use the HTTP transport instead:            $0 http <command>"
}

# Fails friendly when the demo is not up, instead of leaking a docker error.
need_demo() {
  need_deps
  cid=$(compose ps -q "$SERVICE" 2>/dev/null || true)
  [ -n "$cid" ] || die "The babycdf demo is not running (compose project '$PROJECT').
Start it with:  cd $HERE && ./run-demo.sh up"
  [ -f "$STATE/bootstrap.json" ] || die "No $STATE/bootstrap.json yet: the demo has not bootstrapped.
Start it with:  cd $HERE && ./run-demo.sh up"
  [ "$TRANSPORT" = grpc ] && need_grpc_client
  return 0
}

# Party and contract ids come from the bootstrap output, never hard-coded.
load_state() {
  OPENER=$(jq -r .opener "$STATE/bootstrap.json")
  MEMBER=$(jq -r .member "$STATE/bootstrap.json")
  GOVERNOR=$(jq -r .governor "$STATE/bootstrap.json")
  REGISTRY=$(jq -r .registry "$STATE/bootstrap.json")
  RULE=$(jq -r .rule "$STATE/bootstrap.json")
  # readback columns: PORT PARTY PACKAGE_ID GOVERNOR_CID USER_ID
  if [ -f "$STATE/readback" ]; then
    PACKAGE_ID=$(awk '{print $3}' "$STATE/readback")
    USER_ID=$(awk '{print $5}' "$STATE/readback")
  else
    PACKAGE_ID=''
    USER_ID=''
  fi
  END=$(ledger_end)
  # The transport helpers run inside that substitution, so a failure there has
  # already printed its cause and raised the flag; re-reporting it as a bad
  # ledger end would hide the real reason.
  fatal_raised && exit 1
  case "$END" in
    '' | *[!0-9]*) die "Could not read a numeric ledger end (got '${END:-empty}')." ;;
  esac
}

ledger_end() {
  if [ "$TRANSPORT" = grpc ]; then
    printf '{}' | grpc_call StateService/GetLedgerEnd | jq -r '.offset // empty'
  else
    http_get /v2/state/ledger-end | jq -r '.offset // empty'
  fi
}

resolve_party() {
  case "${1:-opener}" in
    opener) printf '%s' "$OPENER" ;;
    member) printf '%s' "$MEMBER" ;;
    *) printf '%s' "$1" ;;
  esac
}

# ------------------------------------------------------- jq shared fragments

# Flattens a gRPC Value/Record tree into the same plain JSON the HTTP API
# returns, so one formatting layer serves both transports.
GRPC_VAL='
  def val:
    if   has("record")      then (.record.fields // [] | map({key: .label, value: (.value | val)}) | from_entries)
    elif has("list")        then ((.list.elements // []) | map(val))
    elif has("optional")    then (if (.optional.value // null) == null then null else (.optional.value | val) end)
    elif has("gen_map")     then ((.gen_map.entries // []) | map(.key | val))
    elif has("variant")     then {tag: .variant.constructor, value: (.variant.value | val)}
    # A Daml enum arrives as an object; the HTTP API renders it as the bare
    # constructor name, so it is flattened the same way here.
    elif has("enum")        then .enum.constructor
    elif has("text")        then .text
    elif has("party")       then .party
    elif has("int64")       then .int64
    elif has("numeric")     then .numeric
    elif has("bool")        then .bool
    # gRPC timestamps are microseconds since the epoch; the HTTP API renders
    # ISO-8601, so they are converted to match.
    elif has("timestamp")   then
      (.timestamp | if . == null then null
       else (tonumber / 1000000 | floor | todate | sub("Z$"; ".000000Z")) end)
    elif has("contract_id") then .contract_id
    elif has("unit")        then {}
    else . end;
  def args: {record: .} | val;
'

# Normalizes a created event from either transport to {cid, pkg, template, offset, arg}.
HTTP_NORM='
  def norm: { cid: .contractId, pkg: .packageName,
              template: (.templateId | split(":") | .[2]),
              offset: .offset, arg: .createArgument };
'
# gRPC renders int64 as a JSON string; the offset is coerced to a number so
# that both transports compare and sort identically downstream.
GRPC_NORM="$GRPC_VAL"'
  def norm: { cid: .contract_id, pkg: .package_name,
              template: .template_id.entity_name,
              offset: (.offset | if . == null then null else tonumber end),
              arg: (.create_arguments | args) };
'

# ------------------------------------------------------------------- queries

# Active contracts for one party, normalized. activeAtOffset/active_at_offset
# is an integer; the filter lives under eventFormat/event_format (the HTTP
# `filter` field is rejected alongside it, and in gRPC 3.5.11 `filter` and
# `verbose` are reserved/removed on these requests).
acs_party() {
  if [ "$TRANSPORT" = grpc ]; then
    jq -n --argjson end "$END" --arg party "$1" \
      '{active_at_offset: $end,
        event_format: { filters_by_party: { ($party): { cumulative: [ { wildcard_filter: {} } ] } }, verbose: true } }' |
      grpc_call StateService/GetActiveContracts |
      jq -s -r "$GRPC_NORM"'[ .[] | select(.active_contract) | .active_contract.created_event | norm ]'
  else
    jq -n --argjson end "$END" --arg party "$1" \
      '{activeAtOffset: $end,
        eventFormat: { filtersByParty: { ($party): { cumulative: [ { identifierFilter: { WildcardFilter: { value: {} } } } ] } }, verbose: true } }' |
      http_post /v2/state/active-contracts |
      jq -r "$HTTP_NORM"'[ .[].contractEntry.JsActiveContract.createdEvent | norm ]'
  fi
}

# Active contracts of one template, for every party on the participant.
# Both transports accept the package-NAME reference form (verified), spelled
# `#name:Module:Entity` on HTTP and as a split Identifier on gRPC.
acs_template() {
  pkg=${1%%:*}
  rest=${1#*:}
  module=${rest%%:*}
  entity=${rest##*:}
  if [ "$TRANSPORT" = grpc ]; then
    jq -n --argjson end "$END" --arg pkg "$pkg" --arg m "$module" --arg e "$entity" \
      '{active_at_offset: $end,
        event_format: { filters_for_any_party: { cumulative: [ { template_filter: { template_id: { package_id: $pkg, module_name: $m, entity_name: $e } } } ] }, verbose: true } }' |
      grpc_call StateService/GetActiveContracts |
      jq -s -r "$GRPC_NORM"'[ .[] | select(.active_contract) | .active_contract.created_event | norm ]'
  else
    jq -n --argjson end "$END" --arg tpl "$1" \
      '{activeAtOffset: $end,
        eventFormat: { filtersForAnyParty: { cumulative: [ { identifierFilter: { TemplateFilter: { value: { templateId: $tpl } } } } ] }, verbose: true } }' |
      http_post /v2/state/active-contracts |
      jq -r "$HTTP_NORM"'[ .[].contractEntry.JsActiveContract.createdEvent | norm ]'
  fi
}

count_template() {
  acs_template "$1" | jq 'length'
}

# One page of updates, normalized to {events, lastOffset, count}.
#
# A page is NOT just transactions. Canton 3.5.11 can return OffsetCheckpoint,
# Reassignment and TopologyTransaction entries alongside Transaction ones
# (GetUpdatesResponse.update is a oneOf; the JSON API mirrors it). Per-page
# metadata is therefore preserved BEFORE the events are flattened: the resume
# offset comes from the response envelope of the LAST usable entry, whatever
# its kind, not from the event offsets. A checkpoint-only page is legitimate
# and still advances the cursor, which matters when the next archive lies
# beyond it.
#
# ACS_DELTA is used rather than LEDGER_EFFECTS because it emits exactly the
# create/archive pair needed here; LEDGER_EFFECTS reports exercises instead,
# which does not directly give the archived contract's payload.
updates_page() {
  _party=$1
  _begin=$2
  if [ "$TRANSPORT" = grpc ]; then
    jq -n --argjson b "$_begin" --argjson end "$END" --arg party "$_party" \
      '{begin_exclusive: $b, end_inclusive: $end,
        update_format: { include_transactions: {
          transaction_shape: "TRANSACTION_SHAPE_ACS_DELTA",
          event_format: { filters_by_party: { ($party): { cumulative: [ { wildcard_filter: {} } ] } }, verbose: true } } } }' |
      grpc_call UpdateService/GetUpdates |
      jq -s -r "$GRPC_NORM"'
        # Envelope offset per response, regardless of the oneof arm taken.
        def envelope:
          if   .transaction          then {off: (.transaction.offset          | tonumber), tx: .transaction}
          elif .offset_checkpoint    then {off: (.offset_checkpoint.offset    | tonumber), tx: null}
          elif .reassignment         then {off: (.reassignment.offset         | tonumber), tx: null}
          elif .topology_transaction then {off: (.topology_transaction.offset | tonumber), tx: null}
          else null end;
        [ .[] | envelope | select(. != null) ] as $env
        | { count: ($env | length),
            lastOffset: ($env | map(.off) | max),
            events: [ $env[] | select(.tx != null) | .tx as $t | ($t.events // [])[]
              | if .created then { kind: "created", offset: ($t.offset | tonumber), event: (.created | norm) }
                else { kind: "archived", offset: ($t.offset | tonumber), cid: .archived.contract_id,
                       template: .archived.template_id.entity_name, pkg: .archived.package_name } end ] }'
  else
    jq -n --argjson b "$_begin" --argjson end "$END" --arg party "$_party" \
      '{beginExclusive: $b, endInclusive: $end,
        updateFormat: { includeTransactions: {
          transactionShape: "TRANSACTION_SHAPE_ACS_DELTA",
          eventFormat: { filtersByParty: { ($party): { cumulative: [ { identifierFilter: { WildcardFilter: { value: {} } } } ] } }, verbose: true } } } }' |
      http_post "/v2/updates?limit=$PAGE_LIMIT" |
      jq -r "$HTTP_NORM"'
        # JsUpdate is a oneOf: Transaction | OffsetCheckpoint | Reassignment |
        # TopologyTransaction. Each arm nests its payload under .value.
        def envelope:
          .update as $u
          | if   $u.Transaction         then {off: ($u.Transaction.value.offset         | tonumber), tx: $u.Transaction.value}
            elif $u.OffsetCheckpoint    then {off: ($u.OffsetCheckpoint.value.offset    | tonumber), tx: null}
            elif $u.Reassignment        then {off: ($u.Reassignment.value.offset        | tonumber), tx: null}
            elif $u.TopologyTransaction then {off: ($u.TopologyTransaction.value.offset | tonumber), tx: null}
            else null end;
        [ .[] | envelope | select(. != null) ] as $env
        | { count: ($env | length),
            lastOffset: ($env | map(.off) | max),
            events: [ $env[] | select(.tx != null) | .tx as $t | ($t.events // [])[]
              | if .CreatedEvent then { kind: "created", offset: $t.offset, event: (.CreatedEvent | norm) }
                else { kind: "archived", offset: $t.offset, cid: .ArchivedEvent.contractId,
                       template: (.ArchivedEvent.templateId | split(":") | .[2]), pkg: .ArchivedEvent.packageName } end ] }'
  fi
}

# Complete bounded event list, assembled by paging.
#
# Both transports need this. The HTTP endpoint applies `take(limit)` and the
# server imposes its own cap, so a single request silently truncates: measured
# on this ledger, `?limit=3` returns 3 of 19 transactions with HTTP 200 and no
# warning. Paging advances `beginExclusive` to the last RESPONSE offset of the
# previous page, which is why updates_page reports page metadata separately
# from the flattened events: a page may carry only offset checkpoints and no
# events at all, and must still advance the cursor so that a later archive is
# reached. Completion is "the page was empty" or "the cursor reached the
# captured ledger end", never "no events were seen".
updates_events() {
  _party=$1
  _begin=0
  _pages=0
  _acc=$(scratch)
  printf '[]' >"$_acc"
  while :; do
    _page=$(updates_page "$_party" "$_begin")
    # A transport failure inside the substitution above exits its subshell and
    # raises the flag; without this check the loop would see an empty page and
    # report a clean, wrong result.
    if fatal_raised; then
      rm -f "$_acc"
      exit 1
    fi
    _count=$(printf '%s' "$_page" | jq -r '.count // "bad"' 2>/dev/null || echo bad)
    case "$_count" in
      '' | *[!0-9]*) rm -f "$_acc"; die 'Paging failed: a page was not a valid update response.' ;;
    esac
    # An empty page means the bounded range is exhausted.
    [ "$_count" -eq 0 ] && break
    _last=$(printf '%s' "$_page" | jq -r '.lastOffset // "bad"')
    case "$_last" in
      '' | null | *[!0-9]*) rm -f "$_acc"; die 'Paging failed: a page carried no usable response offset.' ;;
    esac
    if [ "$_last" -le "$_begin" ]; then
      rm -f "$_acc"
      die "Paging made no progress at offset $_begin; refusing to loop."
    fi
    # Events may legitimately be empty on a checkpoint-only page; only the
    # offset has to advance.
    _merged=$(printf '%s' "$_page" | jq -c --slurpfile acc "$_acc" '$acc[0] + .events')
    printf '%s' "$_merged" >"$_acc"
    _begin=$_last
    _pages=$((_pages + 1))
    [ "$_begin" -ge "$END" ] && break
    if [ "$_pages" -ge "$MAX_PAGES" ]; then
      rm -f "$_acc"
      die "Paging exceeded $MAX_PAGES pages before reaching offset $END; aborting."
    fi
  done
  cat "$_acc"
  rm -f "$_acc"
}

IMPL='#cap-example-babycdf'
DEMO='#cap-example-babycdf-demo'
AMULET='#splice-amulet'

# -------------------------------------------------------------- subcommands

cmd_status() {
  load_state
  printf 'transport    %s\n' "$TRANSPORT"
  printf 'ledger end   %s\n' "$END"
  if [ "$TRANSPORT" = grpc ]; then
    printf 'version      %s\n' "$(printf '{}' | grpc_call VersionService/GetLedgerApiVersion | jq -r .version)"
    printf '{}' | grpc_call StateService/GetConnectedSynchronizers |
      jq -r '.connected_synchronizers[] | "synchronizer \(.synchronizer_alias)  \(.synchronizer_id)"'
  else
    printf 'version      %s\n' "$(http_get /v2/version | jq -r .version)"
    http_get /v2/state/connected-synchronizers |
      jq -r '.connectedSynchronizers[] | "synchronizer \(.synchronizerAlias)  \(.synchronizerId)"'
  fi
  printf 'user         %s\n' "${USER_ID:-unknown}"
  _anchor=$(acs_template "$DEMO:Cap.Examples.BabyCdf.Live:DemoAnchor")
  fatal_raised && exit 1
  printf '%s' "$_anchor" |
    jq -r '.[].arg | "demo step    \(.step)  (runId \(.demoRunId), window \(.windowSeconds)s, \(.credentials|length) credentials)"'
  printf 'governor     %s…\n' "$(printf '%s' "$GOVERNOR" | cut -c1-16)"
  printf 'registry     %s…\n' "$(printf '%s' "$REGISTRY" | cut -c1-16)"
  printf 'rule         %s…\n' "$(printf '%s' "$RULE" | cut -c1-16)"
}

cmd_parties() {
  load_state
  echo 'Parties:'
  if [ "$TRANSPORT" = grpc ]; then
    printf '{}' | grpc_call admin.PartyManagementService/ListKnownParties |
      jq -r '.party_details[] | "  \(.party)"'
    echo 'Users:'
    printf '{}' | grpc_call admin.UserManagementService/ListUsers |
      jq -r '.users[] | "  \(.id) -> \(.primary_party // "(none)")"'
  else
    http_get /v2/parties | jq -r '.partyDetails[] | "  \(.party)"'
    echo 'Users:'
    http_get /v2/users |
      jq -r '.users[] | "  \(.id) -> \(if .primaryParty == "" then "(none)" else .primaryParty end)"'
  fi
}

cmd_packages() {
  load_state
  echo 'Vetted demo packages:'
  if [ "$TRANSPORT" = grpc ]; then
    printf '{}' | grpc_call PackageService/ListVettedPackages |
      jq -r '[.vetted_packages[].packages[]
              | select(.package_name | test("^cap-example|^splice-amulet$"))
              | "  \(.package_name) \(.package_version) \(.package_id)"] | unique | .[]'
  else
    printf '{}' | http_post /v2/package-vetting/list |
      jq -r '[.vettedPackages[].packages[]
              | select(.packageName | test("^cap-example|^splice-amulet$"))
              | "  \(.packageName) \(.packageVersion) \(.packageId)"] | unique | .[]'
    if [ -n "$PACKAGE_ID" ]; then
      printf 'Impl package %s: %s\n' "$PACKAGE_ID" \
        "$(http_get "/v2/packages/$PACKAGE_ID/status" | jq -r .packageStatus)"
    fi
  fi
}

cmd_contracts() {
  load_state
  party=$(resolve_party "${1:-opener}")
  # Fetch before printing the header, so a transport failure cannot leave a
  # heading above an empty, falsely reassuring list.
  _rows=$(acs_party "$party")
  fatal_raised && exit 1
  printf 'Active contracts visible to %s at offset %s:\n' "${party%%::*}" "$END"
  printf '%s' "$_rows" | jq -r '.[] | "  \(.pkg)  \(.template)  \(.cid[0:16])…"'
}

# Archived contracts are not in the ACS by definition, so they are derived from
# the complete bounded update stream: each ArchivedEvent is paired with the
# CreatedEvent of the same contract id seen earlier in the same range.
cmd_archived() {
  load_state
  party=$(resolve_party "${1:-opener}")
  _events=$(updates_events "$party")
  fatal_raised && exit 1
  printf 'Archived contracts visible to %s, offsets 1..%s:\n' "${party%%::*}" "$END"
  out=$(printf '%s' "$_events" | jq -r '
    (map(select(.kind == "created")) | map({key: .event.cid, value: .event}) | from_entries) as $created
    | map(select(.kind == "archived"))
    | if length == 0 then empty
      else .[] | "  \(.template)  \(.cid[0:16])…  created@\($created[.cid].offset // "?")  archived@\(.offset)"
      end')
  if [ -n "$out" ]; then
    printf '%s\n' "$out"
  else
    echo '  (none archived yet in the visible range)'
  fi
  echo '  Visibility is limited to this party; a contract archived before the'
  echo '  range, or never visible to it, does not appear. This sandbox is'
  echo '  in-memory and unpruned, so the range starts at the ledger beginning.'
}

cmd_rule() {
  load_state
  _rows=$(acs_template "$IMPL:Cap.Examples.BabyCdf.RuleVote:EffectiveRule")
  fatal_raised && exit 1
  printf '%s' "$_rows" | jq -r '.[] | "EffectiveRule \(.cid[0:16])…",
               "  ruleId   \(.arg.draft.ruleId)",
               "  version  \(.arg.draft.version)",
               "  design   \(.arg.draft.designText)",
               "  members  \(.arg.members.map | length)",
               (.arg.clauses[]
                | "  clause \(.clauseId)",
                  "    criterion: \(.criterion)",
                  "    required:  \(.requiredPhrases | join(" | "))",
                  "    forbidden: \(.forbiddenPhrases | join(" | "))")'
}

cmd_votes() {
  load_state
  # Each count is captured and checked before anything is printed: a transport
  # failure inside the substitution would otherwise render as a blank field
  # that reads like a legitimate zero.
  _counts=''
  for t in AgendaProposal AgendaBallot ApprovedGrantProposal ApprovedMilestone RuleAssembly AgendaGovernor; do
    _n=$(count_template "$IMPL:Cap.Examples.BabyCdf.RuleVote:$t")
    fatal_raised && exit 1
    case "$_n" in
      '' | *[!0-9]*) die "Could not count $t (got '${_n:-empty}')." ;;
    esac
    _counts="$_counts$t $_n
"
  done
  echo 'Counts:'
  printf '%s' "$_counts" | while read -r _name _value; do
    [ -n "$_name" ] || continue
    printf '  %-22s %s\n' "$_name" "$_value"
  done
  for t in ApprovedGrantProposal ApprovedMilestone; do
    payloads=$(acs_template "$IMPL:Cap.Examples.BabyCdf.RuleVote:$t" | jq -c '.[].arg')
    fatal_raised && exit 1
    if [ -n "$payloads" ]; then
      printf '%s payloads:\n' "$t"
      printf '%s\n' "$payloads" | jq .
    fi
  done
}

cmd_credentials() {
  load_state
  _direct=$(acs_template "$IMPL:Cap.Examples.BabyCdf.Rights:DirectVotingRight")
  fatal_raised && exit 1
  _reg=$(acs_template "$IMPL:Cap.Examples.BabyCdf.Rights:RegisteredVotingRight")
  fatal_raised && exit 1
  printf '%s' "$_direct" | jq -r '.[] | "  direct      \(.arg.member | split("::")[0])  \(.cid[0:16])…"'
  printf '%s' "$_reg" | jq -r '.[] | "  registered  \(.arg.member | split("::")[0])  \(.cid[0:16])…"'
}

cmd_coupons() {
  load_state
  for t in DevelopmentFundCoupon UnclaimedDevelopmentFundCoupon; do
    printf '  %-32s %s\n' "$t" "$(count_template "$AMULET:Splice.Amulet:$t")"
  done
  echo '  (both stay 0 until demo step 5 has run)'
}

cmd_history() {
  load_state
  if [ $# -gt 0 ]; then
    cid=$1
    printf 'Events for contract %s…\n' "$(printf '%s' "$cid" | cut -c1-16)"
    if [ "$TRANSPORT" = grpc ]; then
      events=$(jq -n --arg cid "$cid" --arg party "$OPENER" \
        '{contract_id: $cid,
          event_format: { filters_by_party: { ($party): { cumulative: [ { wildcard_filter: {} } ] } }, verbose: false } }' |
        grpc_call EventQueryService/GetEventsByContractId 2>/dev/null) || true
      printf '%s' "$events" | jq -e '.created' >/dev/null 2>&1 ||
        die "  no events for that contract id (unknown, or not visible to the opener)"
      printf '%s' "$events" | jq -r '
        "  created  offset \(.created.created_event.offset)  \(.created.created_event.template_id.entity_name)",
        (if (.archived // null) == null then "  status   still active"
         else "  archived offset \(.archived.archived_event.offset)" end)'
    else
      # An unknown or invisible contract id answers 4xx, which http_post turns
      # into a failure; a visible one answers 200 with a `created` entry.
      events=$(jq -n --arg cid "$cid" --arg party "$OPENER" \
        '{contractId: $cid,
          eventFormat: { filtersByParty: { ($party): { cumulative: [ { identifierFilter: { WildcardFilter: { value: {} } } } ] } }, verbose: false } }' |
        http_post /v2/events/events-by-contract-id 2>/dev/null) || true
      printf '%s' "$events" | jq -e 'has("created")' >/dev/null 2>&1 ||
        die "  no events for that contract id (unknown, or not visible to the opener)"
      printf '%s' "$events" | jq -r '
        "  created  offset \(.created.createdEvent.offset)  \(.created.createdEvent.templateId | split(":")[2])",
        (if .archived == null then "  status   still active"
         else "  archived offset \(.archived.archivedEvent.offset)" end)'
    fi
  else
    _events=$(updates_events "$OPENER")
    fatal_raised && exit 1
    printf 'Transactions visible to the opener, offsets 1..%s:\n' "$END"
    printf '%s' "$_events" | jq -r '
      group_by(.offset) | .[]
      | "  offset \(.[0].offset)  \([.[] | if .kind == "created" then "Created" else "Archived" end] | join(","))"'
  fi
}

# Update streams must be bounded. /v2/updates and UpdateService/GetUpdates
# never terminate without an end offset, so a `raw` call that omits one would
# hang the checked buffer until the server idles it out. The captured ledger
# end is injected when the bound is missing, and a bound beyond that snapshot
# is rejected: the viewer only reports the state it actually captured.
# Validation happens before the stream is opened.
raw_bound_stream() {
  _target=$1
  _body=$2
  case "$_target" in
    *updates* | *GetUpdates*) : ;;
    *) printf '%s' "$_body"; return 0 ;;
  esac
  printf '%s' "$_body" | jq -e . >/dev/null 2>&1 ||
    refuse "Refused: the request body for $_target is not valid JSON."
  if [ "$TRANSPORT" = grpc ]; then
    _bk=begin_exclusive; _ek=end_inclusive
  else
    _bk=beginExclusive; _ek=endInclusive
  fi
  # Types first, then ordering, then the snapshot bound.
  _verdict=$(printf '%s' "$_body" | jq -r --argjson end "$END" --arg bk "$_bk" --arg ek "$_ek" '
    (.[$bk] // 0) as $b | (.[$ek] // null) as $e
    | ($b | if type == "string" and test("^[0-9]+$") then tonumber else . end) as $bn
    | ($e | if type == "string" and test("^[0-9]+$") then tonumber else . end) as $en
    | if ($bn | type) != "number" or ($bn % 1) != 0 or $bn < 0 then "bad-begin"
      elif $en != null and (($en | type) != "number" or ($en % 1) != 0 or $en < 0) then "bad-end"
      elif $en != null and $en > $end then "beyond-end"
      elif $en != null and $bn > $en then "begin-after-end"
      else "ok" end')
  case "$_verdict" in
    bad-begin) refuse "Refused: $_bk must be a non-negative integer offset." ;;
    bad-end) refuse "Refused: $_ek must be a non-negative integer offset." ;;
    beyond-end) refuse "Refused: $_ek is beyond the captured ledger end ($END).
This viewer reports only the snapshot it captured; re-run to pick up new updates." ;;
    begin-after-end) refuse "Refused: $_bk must not exceed $_ek." ;;
    ok) : ;;
    *) refuse "Refused: could not validate the update range for $_target." ;;
  esac
  # Inject the captured end when the caller omitted it, so the stream always
  # terminates rather than blocking the checked buffer.
  printf '%s' "$_body" | jq -c --argjson end "$END" --arg bk "$_bk" --arg ek "$_ek" '
    . as $o
    | (if ($o[$ek] // null) == null then $o + { ($ek): $end } else $o end)
    | . + { ($bk): ((.[$bk] // 0) | if type == "string" then tonumber else . end) }'
}

# Query-only escape hatch. The order is deliberate and tested:
#   1. allowlist        — a forbidden operation never reaches Docker at all;
#   2. need_demo        — jq/docker present, bootstrap written, demo running,
#                         and for grpc the client image available, so an
#                         allowed call fails with a friendly message instead
#                         of a raw docker error;
#   3. load_state       — captures the ledger end used to bound streams;
#   4. bound + execute.
cmd_raw() {
  [ $# -ge 1 ] || die "Usage: ledger.sh $TRANSPORT raw <path-or-method> [json-body]
  http: ledger.sh http raw /v2/version
  grpc: ledger.sh grpc raw StateService/GetLedgerEnd '{}'
Query-only: see './ledger.sh help' for the permitted operations."

  # 1. Allowlist, before any dependency probe or network call.
  if [ "$TRANSPORT" = grpc ]; then
    grpc_method_allowed "$1" ||
      refuse "Refused: gRPC method $1 is not in the read-only allowlist.
Submission, allocation, DAR upload, user and rights mutation, interactive
submission and pruning are not available here. See './ledger.sh help'."
  elif [ $# -ge 2 ]; then
    http_post_allowed "$1" ||
      refuse "Refused: POST $1 is not in the read-only allowlist.
Only query endpoints are permitted (active-contracts, updates,
events-by-contract-id, package-vetting/list). See './ledger.sh help'."
  else
    http_get_allowed "$1" ||
      refuse "Refused: GET $1 is not in the read-only allowlist.
This viewer only performs queries. See './ledger.sh help'."
  fi

  # 2. Dependencies and a running demo.
  need_demo
  # 3. Ledger end, read safely before any stream is opened.
  load_state

  # 4. Bound the request, then execute.
  if [ "$TRANSPORT" = grpc ]; then
    if [ $# -ge 2 ]; then body=$2; else body='{}'; fi
    body=$(raw_bound_stream "$1" "$body")
    printf '%s' "$body" | grpc_call "$1"
  elif [ $# -ge 2 ]; then
    body=$(raw_bound_stream "$1" "$2")
    printf '%s' "$body" | http_post "$1"
  else
    http_get "$1"
  fi
}

usage() {
  cat <<EOF
Read-only viewer for the running babycdf-live demo ledger.

Usage: ledger.sh [http|grpc] <command> [args]

Transports (default: http):
  http    HTTP JSON Ledger API v2, in-container on port 7575 via docker compose exec
  grpc    gRPC Ledger API v2, via a --rm grpcurl container

Commands (both transports):
  status              ledger end, API version, synchronizer, current demo step
  parties             parties and users on the participant
  packages            babycdf and splice-amulet packages: name, version, id
  contracts [who]     active contracts for 'opener' (default), 'member',
                      or a full party id
  archived [who]      archived contracts, paired with their creation, derived
                      from the complete bounded update stream
  rule                the active EffectiveRule: version, design text, clauses
  votes               proposal/ballot/approval counts, plus approval payloads
  credentials         direct and registered voting rights, per member
  coupons             DevelopmentFundCoupon counts
  history [cid]       update log for the opener, or create/archive events of a
                      contract id
  raw <path> [body]   query-only escape hatch:
                        http: raw /v2/version  |  raw /v2/updates '{json}'
                        grpc: raw StateService/GetLedgerEnd '{}'
  help                this text

Read-only is enforced. 'raw' accepts only allowlisted query operations:
  gRPC    StateService GetLedgerEnd/GetActiveContracts/GetConnectedSynchronizers/
          GetLatestPrunedOffsets, UpdateService GetUpdates/GetUpdatesPage,
          EventQueryService GetEventsByContractId, PartyManagementService
          ListKnownParties, UserManagementService ListUsers, PackageService
          ListPackages/ListVettedPackages/GetPackageStatus,
          VersionService GetLedgerApiVersion
  HTTP    GET on read-only paths; POST only on /v2/state/active-contracts,
          /v2/updates, /v2/events/events-by-contract-id and
          /v2/package-vetting/list
Anything else, including submit, allocate, DAR upload, user and rights
mutation, interactive submission and pruning, is refused with exit 2 before
any network call is made.

Examples:
  ./ledger.sh status                 # http, the default
  ./ledger.sh grpc status
  ./ledger.sh grpc archived member

Container lifecycle is never touched. The grpc transport runs a short-lived
digest-pinned grpcurl client with 'docker run --rm'; nothing is installed on
the host.

Requires the demo to be up: ./run-demo.sh up
Environment:
  BABYCDF_PROJECT     Compose project name (default babycdf-live)
  LEDGER_PORT         published gRPC port (default 41000)
  GRPCURL_IMAGE       gRPC client image reference (digest-pinned by default)
  BABYCDF_GRPC_VIA    'host' (default, Docker Desktop) or 'container' to join
                      the participant's network namespace, useful on Linux
  BABYCDF_PAGE_LIMIT  update page size (default 100)
EOF
}

# Transport is optional and leading; anything else is treated as a command,
# so existing one-word invocations such as `ledger.sh status` keep working.
case "${1:-help}" in
  http | grpc)
    TRANSPORT=$1
    shift
    ;;
esac

case "${1:-help}" in
  help | -h | --help) usage ;;
  status) need_demo; guarded cmd_status ;;
  parties) need_demo; guarded cmd_parties ;;
  packages) need_demo; guarded cmd_packages ;;
  contracts) need_demo; shift; guarded cmd_contracts "$@" ;;
  archived) need_demo; shift; guarded cmd_archived "$@" ;;
  rule) need_demo; guarded cmd_rule ;;
  votes) need_demo; guarded cmd_votes ;;
  credentials) need_demo; guarded cmd_credentials ;;
  coupons) need_demo; guarded cmd_coupons ;;
  history) need_demo; shift; guarded cmd_history "$@" ;;
  # The allowlist is checked inside cmd_raw before any dependency or demo
  # probe, so a refused operation never reaches Docker.
  raw) shift; guarded cmd_raw "$@" ;;
  *)
    printf 'Unknown command: %s\n\n' "$1" >&2
    usage >&2
    exit 2
    ;;
esac
