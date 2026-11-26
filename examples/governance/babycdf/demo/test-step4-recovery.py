#!/usr/bin/env python3
"""Regression tests for the step-4 selector runner and marker recovery.

Tracked on purpose: a regression here could clear an attempt marker after a
real submission, so these cases must not live only in a private directory.

Most cases run on synthetic state in a temporary directory, with ledger.sh
replaced by a stub. The stub replies use the camelCase JSON shape of the HTTP
Ledger API v2, captured read-only from the local participant with
TRANSACTION_SHAPE_LEDGER_EFFECTS and recorded, field names only, in
`.build/step4-evidence/http-shapes/http-ledger-effects-shapes.json`. That
capture is the provenance for these fixtures. Do not cite the gRPC
attestations under `.build/`: those are snake_case (`update_id`,
`contract_id`, `choice_argument`) and are a different wire format.

Observed in that capture:

  * AgendaExecution and ApprovedGrantProposal carry NO root `roundId`;
    identity is in `sourceSnapshot.roundId` and the subject in `agenda`.
  * An Architecture agenda carries a RuleDraft whose fields are exactly
    ruleId, version, designText, ruleRef: no url, and sourceSnapshot absent.
  * A consuming ExercisedEvent carries no `createArgument`, so an archived
    contract is only identifiable through its create.
  * Creates and archives of one transaction appear together.

A stub cannot prove the checker talks to the real viewer correctly, because a
stub ignores argv conventions. One case therefore runs the checker end to end
against the REAL local ledger.sh HTTP transport, with a private synthetic
marker and log metadata in a temporary directory. It never reads or writes the
operator's own state and performs no mutation.

Run: python3 demo/test-step4-recovery.py
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
import tempfile
from pathlib import Path

DEMO = Path(__file__).resolve().parent
RECOVERY = DEMO / "step4-marker-recovery.py"
RUNNER = DEMO / "run-demo.sh"

OPENER = "demo-opener-synthetic::1220aaaa"
MILESTONE_URL = "https://github.com/canton-foundation/canton-dev-fund/issues/540"
OTHER_URL = "https://github.com/canton-foundation/canton-dev-fund/issues/538"
PR_URL = "https://github.com/canton-foundation/canton-dev-fund/pull/184"
ROUND_A = "canton-foundation/canton-dev-fund/issues/540/comments/6038684918"
ROUND_B = "canton-foundation/canton-dev-fund/issues/540/comments/6606684361"
ENTERED = "[BABYCDF-STEP-PHASE] prevalidation_entered"
SUBMITTING = "[BABYCDF-STEP-PHASE] submission_attempted"
PKG = "236409454ed1b08c:Cap.Examples.BabyCdf.RuleVote:"

STUB = r'''
import json, sys
spec = json.load(open(%s))
path = sys.argv[2] if len(sys.argv) > 2 else ""
body = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None

def die(msg):
    sys.stderr.write(msg + "\n"); sys.exit(1)

if path in spec["fail_on"]:
    die("synthetic failure")
if spec["bad_json"]:
    print("this is not json"); sys.exit(0)

if path == "/v2/version":
    print(json.dumps(spec["version"])); sys.exit(0)
if path == "/v2/state/connected-synchronizers":
    if body is not None:
        die("connected-synchronizers must be a GET with no body")
    print(json.dumps(spec["synchronizers"])); sys.exit(0)
if path == "/v2/state/ledger-end":
    if body is not None:
        die("ledger-end must be a GET with no body")
    print(json.dumps({"offset": spec["end"]})); sys.exit(0)
if path == "/v2/state/latest-pruned-offsets":
    if body is not None:
        die("pruned-offsets must be a GET with no body")
    print(json.dumps(spec["pruned"])); sys.exit(0)

if path == "/v2/updates/get-updates-page":
    if spec.get("too_many"):
        print("HTTP 413 for POST /v2/updates: "
              '{"code":"JSON_API_MAXIMUM_LIST_ELEMENTS_NUMBER_REACHED",'
              '"cause":"The number of matching elements (201) is greater '
              'than the node limit (200)."}')
        sys.exit(0)
    # Argument validation, not ignored: the proto requires these to be
    # identical across every page of one pagination.
    if body.get("beginOffsetExclusive") != 0:
        die("beginOffsetExclusive changed between pages")
    if body.get("endOffsetInclusive") != spec["end"]:
        die("endOffsetInclusive changed between pages")
    if body.get("descendingOrder") is not False:
        die("descendingOrder changed between pages")
    fmt = body.get("updateFormat") or {}
    if "includeReassignments" not in fmt:
        die("includeReassignments was not requested, so reassignments "
            "would be silently hidden")
    pages = spec.get("update_pages")
    if pages is None:
        print(json.dumps(spec.get("paged_reply", {}))); sys.exit(0)
    token = body.get("pageToken")
    index = 0 if token is None else int(token)
    if index >= len(pages):
        die("page token out of range")
    page = dict(pages[index])
    if spec.get("repeat_token"):
        page["nextPageToken"] = "0"
    elif index + 1 < len(pages):
        page["nextPageToken"] = str(index + 1)
    print(json.dumps(page)); sys.exit(0)

if path == "/v2/state/active-contracts-page":
    if body.get("activeAtOffset") != spec["end"]:
        die("activeAtOffset changed between pages")
    if "eventFormat" not in body:
        die("eventFormat is required")
    pages = spec.get("acs_pages")
    if pages is None:
        print(json.dumps({"activeContracts": spec.get("acs", []),
                          "activeAtOffset": spec["end"]})); sys.exit(0)
    token = body.get("pageToken")
    index = 0 if token is None else int(token)
    if index >= len(pages):
        die("acs page token out of range")
    page = dict(pages[index])
    page.setdefault("activeAtOffset", spec["end"])
    if index + 1 < len(pages):
        page["nextPageToken"] = str(index + 1)
    print(json.dumps(page)); sys.exit(0)

die("unexpected path " + path)
'''



def created(offset, template, arg):
    return {"CreatedEvent": {"offset": offset, "contractId": arg.pop("_cid"),
                             "templateId": PKG + template, "createArgument": arg}}


def archived(offset, template, cid):
    """A consuming exercise: no createArgument, as the real ledger emits."""
    return {"ExercisedEvent": {"offset": offset, "contractId": cid,
                               "templateId": PKG + template, "consuming": True,
                               "choice": "Archive", "choiceArgument": {}}}


def tx(offset, *events):
    return {"update": {"Transaction": {"value": {"offset": offset,
                                                 "events": list(events)}}}}


def proposal(cid, round_id, url=MILESTONE_URL):
    """Real shape: AgendaProposal has BOTH a root roundId and a snapshot."""
    return {"_cid": cid, "roundId": round_id, "subject": {"url": url},
            "sourceSnapshot": {"roundId": round_id, "originalProposal": "184",
                               "observation": {"sourceUrl": url, "number": "540"}}}


def execution(cid, round_id, url=MILESTONE_URL):
    """Real shape: NO root roundId; identity via sourceSnapshot and agenda."""
    return {"_cid": cid,
            "agenda": {"tag": "Milestone", "value": {"url": url,
                                                     "kind": "milestone"}},
            "sourceSnapshot": {"roundId": round_id,
                               "observation": {"sourceUrl": url}}}


def ballot(cid, round_id):
    return {"_cid": cid, "roundId": round_id, "voter": OPENER,
            "vote": {"tag": "Absent", "value": {}}}


def architecture(cid, extra=None, snapshot=None, fields=None):
    """A step-2 rule vote. RuleDraft has no url and no round by design."""
    draft = {"ruleId": "architecture-review", "version": "2",
             "designText": "rule text", "ruleRef": {"location": "rules/v2.md",
                                                    "sha256": "0" * 64}}
    if fields is not None:
        draft = fields
    arg = {"_cid": cid, "agenda": {"tag": "Architecture", "value": draft}}
    if snapshot is not None:
        arg["sourceSnapshot"] = snapshot
    if extra:
        arg.update(extra)
    return arg


# A real step-2 history: an Architecture round with its ballots invited, cast,
# archived on resolution, and an AgendaExecution created and then consumed.
STEP2_HISTORY = {
    "0": [tx(10, created(10, "AgendaProposal", proposal("00a", ROUND_A))),
          tx(20, created(20, "AgendaProposal", proposal("00b", ROUND_B)))],
    "100": [tx(110, created(110, "AgendaBallot", ballot("00r1", "run:rule-v2")),
               created(110, "AgendaBallot", ballot("00r2", "run:rule-v2"))),
            tx(120, archived(120, "AgendaBallot", "00r1"),
               archived(120, "AgendaBallot", "00r2"),
               created(120, "AgendaExecution", architecture("00rx"))),
            tx(130, archived(130, "AgendaExecution", "00rx"),
               created(130, "EffectiveRule", {"_cid": "00er"}))],
}


BASE_HISTORY = {"0": [tx(10, created(10, "AgendaProposal", proposal("00a", ROUND_A))),
                      tx(20, created(20, "AgendaProposal", proposal("00b", ROUND_B)))]}


def scenario(root, *, history=None, log=ENTERED, end=200, pruned=None,
             marker=True, meta=True, outcome=False, bootstrap=True,
             fail_on=(), bad_json=False, log_prefix="", meta_path=None,
             meta_offset=None, truncate=False, too_many=False,
             version=None, synchronizers=None, paged=False, paged_reply=None,
             acs=None, update_pages=None, acs_pages=None,
             repeat_token=False):
    demo = root / "demo"
    state = demo / "compose" / "state"
    state.mkdir(parents=True)
    logs = root / "logs"
    logs.mkdir()
    shutil.copy(RECOVERY, demo / RECOVERY.name)

    if bootstrap:
        (state / "bootstrap.json").write_text(json.dumps({"opener": OPENER}))
    if outcome:
        (state / "step-4.json").write_text('"done"')

    log_file = logs / "step-4.log"
    log_file.write_text(log_prefix)
    offset = len(log_prefix.encode())
    log_file.write_text(log_prefix + log)
    if truncate:
        log_file.write_text("")

    if marker:
        (state / "step-4.attempted").write_text("")
    if meta:
        (state / "step-4.attempted.attempt").write_text(json.dumps({
            "log_path": meta_path or os.path.realpath(str(log_file)),
            "log_offset": offset if meta_offset is None else meta_offset,
            "step": 4}))

    (root / "replies.json").write_text(json.dumps({
        "end": end,
        # `pruned or {...}` would substitute the default for an empty dict,
        # which is exactly the unreadable-state case under test.
        "pruned": {"participantPrunedUpToInclusive": 0,
                   "allDivulgedContractsPrunedUpToInclusive": 0}
                  if pruned is None else pruned,
        # The existing cases describe history as a dict of window -> updates.
        # With the paged endpoint there are no windows, so they are flattened
        # into one final page covering the whole requested range.
        "update_pages": update_pages if update_pages is not None else
            [{"updates": [u for key in sorted((BASE_HISTORY if history is None
                                               else history), key=int)
                          for u in (BASE_HISTORY if history is None
                                    else history)[key]],
              "lowestPageOffsetExclusive": 0,
              "highestPageOffsetInclusive": end if isinstance(end, int) else 0}],
        "fail_on": list(fail_on), "bad_json": bad_json,
        "too_many": too_many, "paged": paged,
        "paged_reply": paged_reply if paged_reply is not None else None,
        "acs_pages": acs_pages, "repeat_token": repeat_token,
        "acs": acs if acs is not None else [],
        "version": version if version is not None
                   else {"version": "3.5.11"},
        "synchronizers": synchronizers if synchronizers is not None
                   else {"connectedSynchronizers": [
                       {"synchronizerAlias": "a",
                        "synchronizerId": "local::synthetic"}]}}))
    (root / "stub.py").write_text(STUB % json.dumps(str(root / "replies.json")))
    stub = demo / "ledger.sh"
    stub.write_text('#!/bin/sh\nexec python3 "%s" "$@"\n' % (root / "stub.py"))
    stub.chmod(0o755)
    return demo, log_file


def verdict(**kwargs):
    with tempfile.TemporaryDirectory() as tmp:
        demo, log_file = scenario(Path(tmp), **kwargs)
        env = dict(os.environ, BABYCDF_STEP4_LOG=str(log_file))
        done = subprocess.run([sys.executable, str(demo / RECOVERY.name)],
                              capture_output=True, text=True, timeout=180, env=env)
        marker = demo / "compose" / "state" / "step-4.attempted"
        head = (done.stdout.strip().splitlines() or [""])[0]
        return done.returncode == 0, marker.exists(), head


CASES = [
    ("clean pre-submission refusal is recoverable", True, {}),
    ("absent marker is a no-op", True, {"marker": False, "meta": False}),

    # R3: log provenance must be exact, bounded and current.
    ("submission signal in this attempt refuses", False,
     {"log": ENTERED + "\n" + SUBMITTING}),
    ("submission signal alone refuses", False, {"log": SUBMITTING}),
    ("no phase signal in this attempt refuses", False, {"log": "older run\n"}),
    ("empty log refuses", False, {"log": ""}),
    ("missing attempt metadata refuses", False, {"meta": False}),
    ("metadata naming another log refuses", False, {"meta_path": "/nonexistent/x.log"}),
    ("truncated log shorter than offset refuses", False,
     {"log_prefix": "x" * 500, "truncate": True}),
    ("stale prevalidation from a PREVIOUS attempt refuses", False,
     {"log_prefix": ENTERED + "\nold run finished\n", "log": "this attempt said nothing\n"}),
    ("old attempt's submission is not read as this one's", True,
     {"log_prefix": SUBMITTING + "\nprevious attempt submitted\n", "log": ENTERED + "\n"}),

    ("existing step-4.json refuses", False, {"outcome": True}),

    # R2: rounds come from complete create history, not the ACS.
    ("no milestone round ever proposed refuses", False, {"history": {}}),
    ("only a PR round refuses", False,
     {"history": {"0": [tx(10, created(10, "AgendaProposal", proposal("00a", "r", PR_URL)))]}}),

    # R1: identity via sourceSnapshot.roundId and agenda, not a root roundId.
    ("execution with no root roundId still refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution", execution("00x", ROUND_A)))]})}),
    ("approved milestone with no root roundId refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "ApprovedMilestone", execution("00y", ROUND_B)))]})}),
    ("execution matching by subject url alone refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution", execution("00z", "unknown-round")))]})}),
    ("ballot in a milestone round refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaBallot", ballot("00c", ROUND_A)))]})}),

    # R1/R2: archived acting contracts must still block.
    ("archived ballot resolved through its create refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaBallot", ballot("00c", ROUND_A)),
            archived(150, "AgendaBallot", "00c"))]})}),
    ("archive of an unmapped acting contract refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, archived(150, "AgendaBallot", "00ghost"))]})}),
    ("archived round plus active sibling still refuses", False,
     {"history": {"0": [tx(10, created(10, "AgendaProposal", proposal("00a", ROUND_A)),
                           archived(10, "AgendaProposal", "00a")),
                        tx(20, created(20, "AgendaProposal", proposal("00b", ROUND_B)))],
                  "100": [tx(150, created(150, "AgendaBallot", ballot("00c", ROUND_A)),
                             archived(150, "AgendaBallot", "00c"))]}}),

    # Unrelated activity must not block a genuine recovery.
    ("unrelated round ballot is tolerated", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaBallot", ballot("00c", "run:rule-v2")))]})}),
    ("other milestone subject is tolerated", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution", execution("00w", "other", OTHER_URL)))]})}),
    ("unrelated archived template is tolerated", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "SourceCheckpoint", {"_cid": "00s"}),
            archived(150, "SourceCheckpoint", "00s"))]})}),

    # A: an Architecture rule vote is unrelated BY STRUCTURE, and a real
    # step-2 history with ballots and archives must not block recovery.
    ("real step-2 architecture history is tolerated", True,
     {"history": STEP2_HISTORY}),
    ("architecture execution alone is tolerated", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution", architecture("00rx")))]})}),
    ("architecture with a stray root roundId is tolerated", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution",
                         architecture("00rx", extra={"roundId": "run:rule-v2"})))]})}),
    # Unknown or malformed variants must fail closed, never be ignored.
    ("architecture carrying a source snapshot refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution",
                         architecture("00rx", snapshot={"roundId": ROUND_A})))]})}),
    ("architecture with unexpected draft fields refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution",
                         architecture("00rx", fields={"ruleId": "x", "url": MILESTONE_URL})))]})}),
    ("unknown agenda tag refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution",
                         {"_cid": "00q", "agenda": {"tag": "Mystery", "value": {}}}))]})}),
    ("agenda that is not an object refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution",
                         {"_cid": "00q", "agenda": "Architecture"}))]})}),
    ("source agenda with no url refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         tx(150, created(150, "AgendaExecution",
                         {"_cid": "00q", "agenda": {"tag": "Milestone", "value": {}}}))]})}),

    # Completeness: pruning destroys the absence proof.
    ("participant pruning refuses", False,
     {"pruned": {"participantPrunedUpToInclusive": 42,
                 "allDivulgedContractsPrunedUpToInclusive": 0}}),
    ("divulged pruning refuses", False,
     {"pruned": {"participantPrunedUpToInclusive": 0,
                 "allDivulgedContractsPrunedUpToInclusive": 7}}),
    ("unreadable pruning state refuses", False, {"pruned": {}}),
    ("pruning query failure refuses", False,
     {"fail_on": ["/v2/state/latest-pruned-offsets"]}),

    # Query integrity: an error is never an empty result.
    ("ledger-end failure refuses", False, {"fail_on": ["/v2/state/ledger-end"]}),
    ("history failure refuses", False,
     {"fail_on": ["/v2/updates/get-updates-page"]}),
    ("malformed JSON refuses", False, {"bad_json": True}),
    ("missing bootstrap refuses", False, {"bootstrap": False}),
    ("non-integer ledger end refuses", False, {"end": "not-a-number"}),

    # Completeness of the offset range. Offsets are sparse and the server
    # caps a response at 200 elements, so a truncated, overlapping or
    # out-of-range page must never be read as complete history.
    ("an update page that is not a list refuses", False,
     {"history": {"0": {"not": "a list"}}}),
    ("a transaction with no integer offset refuses", False,
     {"history": {"0": [{"update": {"Transaction": {"value": {
         "offset": "ten", "events": []}}}}]}}),
    ("an offset outside its requested window refuses", False,
     {"history": {"0": [tx(5000, created(5000, "AgendaProposal",
                                         proposal("00a", ROUND_A)))]}}),
    ("a duplicated offset across pages refuses", False,
     {"history": {"0": [tx(10, created(10, "AgendaProposal", proposal("00a", ROUND_A)))],
                  "100": [tx(10, created(10, "AgendaBallot", ballot("00c", ROUND_A)))]}}),
    ("sparse offsets with legitimate visibility gaps are fine", True,
     {"history": {"0": [tx(35, created(35, "AgendaProposal", proposal("00a", ROUND_A))),
                        tx(38, created(38, "AgendaProposal", proposal("00b", ROUND_B))),
                        tx(99, created(99, "SourceCheckpoint", {"_cid": "00s"}))]}}),

    # Unknown update variants must fail closed; the three documented
    # non-Transaction variants cannot carry contract effects and are benign.
    ("an unrecognised update variant refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [{"update": {"Mystery": {"value": {}}}}]})}),
    ("an update entry with no variant refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [{"update": {}}]})}),
    ("documented OffsetCheckpoint updates are benign", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         {"update": {"OffsetCheckpoint": {"value": {"offset": 150}}}}]})}),
    ("documented TopologyTransaction updates are benign", True,
     {"history": dict(BASE_HISTORY, **{"100": [
         {"update": {"TopologyTransaction": {"value": {"offset": 150}}}}]})}),

    # Finding 1: a Reassignment can move a contract between synchronizers
    # without a create or archive in this view, so it is NOT benign.
    ("a Reassignment update refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         {"update": {"Reassignment": {"value": {"offset": 150}}}}]})}),
    # Finding 1: more than one connected synchronizer makes reassignment
    # possible at all, so absence cannot be proven.
    ("two connected synchronizers refuse", False,
     {"synchronizers": {"connectedSynchronizers": [
         {"synchronizerAlias": "a", "synchronizerId": "local::one"},
         {"synchronizerAlias": "b", "synchronizerId": "local::two"}]}}),
    ("zero connected synchronizers refuse", False,
     {"synchronizers": {"connectedSynchronizers": []}}),
    ("a synchronizer with no id refuses", False,
     {"synchronizers": {"connectedSynchronizers": [{"synchronizerAlias": "a"}]}}),
    ("an unreadable synchronizer reply refuses", False,
     {"synchronizers": ["not", "an", "object"]}),
    ("an unreported runtime version refuses", False, {"version": {}}),

    # Finding 2: a malformed Transaction value must refuse, not be skipped.
    ("a Transaction with no value refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [{"update": {"Transaction": {}}}]})}),
    ("a Transaction with an empty value refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         {"update": {"Transaction": {"value": {}}}}]})}),
    ("a Transaction with a non-object value refuses", False,
     {"history": dict(BASE_HISTORY, **{"100": [
         {"update": {"Transaction": {"value": "nope"}}}]})}),

    # Finding 3: the ACS at `end` must reconcile against the history read.
    ("an active contract missing from history refuses", False,
     {"acs": [{"contractEntry": {"JsActiveContract": {"createdEvent": {
         "contractId": "00ghost", "offset": 10}}}}]}),
    ("active contracts all present in history reconcile", True,
     {"acs": [{"contractEntry": {"JsActiveContract": {"createdEvent": {
         "contractId": "00a", "offset": 10}}}}]}),
]

# Finding 3/4: the paged endpoint is preferred and its next_page_token is the
# server's own completion signal. These drive it directly.
PAGED_CASES = [
    ("paged endpoint: single final page completes", True,
     {"update_pages": [{"updates": [tx(10, created(10, "AgendaProposal",
                                                   proposal("00a", ROUND_A)))],
                        "lowestPageOffsetExclusive": 0,
                        "highestPageOffsetInclusive": 200}]}),
    ("paged endpoint: two pages with a continuation token complete", True,
     {"update_pages": [
         {"updates": [tx(10, created(10, "AgendaProposal", proposal("00a", ROUND_A)))],
          "lowestPageOffsetExclusive": 0, "highestPageOffsetInclusive": 100},
         {"updates": [tx(150, created(150, "AgendaProposal", proposal("00b", ROUND_B)))],
          "lowestPageOffsetExclusive": 100, "highestPageOffsetInclusive": 200}]}),
    ("paged endpoint: a gap between page bounds refuses", False,
     {"update_pages": [
         {"updates": [], "lowestPageOffsetExclusive": 0,
          "highestPageOffsetInclusive": 100},
         {"updates": [], "lowestPageOffsetExclusive": 120,
          "highestPageOffsetInclusive": 200}]}),
    ("paged endpoint: final page short of the end refuses", False,
     {"update_pages": [{"updates": [], "lowestPageOffsetExclusive": 0,
                        "highestPageOffsetInclusive": 150}]}),
    ("paged endpoint: missing bounds refuse", False,
     {"update_pages": [{"updates": []}]}),
    ("paged endpoint: bounds outside the range refuse", False,
     {"update_pages": [{"updates": [], "lowestPageOffsetExclusive": 0,
                        "highestPageOffsetInclusive": 9999}]}),
    ("paged endpoint: unexpected shape refuses", False,
     {"update_pages": [{"nope": 1}]}),
    ("paged endpoint: a mid-pagination failure refuses", False,
     {"fail_on": ["/v2/updates/get-updates-page"]}),
    ("paged endpoint: a 413 refuses without retrying", False,
     {"too_many": True}),
    # ACS continuation: tokens, and an offset that changes mid-pagination.
    ("acs endpoint: two pages with a continuation token reconcile", True,
     {"acs_pages": [
         {"activeContracts": [{"contractEntry": {"JsActiveContract": {
             "createdEvent": {"contractId": "00a", "offset": 10}}}}]},
         {"activeContracts": [{"contractEntry": {"JsActiveContract": {
             "createdEvent": {"contractId": "00b", "offset": 20}}}}]}]}),
    ("acs endpoint: an offset that changes mid-pagination refuses", False,
     {"acs_pages": [
         {"activeContracts": [], "activeAtOffset": 200},
         {"activeContracts": [], "activeAtOffset": 199}]}),
    ("paged endpoint: a repeated page token refuses early", False,
     {"repeat_token": True,
      "update_pages": [{"updates": [], "lowestPageOffsetExclusive": 0,
                        "highestPageOffsetInclusive": 100}]}),
]


def runner_cases():
    text = RUNNER.read_text()
    tail = text.split("recover_step4_marker()")[-1]
    return [
        ("step 4 accepts a third argument",
         'step) run_step "${2:-}" "${3:-}" ;;' in text),
        ("a selector routes to milestoneVoteFor", "SCRIPT=milestoneVoteFor" in text),
        ("recovery is an explicit operator command",
         "recover-step4-marker) recover_step4_marker ;;" in text),
        ("recovery refuses unless the checker succeeds",
         'python3 "$HERE/step4-marker-recovery.py"; then' in text),
        ("recovery deletes exactly one fixed marker path",
         'rm -f -- "$STATE/step-4.attempted" "$STEP4_META"' in text),
        ("recovery never deletes other steps' markers",
         'rm -f -- "$STATE/step-3.attempted"' not in tail),
        ("the one-shot guard is unchanged",
         '[ -e "$STATE/step-$NUMBER.attempted" ]' in text),
        ("the marker is still written before the script runs",
         ': > "$STATE/step-$NUMBER.attempted"' in text),
        # R3: the runner must bind the attempt to the configured log and offset.
        ("the attempt records the configured log path, not a guess",
         'STEP_LOG="$RAW_DIR/step-$NUMBER.log"' in text),
        ("the attempt records a byte offset into that log",
         '"log_offset": os.path.getsize(p)' in text),
        ("the attempt refuses when the log cannot be opened",
         'cannot open $STEP_LOG for append' in text),
        ("recovery refuses without attempt metadata",
         "no attempt metadata beside the step-4 marker" in text),
        ("recovery passes the recorded log to the checker",
         'BABYCDF_STEP4_LOG="$STEP4_LOG" python3' in text),
        ("down clears the attempt metadata too",
         '"$STATE"/step-4.attempted.attempt' in text),
        ("the selector temp file is cleaned by down",
         '"$STATE"/step-4-selector-*' in text),
        ("help documents the selector", "step 4 R" in text),
        ("help documents the bounded recovery", "recover-step4-marker" in text),
        ("help no longer promises an unproven rerun",
         "retain markers and rerun the same command" not in text),
    ]


def real_transport_case():
    """Run the checker end to end against the REAL local ledger.sh.

    This is the only case that proves the checker's argv convention matches
    the actual viewer: a stub ignores argv, so it would pass even when every
    GET is refused with "POST ... is not in the read-only allowlist".

    Everything it touches is a private temporary copy. The operator's state
    directory and marker are never read or written, and no mutation is made.

    What is asserted is TRANSPORT CORRECTNESS, not a particular verdict: the
    GET and POST paths must both be accepted by the real viewer, a complete
    history must be read, and the milestone rounds must be discovered. The
    verdict itself depends on the network. On the network observed while
    writing this, step 4 aborted at the PR184 prerequisite before inviting
    any ballot, so no milestone-540 ballot, execution or approval exists and
    the honest verdict is PROVEN SAFE for a synthetic marker. A refusal is
    equally acceptable here, as long as it is evidence-based rather than a
    transport or allowlist failure.
    """
    boot = DEMO / "compose" / "state" / "bootstrap.json"
    if not boot.exists():
        return ("real ledger.sh transport (skipped: no bootstrap)", None,
                "no bootstrap.json; a live participant is required")
    # A private validation tree carries a SYNTHETIC bootstrap whose opener
    # does not exist on any participant. Querying with it would prove nothing
    # about transport, so this case only runs where the opener is real.
    try:
        opener = json.loads(boot.read_text()).get("opener")
        listed = subprocess.run(["bash", str(DEMO / "ledger.sh"), "raw", "/v2/parties"],
                                capture_output=True, text=True, timeout=120)
        if listed.returncode != 0:
            return ("real ledger.sh transport (skipped: viewer unavailable)", None,
                    "ledger.sh could not list parties")
        payload = json.loads(listed.stdout)
        rows = payload if isinstance(payload, list) else payload.get("partyDetails", [])
        if opener not in {row.get("party") for row in rows}:
            return ("real ledger.sh transport (skipped: synthetic opener)", None,
                    "this tree's opener is not hosted on the participant")
    except (OSError, ValueError, subprocess.SubprocessError):
        return ("real ledger.sh transport (skipped: no live participant)", None,
                "the participant could not be reached read-only")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo = root / "demo"
        state = demo / "compose" / "state"
        state.mkdir(parents=True)
        # The real viewer and its helpers, plus the real opener, copied read-only.
        for name in ("ledger.sh", "http-acs-pages.py", RECOVERY.name):
            shutil.copy(DEMO / name, demo / name)
        shutil.copy(DEMO / "compose" / "state" / "bootstrap.json",
                    state / "bootstrap.json")
        shutil.copytree(DEMO / "compose" / ".cips", demo / "compose" / ".cips",
                        dirs_exist_ok=True)
        marker = state / "step-4.attempted"
        marker.write_text("")
        log = root / "step-4.log"
        log.write_text(ENTERED + "\n")
        (state / "step-4.attempted.attempt").write_text(json.dumps(
            {"log_path": os.path.realpath(str(log)), "log_offset": 0, "step": 4}))
        env = dict(os.environ, BABYCDF_STEP4_LOG=str(log),
                   BABYCDF_HTTP_VIA="host")
        done = subprocess.run([sys.executable, str(demo / RECOVERY.name)],
                              capture_output=True, text=True, timeout=600, env=env)
        out = (done.stdout + done.stderr).strip()
        head = (out.splitlines() or [""])[0]
        # The old bug: a GET sent with an empty body argument came back as
        # "POST ... is not in the read-only allowlist". Any of these means the
        # checker is not speaking to the viewer correctly.
        transport_broken = ("not in the read-only allowlist" in out
                            or "a ledger query failed" in out
                            or "was not valid JSON" in out
                            or "could not be read" in out
                            or "pruning state" in out)
        # Proof the real transport actually returned usable history: the
        # windows tiled the range, offsets were verified, rounds discovered.
        read_history = ("history pages" in out and "updates read" in out
                        and "acs reconciled" in out
                        and "milestone rounds" in out
                        and "synchronizer      exactly 1" in out)
        decided_on_evidence = done.returncode in (0, 2) and not transport_broken
        ok = decided_on_evidence and read_history and marker.exists()
        return ("real ledger.sh transport reads complete history and decides",
                ok, head)


def runner_behaviour():
    """Actually invoke recover_step4_marker and run_step's attempt binding.

    Substring checks over run-demo.sh cannot show what the function DOES, so
    these drive the real shell function with a stub checker on a private copy
    and assert the observable effect on the marker and its metadata. No live
    state, no container, no ledger.
    """
    results = []

    def harness(root, checker_exit, with_meta=True, meta_text=None):
        demo = root / "demo"
        state = demo / "compose" / "state"
        state.mkdir(parents=True)
        (root / "build").mkdir()
        shutil.copy(RUNNER, demo / "run-demo.sh")
        for helper in ("build-root.sh", "up-stage.sh"):
            source = DEMO.parent / helper
            if source.exists():
                shutil.copy(source, demo.parent / helper)
            elif (DEMO / helper).exists():
                shutil.copy(DEMO / helper, demo / helper)
        marker = state / "step-4.attempted"
        marker.write_text("")
        log = root / "step-4.log"
        log.write_text(ENTERED + "\n")
        if with_meta:
            (state / "step-4.attempted.attempt").write_text(
                meta_text if meta_text is not None else json.dumps(
                    {"log_path": os.path.realpath(str(log)),
                     "log_offset": 0, "step": 4}))
        # Stub checker: its exit code is the only thing the runner may act on.
        (demo / "step4-marker-recovery.py").write_text(
            "import sys\nprint('stub checker')\nsys.exit(%d)\n" % checker_exit)
        return demo, state, marker

    # 1. Checker succeeds: the marker and its metadata are cleared, nothing else.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, marker = harness(root, 0)
        other = state / "step-3.attempted"
        other.write_text("")
        done = subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                              cwd=demo, capture_output=True, text=True,
                              timeout=300,
                              env=dict(os.environ,
                                       BABYCDF_BUILD_ROOT=str(root / "build")))
        results.append(("recovery clears the marker when the checker succeeds",
                        done.returncode == 0 and not marker.exists()))
        results.append(("recovery clears the attempt metadata with it",
                        not (state / "step-4.attempted.attempt").exists()))
        results.append(("recovery leaves other steps' markers alone",
                        other.exists()))

    # 2. Checker refuses: the marker survives and the command fails.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, marker = harness(root, 2)
        done = subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                              cwd=demo, capture_output=True, text=True,
                              timeout=300,
                              env=dict(os.environ,
                                       BABYCDF_BUILD_ROOT=str(root / "build")))
        results.append(("recovery keeps the marker when the checker refuses",
                        done.returncode != 0 and marker.exists()))
        results.append(("recovery keeps the metadata when the checker refuses",
                        (state / "step-4.attempted.attempt").exists()))

    # 3. Uncertainty: no metadata at all. The runner must refuse before even
    #    consulting the checker, because the log span is unknown.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, marker = harness(root, 0, with_meta=False)
        done = subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                              cwd=demo, capture_output=True, text=True,
                              timeout=300,
                              env=dict(os.environ,
                                       BABYCDF_BUILD_ROOT=str(root / "build")))
        out = done.stdout + done.stderr
        results.append(("recovery refuses with no attempt metadata",
                        done.returncode != 0 and marker.exists()
                        and "predates bounded recovery" in out))

    # 4. Uncertainty: metadata present but unreadable. Still a refusal, even
    #    though the stub checker would have succeeded.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, marker = harness(root, 0, meta_text="{not json")
        done = subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                              cwd=demo, capture_output=True, text=True,
                              timeout=300,
                              env=dict(os.environ,
                                       BABYCDF_BUILD_ROOT=str(root / "build")))
        results.append(("recovery refuses on unreadable metadata",
                        done.returncode != 0 and marker.exists()))

    # 4b. Cross-process exclusion. A lock held by a LIVE process must block
    #     recovery; a lock whose owner is dead must be reported stale and
    #     never broken; a lock with an unreadable owner is treated as held.
    #     None of these may delete a marker.
    for label, owner, expect in (
            ("a live lock holder blocks recovery", "live", "blocked"),
            ("a stale lock is reported, never broken", "dead", "stale"),
            ("a lock with an unreadable owner is treated as held",
             "garbage", "held")):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            demo, state, marker = harness(root, 0)
            lock = state / "step-4.lock"
            lock.mkdir()
            if owner == "live":
                holder = subprocess.Popen([sys.executable, "-c",
                                           "import time; time.sleep(60)"])
                (lock / "pid").write_text("%d\n" % holder.pid)
            elif owner == "dead":
                spent = subprocess.Popen([sys.executable, "-c", "pass"])
                spent.wait()
                (lock / "pid").write_text("%d\n" % spent.pid)
            else:
                (lock / "pid").write_text("not-a-pid\n")
            try:
                done = subprocess.run(
                    ["sh", "./run-demo.sh", "recover-step4-marker"], cwd=demo,
                    capture_output=True, text=True, timeout=300,
                    env=dict(os.environ,
                             BABYCDF_BUILD_ROOT=str(root / "build")))
            finally:
                if owner == "live":
                    holder.kill()
                    holder.wait()
            out = done.stdout + done.stderr
            wording = {"blocked": "holds", "stale": "stale lock",
                       "held": "cannot be"}[expect]
            results.append((label, done.returncode != 0 and marker.exists()
                            and lock.exists() and wording in out))

    # 4c. The lock is released on a successful recovery, so a later run is
    #     not permanently blocked by a lock this process took.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, marker = harness(root, 0)
        subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                       cwd=demo, capture_output=True, text=True, timeout=300,
                       env=dict(os.environ,
                                BABYCDF_BUILD_ROOT=str(root / "build")))
        results.append(("the lock is released after a successful recovery",
                        not marker.exists()
                        and not (state / "step-4.lock").exists()))

    # 4d. The lock is released when recovery refuses, so a refusal does not
    #     wedge the operator out of retrying read-only checks.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, marker = harness(root, 2)
        subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                       cwd=demo, capture_output=True, text=True, timeout=300,
                       env=dict(os.environ,
                                BABYCDF_BUILD_ROOT=str(root / "build")))
        results.append(("the lock is released after a refused recovery",
                        marker.exists()
                        and not (state / "step-4.lock").exists()))

    # 4e. run_step must not leak the lock or leave a blocking marker when a
    #     preparation step fails. Each case makes exactly one thing fail and
    #     asserts that neither the lock directory nor the marker survives.
    def step_harness(root, *, raw_writable=True, meta_ok=True, mktemp_ok=True):
        demo = root / "demo"
        state = demo / "compose" / "state"
        state.mkdir(parents=True)
        raw = root / "raw"
        raw.mkdir()
        (root / "build").mkdir()
        shutil.copy(RUNNER, demo / "run-demo.sh")
        if (DEMO.parent / "build-root.sh").exists():
            shutil.copy(DEMO.parent / "build-root.sh", demo.parent / "build-root.sh")
        for name in ("up-stage.sh", "format-log.py", "summarize-agent-log.py"):
            if (DEMO / name).exists():
                shutil.copy(DEMO / name, demo / name)
        (demo / "package-gate.py").write_text("pass\n")
        (state / "bootstrap.json").write_text(json.dumps({"opener": OPENER}))
        (state / "run-id.json").write_text(json.dumps("babycdf-synthetic"))
        dar = demo / ".daml" / "dist"
        dar.mkdir(parents=True)
        (dar / "cap-example-babycdf-demo-0.1.0.dar").write_bytes(b"x")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        (bin_dir / "dpm").write_text("#!/bin/sh\nexit 0\n")
        (bin_dir / "dpm").chmod(0o755)
        if not raw_writable:
            # A file where the log directory must be: the open must fail.
            raw.rmdir()
            raw.write_text("not a directory")
        if not meta_ok:
            # python3 that fails only for the metadata write.
            (bin_dir / "python3").write_text(
                "#!/bin/sh\ncase \"$*\" in *log_offset*) exit 3 ;; esac\n"
                "exec %s \"$@\"\n" % sys.executable)
            (bin_dir / "python3").chmod(0o755)
        if not mktemp_ok:
            (bin_dir / "mktemp").write_text("#!/bin/sh\nexit 4\n")
            (bin_dir / "mktemp").chmod(0o755)
        env = dict(os.environ,
                   PATH="%s:%s" % (bin_dir, os.environ.get("PATH", "")),
                   BABYCDF_RAW_LOG_DIR=str(raw),
                   BABYCDF_BUILD_ROOT=str(root / "build"))
        done = subprocess.run(["sh", "./run-demo.sh", "step", "4"], cwd=demo,
                              capture_output=True, text=True, timeout=300,
                              env=env)
        return done, state

    for label, kwargs, expect_marker in (
            ("run_step releases the lock on success", {}, True),
            ("a log that cannot be opened leaves no lock or marker",
             {"raw_writable": False}, False),
            ("a metadata write failure leaves no lock or marker",
             {"meta_ok": False}, False),
            ("an mktemp failure leaves no lock or marker",
             {"mktemp_ok": False}, False)):
        with tempfile.TemporaryDirectory() as tmp:
            done, state = step_harness(Path(tmp), **kwargs)
            lock_gone = not (state / "step-4.lock").exists()
            marker = (state / "step-4.attempted").exists()
            results.append((label, lock_gone and marker == expect_marker))

    # 4f. Signals must not release a lock while the child still runs, and a
    #     lock that cannot be cleaned up must be LEFT as stale rather than
    #     silently freed. Driven on a private copy with a stub dpm that
    #     sleeps, so there is a real child to protect.
    def signal_harness(root, *, sleep_seconds=30):
        demo = root / "demo"
        state = demo / "compose" / "state"
        state.mkdir(parents=True)
        raw = root / "raw"
        raw.mkdir()
        (root / "build").mkdir()
        shutil.copy(RUNNER, demo / "run-demo.sh")
        if (DEMO.parent / "build-root.sh").exists():
            shutil.copy(DEMO.parent / "build-root.sh", demo.parent / "build-root.sh")
        for name in ("up-stage.sh", "format-log.py", "summarize-agent-log.py"):
            if (DEMO / name).exists():
                shutil.copy(DEMO / name, demo / name)
        (demo / "package-gate.py").write_text("pass\n")
        (state / "bootstrap.json").write_text(json.dumps({"opener": OPENER}))
        (state / "run-id.json").write_text(json.dumps("babycdf-synthetic"))
        dar = demo / ".daml" / "dist"
        dar.mkdir(parents=True)
        (dar / "cap-example-babycdf-demo-0.1.0.dar").write_bytes(b"x")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        (bin_dir / "dpm").write_text("#!/bin/sh\nsleep %d\n" % sleep_seconds)
        (bin_dir / "dpm").chmod(0o755)
        env = dict(os.environ,
                   PATH="%s:%s" % (bin_dir, os.environ.get("PATH", "")),
                   BABYCDF_RAW_LOG_DIR=str(raw),
                   BABYCDF_BUILD_ROOT=str(root / "build"))
        return demo, state, env

    # SIGTERM with a DESCENDANT that outlives the render shell. The stub dpm
    # spawns a grandchild that survives, writing its pid where the test can
    # read it, so this reproduces the real shape: reaping the render subshell
    # does not prove the submitter stopped. The attempt must therefore be
    # sticky-uncertain and the lock must be RETAINED.
    def descendant_harness(root):
        demo, state, env = signal_harness(root, sleep_seconds=0)
        bin_dir = root / "bin"
        # dpm records a long-lived grandchild's pid, then exits itself.
        (bin_dir / "dpm").write_text(
            '#!/bin/sh\n'
            'sleep 120 &\n'
            'echo $! > "' + str(root / "descendant.pid") + '"\n'
            'exit 0\n')
        (bin_dir / "dpm").chmod(0o755)
        return demo, state, env

    def reap_exact(pid_file):
        """Terminate ONLY the pid this harness recorded. No broad kill."""
        try:
            pid = int(Path(pid_file).read_text().strip())
        except (OSError, ValueError):
            return
        for sig in ("-TERM", "-KILL"):
            subprocess.run(["kill", sig, str(pid)], capture_output=True)
            time.sleep(0.2)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, env = descendant_harness(root)
        pid_file = root / "descendant.pid"
        runner = subprocess.Popen(["sh", "./run-demo.sh", "step", "4"], cwd=demo,
                                  stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, env=env)
        for _ in range(400):
            if pid_file.exists():
                break
            time.sleep(0.05)
        try:
            alive = False
            if pid_file.exists():
                pid = int(pid_file.read_text().strip())
                alive = subprocess.run(["kill", "-0", str(pid)],
                                       capture_output=True).returncode == 0
            results.append(("a long-lived descendant outlives the render shell",
                            alive))
            runner.terminate()
            runner.wait(timeout=120)
            # Sticky-uncertain: the lock must still be there.
            results.append(("interruption retains the lock, not released on "
                            "render-shell reap", (state / "step-4.lock").exists()))
            results.append(("the uncertain state is recorded on disk",
                            (state / "step-4.lock" / "uncertain").exists()))
            results.append(("the marker is kept after an uncertain attempt",
                            (state / "step-4.attempted").exists()))
            # A second step 4 cannot acquire while the attempt is uncertain.
            again = subprocess.run(["sh", "./run-demo.sh", "step", "4"], cwd=demo,
                                   capture_output=True, text=True, timeout=300,
                                   env=env)
            results.append(("a later step 4 cannot acquire an uncertain lock",
                            again.returncode != 0
                            and (state / "step-4.lock").exists()))
            # Recovery likewise refuses, and never clears the marker.
            rec = subprocess.run(["sh", "./run-demo.sh", "recover-step4-marker"],
                                 cwd=demo, capture_output=True, text=True,
                                 timeout=300, env=env)
            results.append(("recovery refuses while the attempt is uncertain",
                            rec.returncode != 0
                            and (state / "step-4.attempted").exists()))
        finally:
            reap_exact(pid_file)

    # Repeated cleanup must be idempotent: a signal trap followed by the EXIT
    # trap must not clear the sticky state and must not release the lock.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, env = descendant_harness(root)
        pid_file = root / "descendant.pid"
        probe = root / "probe.sh"
        # Drive the real helpers directly: mark uncertain, then run cleanup
        # twice, exactly as a signal trap plus the EXIT trap would.
        probe.write_text(
            'set -eu\n'
            'STATE="%s"\n'
            'HERE="%s"\n'
            % (demo / "compose" / "state", demo)
            + '. ./helpers.sh\n'
              'step4_lock_acquire >/dev/null 2>&1\n'
              'sleep 120 &\n'
              'STEP4_CHILD=$!\n'
              'echo "$STEP4_CHILD" > "%s"\n' % (root / "probe-child.pid")
            +
              'step4_cleanup signal >/dev/null 2>&1\n'
              'step4_cleanup >/dev/null 2>&1\n'
              'step4_cleanup >/dev/null 2>&1\n'
              'step4_lock_release >/dev/null 2>&1\n'
              'if [ -d "$STATE/step-4.lock" ]; then echo RETAINED; else echo RELEASED; fi\n'
              'if [ -e "$STATE/step-4.lock/uncertain" ]; then echo STICKY; else echo CLEARED; fi\n')
        runner_text = RUNNER.read_text()
        helpers = []
        for name in ("step4_mark_uncertain", "step4_cleanup",
                     "step4_lock_acquire", "step4_lock_release"):
            found = re.search(r'(?ms)^%s\(\) \{.*?^\}\n' % name, runner_text)
            if found:
                helpers.append(found.group(0))
        (demo / "helpers.sh").write_text(
            "STEP4_LOCK=''\nSTEP4_CHILD=''\nSTEP4_UNCERTAIN=''\n"
            + "".join(helpers))
        out = subprocess.run(["sh", str(probe)], cwd=demo, capture_output=True,
                             text=True, timeout=300, env=env).stdout
        results.append(("repeated cleanup plus an explicit release still retains",
                        "RETAINED" in out))
        results.append(("the sticky uncertain flag survives repeated cleanup",
                        "STICKY" in out))
        reap_exact(root / "probe-child.pid")

    # A normal, uninterrupted success must still release the lock.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, env = signal_harness(root, sleep_seconds=0)
        done = subprocess.run(["sh", "./run-demo.sh", "step", "4"], cwd=demo,
                              capture_output=True, text=True, timeout=300,
                              env=env)
        results.append(("an uninterrupted step 4 releases the lock",
                        not (state / "step-4.lock").exists()))

    # 4g. Finding C: `down` must clean up a leaked lock, pid first then the
    #     directory. Driven against a FAKE compose on a private tree; the
    #     live stack is never touched.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo, state, env = signal_harness(root)
        lock = state / "step-4.lock"
        lock.mkdir()
        (lock / "pid").write_text("999999\n")
        (state / "step-4.attempted").write_text("")
        # `down` refuses unless the pinned Compose source is present, so the
        # private tree gets an empty stand-in at the exact pinned path. This
        # is a fixture, not the real vendored tree.
        pinned = (demo / "compose" / ".cips"
                  / "8e36c20e5106014efb8348f749d9535afbc48921"
                  / "cn-credentials-standard" / "demo")
        pinned.mkdir(parents=True)
        (pinned / "compose.yaml").write_text("services: {}\n")
        (demo / "compose" / "compose.yaml").write_text("services: {}\n")
        bin_dir = root / "bin"
        # A fake docker that records its arguments and never touches anything.
        (bin_dir / "docker").write_text(
            '#!/bin/sh\necho "$*" >> "' + str(root / "docker-calls")
            + '"\nexit 0\n')
        (bin_dir / "docker").chmod(0o755)
        done = subprocess.run(["sh", "./run-demo.sh", "down"], cwd=demo,
                              capture_output=True, text=True, timeout=300,
                              env=env)
        calls = (root / "docker-calls").read_text() if (root / "docker-calls").exists() else ""
        results.append(("down removes a leaked lock entirely, pid then dir",
                        not lock.exists()))
        results.append(("down used the fake compose, never a live one",
                        "compose" in calls and "down" in calls))
        results.append(("down also cleared the step-4 marker",
                        not (state / "step-4.attempted").exists()))

    # 5. run_step must bind the attempt: marker plus metadata naming the
    #    configured log with a byte offset. Driven with a stub dpm on PATH.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        demo = root / "demo"
        state = demo / "compose" / "state"
        state.mkdir(parents=True)
        raw = root / "raw"
        raw.mkdir()
        shutil.copy(RUNNER, demo / "run-demo.sh")
        for name in ("package-gate.py", "format-log.py", "summarize-agent-log.py"):
            if (DEMO / name).exists():
                shutil.copy(DEMO / name, demo / name)
        for helper in ("build-root.sh",):
            if (DEMO.parent / helper).exists():
                shutil.copy(DEMO.parent / helper, demo.parent / helper)
        if (DEMO / "up-stage.sh").exists():
            shutil.copy(DEMO / "up-stage.sh", demo / "up-stage.sh")
        (state / "bootstrap.json").write_text(json.dumps({"opener": OPENER}))
        (state / "run-id.json").write_text(json.dumps("babycdf-synthetic"))
        dar = demo / ".daml" / "dist"
        dar.mkdir(parents=True)
        dar_file = dar / "cap-example-babycdf-demo-0.1.0.dar"
        dar_file.write_bytes(b"synthetic dar bytes")
        import hashlib
        (state / "demo-dar.json").write_text(json.dumps(
            {"main_package_id": "synthetic",
             "sha256": hashlib.sha256(b"synthetic dar bytes").hexdigest()}))
        # An existing log, so the recorded offset must be non-zero.
        prior = "earlier attempt output\n" + SUBMITTING + "\n"
        (raw / "step-4.log").write_text(prior)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        stub_dpm = bin_dir / "dpm"
        stub_dpm.write_text("#!/bin/sh\nprintf 'stub dpm\\n'\nexit 1\n")
        stub_dpm.chmod(0o755)
        env = dict(os.environ,
                   PATH="%s:%s" % (bin_dir, os.environ.get("PATH", "")),
                   BABYCDF_RAW_LOG_DIR=str(raw),
                   BABYCDF_BUILD_ROOT=str(root / "build"))
        (root / "build").mkdir()
        subprocess.run(["sh", "./run-demo.sh", "step", "4"], cwd=demo,
                       capture_output=True, text=True, timeout=300, env=env)
        meta = state / "step-4.attempted.attempt"
        ok_written = (state / "step-4.attempted").exists() and meta.exists()
        recorded = {}
        if meta.exists():
            try:
                recorded = json.loads(meta.read_text())
            except ValueError:
                recorded = {}
        results.append(("run_step writes the marker and attempt metadata",
                        ok_written))
        results.append(("the metadata names the CONFIGURED log directory",
                        os.path.realpath(recorded.get("log_path", "")) ==
                        os.path.realpath(str(raw / "step-4.log"))))
        results.append(("the metadata records the pre-attempt byte offset",
                        recorded.get("log_offset") == len(prior.encode())))
    return results


def main():
    failures = []
    print("runner contract")
    for name, ok in runner_cases():
        print("  %-56s %s" % (name[:56], "ok" if ok else "FAIL"))
        if not ok:
            failures.append(name)

    print("")
    print("marker recovery, fail-closed")
    for name, expect_safe, kwargs in CASES:
        safe, marker, head = verdict(**kwargs)
        intact = marker or not kwargs.get("marker", True)
        ok = safe == expect_safe and intact
        print("  %-56s want=%-7s got=%-7s %s"
              % (name[:56], "safe" if expect_safe else "refuse",
                 "safe" if safe else "refuse", "ok" if ok else "FAIL"))
        if not ok:
            failures.append("%s (%s)" % (name, head))

    print("")
    print("paged endpoint, server-reported completeness")
    for name, expect_safe, kwargs in PAGED_CASES:
        safe, marker, head = verdict(**kwargs)
        ok = safe == expect_safe and marker
        print("  %-56s want=%-7s got=%-7s %s"
              % (name[:56], "safe" if expect_safe else "refuse",
                 "safe" if safe else "refuse", "ok" if ok else "FAIL"))
        if not ok:
            failures.append("%s (%s)" % (name, head))

    print("")
    print("runner behaviour, actually invoked (stub checker, private state)")
    behaviour = runner_behaviour()
    for name, ok in behaviour:
        print("  %-56s %s" % (name[:56], "ok" if ok else "FAIL"))
        if not ok:
            failures.append(name)

    print("")
    print("real transport, end to end (no stub, private state only)")
    name, ok, note = real_transport_case()
    if ok is None:
        print("  %-56s SKIPPED (%s)" % (name[:56], note))
        real_counted = 0
    else:
        print("  %-56s %s" % (name[:56], "ok" if ok else "FAIL"))
        real_counted = 1
        if not ok:
            failures.append("%s (%s)" % (name, note))

    print("")
    total = (len(CASES) + len(PAGED_CASES) + len(runner_cases())
             + len(behaviour) + real_counted)
    print("%d checks, %d failures" % (total, len(failures)))
    for name in failures:
        print("  FAIL %s" % name)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
