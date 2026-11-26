#!/usr/bin/env python3
"""Fail-closed proof that step 4 never reached the ledger, for marker recovery.

A step-4 attempt marker is written BEFORE the script runs, so a refusal that
happened during pre-ledger selection still leaves a marker that blocks every
later retry, and the only existing command that clears a marker is `down`,
which destroys the network. This decides one question only:

    did THIS network's step 4 ever submit anything for the milestone subject?

It answers from the ledger and from the exact raw log the runner used, and it
refuses whenever the evidence is insufficient. It never clears anything: it
prints a verdict and exits non-zero unless recovery is proven safe. Absence of
evidence is never read as evidence of absence.

Design points that are load-bearing, not incidental:

  * Identity of an execution or an approval is NOT a root `roundId`. Those
    contracts carry no such field; the round lives in `sourceSnapshot.roundId`
    and the subject in `agenda`. Checking a root `roundId` would silently
    match nothing and pass.
  * Archived events carry no `createArgument`, so a contract archived in
    history can only be identified through the create that made it. A full
    create map is built first, and any relevant contract that cannot be
    resolved is a refusal.
  * TRANSACTION_SHAPE_LEDGER_EFFECTS is used, because creates and archives of
    the same transaction must both be visible.
  * Completeness depends on nothing having been pruned. If the participant has
    pruned any offset, absence cannot be proven from the start and this
    refuses.
  * The milestone rounds considered come from the COMPLETE create history for
    the subject URL, active or archived, never from the ACS alone.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

MILESTONE_URL = "https://github.com/canton-foundation/canton-dev-fund/issues/540"
ENTERED = "[BABYCDF-STEP-PHASE] prevalidation_entered"
SUBMITTING = "[BABYCDF-STEP-PHASE] submission_attempted"
PAGE = 100
# The ONLY runtime this check asserts its API contract for. Every normative
# statement below was read from the cached SDK protos named in CONTRACT_SDK;
# a different runtime is refused rather than assumed compatible.
SUPPORTED_RUNTIME = "3.5.11"
# The normative API text is quoted from protos extracted read-only from the
# cached SDK 3.5.2 component jar. The excerpts and the archive/member digests
# are preserved under .build/step4-evidence/live-capture as
# proto-contract-3.5.2.log, so this citation is checkable rather than claimed.
CONTRACT_SDK = "3.5.2"
# Hard cap on paged iteration, so a non-terminating token loop fails closed.
MAX_PAGES = 10000
# Templates whose existence for a milestone round proves step 4 acted.
ACTING = ("AgendaBallot", "AgendaExecution", "ApprovedMilestone")


def refuse(reason):
    print("REFUSED: " + reason)
    print("The attempt marker is kept. Nothing was changed.")
    sys.exit(2)


# The HTTP JSON API refuses a response that would exceed its node limit with
# JSON_API_MAXIMUM_LIST_ELEMENTS_NUMBER_REACHED (HTTP 413), observed on this
# participant as "The number of matching elements (201) is greater than the
# node limit (200)." A truncated page must never be read as a complete one,
# so this marker is detected explicitly and the window is narrowed instead.
TOO_MANY = "JSON_API_MAXIMUM_LIST_ELEMENTS_NUMBER_REACHED"

# NORMATIVE (com/daml/ledger/api/v2/update_service.proto, cached SDK 3.5.2):
# the update oneof is exactly these four variants. This is proto text, not a
# live observation; what the running participant actually emitted is reported
# separately in the run summary.
#
# Only two of the four are benign for this check:
#   * OffsetCheckpoint carries an offset and synchronizer times, no events.
#   * TopologyTransaction carries party-authorization events, no contracts.
#
# Reassignment is NOT benign. An unassign/assign moves a contract between
# synchronizers, so a relevant contract could leave or enter this view
# without a create or archive that this checker would otherwise see, which
# would silently weaken the absence proof. No attempt is made to interpret
# reassignments, so encountering one is a refusal.
BENIGN_UPDATES = {"OffsetCheckpoint", "TopologyTransaction"}
KNOWN_UPDATES = BENIGN_UPDATES | {"Transaction", "Reassignment"}


def ledger(demo, path, body=None):
    """One allowlisted read-only query through the existing viewer.

    The body argument is OMITTED for reads, never passed as an empty string:
    ledger.sh treats the presence of a body as a POST and refuses a GET path
    with "POST ... is not in the read-only allowlist", so an empty body would
    make every GET fail in production while a stub that ignores argv passed.
    """
    command = ["bash", str(demo / "ledger.sh"), "raw", path]
    if body is not None:
        command.append(body)
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=180)
    except subprocess.TimeoutExpired:
        refuse("a ledger query timed out; completeness unknown")
    combined = done.stdout + done.stderr
    if TOO_MANY in combined:
        refuse("the ledger refused a page as too large (%s); the page size is "
               "fixed and this check does not retry with a different shape, "
               "so completeness is unknown" % TOO_MANY)
    if done.returncode != 0:
        refuse("a ledger query failed; an error is never read as an empty result")
    try:
        return json.loads(done.stdout)
    except json.JSONDecodeError:
        refuse("a ledger reply was not valid JSON; completeness unknown")


def party_filter(opener):
    return {"filtersByParty": {opener: {"cumulative": [
        {"identifierFilter": {"WildcardFilter": {"value": {
            "includeCreatedEventBlob": False}}}}]}}, "verbose": False}


def update_format(opener):
    """Transactions AND reassignments, both explicitly requested.

    UpdateFormat.include_reassignments (transaction_filter.proto, SDK 3.5.2)
    is documented as: "Include (un)assignments in the stream. If unset, no
    (un)assignments are emitted in the stream." Leaving it unset would hide
    every reassignment, so a contract could leave or enter this view with no
    visible create or archive and the absence proof would be silently wrong.
    It is therefore requested, and any reassignment that appears is refused
    rather than interpreted.
    """
    return {"includeTransactions": {
                "eventFormat": party_filter(opener),
                "transactionShape": "TRANSACTION_SHAPE_LEDGER_EFFECTS"},
            "includeReassignments": party_filter(opener)}


def active_contracts(demo, opener, end):
    """The complete active-contract set at `end`, via the paged endpoint.

    GetActiveContractsPageRequest (state_service.proto, SDK 3.5.2) requires
    event_format, takes an optional max_page_size, and returns a
    next_page_token that is absent exactly on the last page. A wildcard ACS
    here is far larger than the 200-element JSON node limit, so the paged
    form is the only complete one, and its token absence is the server's own
    end signal rather than an inference.
    """
    event_format = {"filtersByParty": {opener: {"cumulative": [
        {"identifierFilter": {"WildcardFilter": {"value": {
            "includeCreatedEventBlob": False}}}}]}}, "verbose": False}
    collected, pages, token = [], 0, None
    while True:
        request = {"activeAtOffset": end, "eventFormat": event_format,
                   "maxPageSize": PAGE}
        if token is not None:
            request["pageToken"] = token
        reply = ledger(demo, "/v2/state/active-contracts-page",
                       json.dumps(request))
        if not isinstance(reply, dict) or "activeContracts" not in reply:
            refuse("the paged active-contract endpoint returned an unexpected "
                   "shape, so history coverage cannot be reconciled")
        if reply.get("activeAtOffset") != end:
            refuse("the active-contract snapshot was computed at offset %s "
                   "rather than %s, so it cannot be reconciled with the "
                   "history read" % (reply.get("activeAtOffset"), end))
        collected.extend(reply["activeContracts"])
        pages += 1
        next_token = reply.get("nextPageToken")
        if not next_token:
            return collected
        if next_token == token:
            refuse("the paged active-contract endpoint returned the same page "
                   "token twice, so pagination is not progressing")
        token = next_token
        if pages > MAX_PAGES:
            refuse("the paged active-contract endpoint did not terminate "
                   "within %s pages; coverage unknown" % MAX_PAGES)


def paged_updates(demo, opener, end):
    """Complete history of (0, end] using the server's own paging contract.

    GetUpdatesPage (update_service.proto, SDK 3.5.2) returns, per page, the
    bounds it actually covered and a next_page_token that is absent exactly
    when the last page has been delivered. That absence is the server's own
    completion signal, so completeness is READ rather than inferred from
    numeric tiling. Per the proto, every subsequent request must repeat the
    same begin, end, update_format and order, which this does.

    Returns (updates, pages). There is deliberately no fallback: if this
    endpoint fails at any point, the range actually covered is unknown, so
    the check refuses instead of restarting against a different endpoint.
    """
    collected, pages, token = [], 0, None
    covered_to, previous_high = 0, -1
    while True:
        request = {"beginOffsetExclusive": 0, "endOffsetInclusive": end,
                   "maxPageSize": PAGE, "updateFormat": update_format(opener),
                   "descendingOrder": False}
        if token is not None:
            request["pageToken"] = token
        # No fallback: a failure anywhere in a paginated read leaves the
        # covered range unknown, and restarting on another endpoint could
        # silently skip updates. Any failure refuses.
        reply = ledger(demo, "/v2/updates/get-updates-page",
                       json.dumps(request))
        if not isinstance(reply, dict) or "updates" not in reply:
            refuse("the paged update endpoint returned an unexpected shape, so "
                   "history completeness cannot be established")
        low = reply.get("lowestPageOffsetExclusive")
        high = reply.get("highestPageOffsetInclusive")
        if not isinstance(low, int) or not isinstance(high, int):
            refuse("a page did not report its own offset bounds, so coverage "
                   "cannot be verified")
        if low != covered_to:
            refuse("page coverage is not contiguous: a page starts after %s "
                   "but the previous page ended at %s, so updates between "
                   "them were never read" % (low, covered_to))
        if high < low or high > end:
            refuse("a page reported bounds (%s, %s] outside the requested "
                   "range (0, %s]" % (low, high, end))
        covered_to = high
        collected.extend(reply["updates"])
        pages += 1
        next_token = reply.get("nextPageToken")
        if not next_token:
            # The server says this was the last page. Its own stated coverage
            # must then reach the requested end, or the range is incomplete.
            if covered_to != end:
                refuse("the server reported the final page at offset %s but "
                       "the requested range ended at %s, so history is "
                       "incomplete" % (covered_to, end))
            return collected, pages
        # Refuse immediately on a token that repeats or a page that made no
        # progress, rather than looping to the page cap.
        if next_token == token:
            refuse("the paged update endpoint returned the same page token "
                   "twice, so pagination is not progressing")
        if high <= previous_high and not reply["updates"]:
            refuse("a page advanced neither its upper bound nor any update, "
                   "so pagination is not progressing")
        previous_high = high
        token = next_token
        if pages > MAX_PAGES:
            refuse("the paged update endpoint did not terminate within %s "
                   "pages; completeness unknown" % MAX_PAGES)


def template(event):
    return event.get("templateId", "").split(":")[-1]


def subject_url(arg):
    """Subject URL of a create, wherever this template carries it."""
    for holder in (arg.get("subject"), (arg.get("agenda") or {}).get("value")):
        if isinstance(holder, dict) and holder.get("url"):
            return holder["url"]
    snap = arg.get("sourceSnapshot") or {}
    observation = snap.get("observation") or {}
    return observation.get("sourceUrl")


def round_of(arg):
    """Round id of a create: root field when present, else the snapshot."""
    if arg.get("roundId"):
        return arg["roundId"]
    snap = arg.get("sourceSnapshot") or {}
    return snap.get("roundId")


# An Architecture agenda carries a RuleDraft, which has exactly these fields
# and deliberately no url and no round: it is a rule vote, not a source round,
# and its sourceSnapshot is always absent. Confirmed against the live
# participant for AgendaAction, AgendaExecution and AgendaRoundReceipt.
RULE_DRAFT_FIELDS = {"ruleId", "version", "designText", "ruleRef"}
SOURCE_AGENDAS = ("Grant", "Milestone")


def classify(arg):
    """Say what an acting contract is, or that it cannot be understood.

    Returns "architecture" for a structurally valid Architecture agenda with
    no source snapshot, "source" for a source-backed agenda, "plain" when
    there is no agenda at all (a ballot), or "unknown" for anything that does
    not match a recognised shape. "unknown" must fail closed: a blank or
    unparsed variant is never silently treated as unrelated.
    """
    agenda = arg.get("agenda")
    if agenda is None:
        # No agenda field at all: a ballot carries its round directly.
        return "plain" if arg.get("roundId") else "unknown"
    if not isinstance(agenda, dict):
        return "unknown"
    tag, value = agenda.get("tag"), agenda.get("value")
    if tag == "Architecture":
        if not isinstance(value, dict):
            return "unknown"
        # Exact field set, and no source snapshot: a rule vote cannot be a
        # milestone round, so it is unrelated by structure, not by omission.
        if set(value.keys()) != RULE_DRAFT_FIELDS:
            return "unknown"
        if arg.get("sourceSnapshot") is not None:
            return "unknown"
        return "architecture"
    if tag in SOURCE_AGENDAS:
        return "source" if isinstance(value, dict) and value.get("url") else "unknown"
    return "unknown"


def log_evidence(log_path, marker_path):
    """Phase signals from the exact bytes THIS attempt appended."""
    if not log_path:
        refuse("the runner supplied no step-4 log path; a run cannot be proven "
               "submission-free from a guessed location")
    log = Path(log_path)
    if not log.is_file():
        refuse("the recorded step-4 log %s does not exist" % log)
    meta = Path(str(marker_path) + ".attempt")
    if not meta.is_file():
        refuse("no attempt metadata beside the marker: this marker predates "
               "bounded recovery and its log span cannot be established")
    try:
        record = json.loads(meta.read_text())
        start = int(record["log_offset"])
        recorded = record["log_path"]
    except (ValueError, KeyError, TypeError):
        refuse("attempt metadata is malformed; the log span cannot be established")
    if os.path.realpath(recorded) != os.path.realpath(str(log)):
        refuse("attempt metadata names a different log (%s) than the one given; "
               "refusing rather than reading unrelated bytes" % recorded)
    size = log.stat().st_size
    if size < start:
        refuse("the step-4 log is shorter than the recorded attempt offset, so "
               "it was truncated or rotated and this attempt cannot be read")
    with log.open("rb") as handle:
        handle.seek(start)
        attempt = handle.read().decode("utf-8", "replace")
    if SUBMITTING in attempt:
        refuse("this attempt logged the submission phase, so a submission may "
               "have been attempted and its outcome may be unknown")
    if ENTERED not in attempt:
        refuse("this attempt logged no prevalidation phase signal, so it cannot "
               "be proven to have refused before reaching the ledger")
    return start, size


def main():
    demo = Path(__file__).resolve().parent
    state = demo / "compose" / "state"
    marker = state / "step-4.attempted"

    if not marker.exists():
        print("Nothing to recover: no step-4 attempt marker exists.")
        return 0
    if (state / "step-4.json").exists():
        refuse("step-4.json exists, so step 4 produced an authoritative outcome")

    # 1. Log evidence, bounded to exactly this attempt's byte span.
    start, size = log_evidence(os.environ.get("BABYCDF_STEP4_LOG"), marker)

    bootstrap = state / "bootstrap.json"
    if not bootstrap.exists():
        refuse("no bootstrap.json; the opener party cannot be established")
    opener = json.loads(bootstrap.read_text()).get("opener")
    if not opener:
        refuse("bootstrap.json carries no opener party")

    # 2. Absence can only be proven over unpruned history.
    pruned = ledger(demo, "/v2/state/latest-pruned-offsets")
    for field in ("participantPrunedUpToInclusive",
                  "allDivulgedContractsPrunedUpToInclusive"):
        value = pruned.get(field)
        if not isinstance(value, int):
            refuse("the participant's pruning state could not be read; "
                   "completeness from the start of history is unknown")
        if value > 0:
            refuse("the participant has pruned up to offset %s, so absence "
                   "cannot be proven from the start of history" % value)

    # Exactly one synchronizer must be connected RIGHT NOW. This is a
    # present-tense topology check and is NOT historical proof: the endpoint
    # reports current connections only, so it cannot show that the
    # participant was single-synchronizer across the whole history being
    # read. The actual historical guarantee comes from requesting
    # include_reassignments and refusing any reassignment found; this check
    # only rejects a topology where reassignment is routine.
    # GetConnectedSynchronizersRequest.party is optional and an empty party
    # returns every synchronizer this node is connected to.
    connected = ledger(demo, "/v2/state/connected-synchronizers")
    if not isinstance(connected, dict):
        refuse("the connected-synchronizers reply was not an object, so the "
               "synchronizer topology could not be established")
    synchronizers = connected.get("connectedSynchronizers")
    if not isinstance(synchronizers, list) or not synchronizers:
        refuse("no connected synchronizer was reported, so the topology "
               "cannot be established")
    if len(synchronizers) != 1:
        refuse("the participant is connected to %s synchronizers; a contract "
               "can be reassigned between them and this checker does not "
               "interpret reassignments, so absence cannot be proven"
               % len(synchronizers))
    synchronizer = synchronizers[0].get("synchronizerId")
    if not isinstance(synchronizer, str) or not synchronizer:
        refuse("the connected synchronizer reported no id, so the topology "
               "cannot be established")

    # The runtime version is GATED, not merely recorded. Every normative
    # statement this check relies on (the update oneof, the paged endpoints
    # and their token semantics, include_reassignments) was read from the
    # cached SDK 3.5.2 protos. Those protos are only evidence for a runtime
    # they actually describe, so a different runtime is refused rather than
    # assumed compatible.
    version = ledger(demo, "/v2/version")
    runtime = version.get("version") if isinstance(version, dict) else None
    if not isinstance(runtime, str) or not runtime:
        refuse("the participant did not report its version, so the API "
               "contract this check relies on cannot be attributed")
    if runtime != SUPPORTED_RUNTIME:
        refuse("this check's API contract was read from the cached SDK %s "
               "protos and is only asserted for runtime %s, but the "
               "participant reports %s; refusing rather than assuming the "
               "contract still holds"
               % (CONTRACT_SDK, SUPPORTED_RUNTIME, runtime))

    end = ledger(demo, "/v2/state/ledger-end").get("offset")
    if not isinstance(end, int):
        refuse("the ledger end offset could not be read")

    # 3. Complete create history, with ledger effects so creates and archives
    #    of one transaction are both visible.
    creates = {}          # contractId -> (template, subject url, round id)
    rounds = set()        # every milestone round ever proposed for the subject
    acting = []           # relevant acting contracts, with how they were seen
    seen_offsets = set()
    highest = 0
    # Preferred path: the server's own paged endpoint, whose absent
    # next_page_token is an authoritative end-of-range signal. Fallback:
    # bounded windows that tile (0, end], where completeness is inferred
    # from the tiling rather than reported by the server. Which path was
    # used is stated in the summary, never glossed.
    items, windows = paged_updates(demo, opener, end) if end > 0 else ([], 0)
    completeness = "reported by the server (final page had no next token)"
    batches = [(0, end, items)]
    for begin, stop, page in batches:
        for item in page:
            update = item.get("update") or {}
            # The update oneof is documented in update_service.proto as exactly
            # Transaction, Reassignment, OffsetCheckpoint and TopologyTransaction.
            # Only Transaction can carry contract effects, so the other three
            # are benign HERE and skipped by name. Anything else is a shape this
            # checker has not validated, and reading history that contains it as
            # complete would be unsound, so it fails closed.
            unknown = set(update) - KNOWN_UPDATES
            if unknown:
                refuse("an update of unrecognised kind %s was returned, so this "
                       "history cannot be read as complete"
                       % ", ".join(sorted(unknown)))
            if not update:
                refuse("an update entry carried no update variant at all, so "
                       "this history cannot be read as complete")
            if "Reassignment" in update:
                refuse("history contains a Reassignment, which can move a "
                       "contract between synchronizers without a create or "
                       "archive in this view; this checker does not interpret "
                       "reassignments, so absence cannot be proven")
            if "Transaction" in update:
                holder = update["Transaction"]
                if not isinstance(holder, dict) or "value" not in holder:
                    refuse("a Transaction update carried no value, so this "
                           "history cannot be read as complete")
                tx = holder["value"]
                if not isinstance(tx, dict) or not tx:
                    refuse("a Transaction update carried an empty or "
                           "non-object value, so this history cannot be read "
                           "as complete")
            else:
                # A benign variant: no contract events are possible.
                continue
            # Every returned offset must be inside the window it came from and
            # must not repeat. A duplicate or out-of-range offset means the
            # pages do not tile the range as assumed, so completeness fails.
            offset = tx.get("offset")
            if not isinstance(offset, int):
                refuse("a transaction carried no integer offset, so history "
                       "completeness cannot be established")
            if not begin < offset <= stop:
                refuse("a transaction at offset %s was returned outside its "
                       "requested window (%s, %s]; the offset range contract "
                       "does not hold and completeness is unknown"
                       % (offset, begin, stop))
            if offset in seen_offsets:
                refuse("offset %s was returned more than once, so pages "
                       "overlap and completeness cannot be established"
                       % offset)
            seen_offsets.add(offset)
            if offset < highest:
                refuse("offsets were returned out of order (%s after %s); the "
                       "monotonic contract does not hold" % (offset, highest))
            highest = offset
            for event in tx.get("events", []):
                created = event.get("CreatedEvent")
                if created:
                    arg = created.get("createArgument") or {}
                    name = template(created)
                    url, rid = subject_url(arg), round_of(arg)
                    creates[created.get("contractId")] = (name, url, rid)
                    if name == "AgendaProposal" and url == MILESTONE_URL and rid:
                        rounds.add(rid)
                    if name in ACTING:
                        acting.append((name, url, rid, tx.get("offset"), "created",
                                       classify(arg)))
                    continue
                # An exercise consumes a contract and carries no create
                # argument, so it is resolved through the create map.
                exercised = event.get("ExercisedEvent")
                if exercised and exercised.get("consuming"):
                    cid = exercised.get("contractId")
                    known = creates.get(cid)
                    name = template(exercised)
                    if known is None and name in ACTING:
                        refuse("a consuming event at offset %s touches a %s "
                               "that has no create in this history, so it "
                               "cannot be excluded from a milestone round"
                               % (tx.get("offset"), name))

    if windows == 0:
        refuse("no bounded history page was read; absence cannot be inferred")

    # Reconcile the history against the authoritative active-contract set at
    # the SAME offset. Every contract the participant considers active at
    # `end` must have been seen as a create in the history just read; if any
    # is missing, the history did not actually cover the range and no absence
    # claim may be made from it. This is a measured cross-check, not a
    # restatement of the traversal.
    acs = active_contracts(demo, opener, end)
    if not isinstance(acs, list):
        refuse("the active-contract snapshot was not a list, so history "
               "coverage cannot be reconciled")
    active_ids, unmapped = 0, []
    for entry in acs:
        created_event = ((entry.get("contractEntry") or {})
                         .get("JsActiveContract", {}).get("createdEvent") or {})
        cid = created_event.get("contractId")
        if not cid:
            continue
        active_ids += 1
        if cid not in creates:
            unmapped.append(cid)
    if unmapped:
        refuse("%s contract(s) active at offset %s have no create in the "
               "history just read (first: %s...), so that history did not "
               "cover the range and absence cannot be proven"
               % (len(unmapped), end, unmapped[0][:16]))
    if not rounds:
        refuse("no milestone round was ever proposed for this subject, so "
               "there is nothing a corrected selector could retry")

    # 4. Any acting contract for the subject blocks, active or archived. An
    #    Architecture rule vote is unrelated BY STRUCTURE: it carries a
    #    RuleDraft with no url and no source snapshot, so it can never be a
    #    milestone round. Anything whose shape is not recognised fails closed.
    architecture = 0
    for name, url, rid, offset, how, kind in acting:
        if kind == "architecture":
            architecture += 1
            continue
        if kind == "unknown":
            refuse("a %s %s at offset %s has an agenda shape this checker does "
                   "not recognise, so it cannot be excluded from a milestone "
                   "round" % (name, how, offset))
        if rid is None and url is None:
            refuse("a %s %s at offset %s carries neither a round nor a subject, "
                   "so it cannot be excluded from a milestone round"
                   % (name, how, offset))
        if url == MILESTONE_URL or (rid is not None and rid in rounds):
            refuse("history contains a %s at offset %s belonging to a milestone "
                   "round for this subject; step 4 may have acted"
                   % (name, offset))

    print("PROVEN SAFE: step 4 refused before any ledger submission.")
    print("  opener            %s..." % opener[:28])
    print("  ledger end        %s" % end)
    print("  pruned up to      0 (complete history available)")
    print("  runtime version   %s (gated; normative contract from cached SDK "
          "%s protos)" % (runtime, CONTRACT_SDK))
    print("  synchronizer      exactly 1 connected NOW: %s... (present-tense "
          "topology, not historical proof)" % synchronizer[:24])
    print("  reassignments     requested via includeReassignments; none found "
          "(any would have refused)")
    print("  history pages     %s, completeness %s" % (windows, completeness))
    print("  updates read      %s distinct offsets, monotonic and in range"
          % len(seen_offsets))
    print("  acs reconciled    %s active contracts at offset %s, all present "
          "as creates" % (active_ids, end))
    print("  creates mapped    %s" % len(creates))
    print("  milestone rounds  %s (from complete history, active or archived)"
          % len(rounds))
    for rid in sorted(rounds):
        print("    %s" % rid)
    print("  acting contracts  %s relevant, none for this subject" % len(acting))
    print("  architecture      %s rule-vote contracts, unrelated by structure"
          % architecture)
    print("  attempt log span  bytes %s..%s of %s"
          % (start, size, os.environ.get("BABYCDF_STEP4_LOG")))
    print("  phase signals     prevalidation present; submission absent")
    print("")
    print("The runner may now clear ONLY its own step-4 attempt marker.")
    print("This script changed nothing.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
