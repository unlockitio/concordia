#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Strict offline guard for the business narration in Live.daml. It runs no
# ledger, no sandbox, no container and no build: it only reads source text, so
# it is safe to run while a demo is live. Run it from anywhere:
#
#   sh examples/governance/babycdf/demo/test-narration.sh
#   sh examples/governance/babycdf/demo/test-narration.sh <git-revision>
#
# The narration added for the demo deck must explain the governance story
# without being able to change it. This script enforces that by construction
# rather than by blacklist:
#
#   1. Each of the five narration helpers must match its exact expected
#      definition, character for character after whitespace folding. The body
#      may only be `debug` applied to a Text built from the helper's own
#      parameters. Anything else, including an extra statement, a changed label
#      or a second effect, fails. A blacklist of forbidden words could be
#      evaded; an exact shape cannot.
#   2. `roundName` is validated separately as a pure Text function: it must
#      declare the type `Text -> Text` and must not mention Script, submit,
#      query, exercise, create, debug, assert or abort anywhere in its span.
#   3. The executable logic must equal the logic this guard was last reviewed
#      against, identified by the SPEC below and pinned by digest. Only the
#      exact validated definition spans and standalone narration call
#      statements are removed before digesting; every other difference changes
#      the digest, including a changed case arm.
#   4. The numbers the narration says out loud must equal the constants the
#      code votes with, and those must be consistent with the governance rule
#      in the impl package. A demo that says "three" while casting four votes
#      would pass checks 1-3 and still mislead a room, so it is checked here.
#
# WHAT THIS GUARD DOES AND DOES NOT PROVE. Checks 1, 2 and 4 are absolute: they
# hold on this working tree alone. Check 3 pins the executable logic to one
# reviewed revision of it, named by SPEC_VERSION. It is NOT a claim that the
# logic never changed: when governance behaviour is deliberately revised, the
# SPEC_VERSION and the digest are both updated in the same change, and the diff
# against a chosen git revision is printed for a human to read. That diff is
# reported as information, never as a pass, so this guard can no longer be used
# to assert "narration-only" about a change that moved behaviour.
#
# Usage:
#   sh examples/governance/babycdf/demo/test-narration.sh
#   sh examples/governance/babycdf/demo/test-narration.sh <git-revision>
#     ^ additionally prints the executable-logic diff against that revision.
#   BABYCDF_PRINT_LOGIC_DIGEST=1 sh .../test-narration.sh
#     ^ prints the current digest, for updating SPEC_DIGEST after a reviewed
#       behaviour change. Printing it is not approving it.

set -eu

HERE=$(CD=$(dirname "$0") && cd "$CD" && pwd)
LIVE="$HERE/daml/Cap/Examples/BabyCdf/Live.daml"
# Additional source-only invariants cover ordering and runner dispatch, which
# the Live.daml digest alone cannot protect.
python3 "$HERE/check-workflow.py" "$HERE"
SELECTOR="$HERE/daml/Cap/Examples/BabyCdf/LiveVote.daml"
RULE="$HERE/../impl/daml/Cap/Examples/BabyCdf/RuleVote.daml"
BASE_REV=${1:-}
REPO_REL="examples/governance/babycdf/demo/daml/Cap/Examples/BabyCdf/Live.daml"

# The reviewed state of the executable governance logic in Live.daml.
#
# SPEC_VERSION names the behaviour; SPEC_DIGEST pins the exact logic lines that
# implement it. Both change together, deliberately, when behaviour changes.
#
#   quorum-of-three-core-v1
#     Steps 2, 3 and 4 are carried by exactly three permanent (core) members
#     voting Yes, with the remaining four members absent; step 6 case 2 shows
#     two core Yes votes and five absences lapsing for want of quorum. All
#     seven ballots are always issued and presented, and credentials are
#     presented only for the members who took part.
#
#   quorum-of-three-core-v2-presentation
#     Same governance behaviour as v1, now with phase-structured presentation
#     output and ledger-derived evidence blocks. The governance operations are
#     byte-identical to v1: validated by an operation-level equivalence
#     extraction kept alongside this change, whose only difference is three
#     Count constructors inside the new READ-ONLY evidence tally. The added
#     code is queries (queryInterface/query) and log formatting; no submit,
#     exercise, assertion, verdict or quorum constant changed.
#
#   quorum-of-three-core-v3-ordered-evidence
#     A DELIBERATE REORDER, not an equivalent change, and the only reason the
#     digest moved: ballot issuance is now a separate statement
#     (`inviteBallotsFor`) sequenced BEFORE the "after ballots issued, before
#     any cast" snapshot. Previously that snapshot ran before `InviteBallots`
#     executed, so its label described a moment that had not happened. The set
#     of governance operations is unchanged -- same InviteBallots per round,
#     same casts, same resolves, same assertions -- but their ORDER relative
#     to the read-only snapshots is different, so no "operations unchanged"
#     claim is made for this step. Also: a zero VoteRecord count is reported
#     neutrally, with the consumption explanation moved to a post-resolution
#     helper that has actually observed a resolve.
#     Order is proven by querying the ledger per stage in the Daml suite, not
#     by inspecting labels or source order.
#   quorum-of-three-core-v4-single-round-optional-stale
#     DELIBERATE workflow restructure, not an unchanged-logic claim: step 2
#     completes one agenda before the next main step. The stale v1 agenda is
#     newly proposed, voted and resolved in opt-step 1 after v2 activation;
#     its execution is refused against the live registry snapshot. It never
#     advances the main anchor. INITIAL/FINAL STATE query visible ledger state
#     around each main step (step 1 lists participant parties/users instead).
# Identity revision: reader is opener-only CanReadAs; distinct Intake user has
# CanActAs opener. Exact rights readback and Intake state fields are protected.
# Reversing only that identity delta reproduces the v4 source and digest.
# v6 is an intentional security-fixture split, NOT an equivalence claim for
# the old step 6. Main steps 2-5 and opt1 are unchanged. Opt2-5 each use a
# bootstrap-only anchor lookup, their own rounds, and return runId without
# advancing the anchor. Outsider authorization now supplies opener readAs and
# asserts the proposer guard; credential privacy is queried, not authority.
# Static-time tests inject setTime only at the same close-window boundary.
# v7 intentionally replaces successful direct member ProposeAgenda in steps 3/4
# with opener-signed IntakeRequest creation and bounded ledger proof polling.
# Java initiates the consuming OpenAgenda bridge; Daml derives a fresh window.
# Existing grant/illustrative-milestone round IDs and all voting/resolution remain.
# The unpinned milestone negative-path direct exercise remains intentional.
# This is NOT a narration-only/equivalence claim. The incremental source diff
# against the captured dirty v6 baseline accompanies the implementation evidence.
# v8 extracts pure evidence validation/request construction for negative tests,
# checks action opener as well as proposal identity, and deliberately requires
# 10 seconds remaining before voting with a minimum requested window of 15.
# This fail-closed budget change is not equivalence; transaction window still
# comes only from Daml getTime. Timeout preserves pending request for recovery.
# v9 adds one read-only opener SourceItem query before each curated agenda in
# steps 3/4: active contracts, distinct unified keys and proposal/milestone kinds.
# Zero is neutral, sync completeness unknown; no sweep wait or governance change.
# Client logs remain separate. Offline checkpoint tests exercise this count seam.
# v11 receives both RuleRefs through bootstrap script input and carries the
# proposed reference in the signed anchor. Rule text hashes are runtime data.
# v12 queries the active rule for reference-only narration; no clause text.
# v13 changes only the read-only catalogue count template to the impl credential.
# Voting, agenda creation, authority, and narration remain v12 semantics (S1b deferred).
# v14 renames the emitted read-only catalogue label; no governance semantics change.
# v15 credential-bound source agendas copy immutable snapshots/pins and durable
# round receipts. Step4 missing delivery is blocked_pin, never illustrative approval.
# Architecture, RuleRef, seven-member electorate and counting formula stay unchanged.
# v19 deliberately changes source casting: verified existing rounds, pending/resume,
# and atomic opener phase checkpoints. RuleVote/Rights and step 2 are unchanged.
# v20 validates existing ballot sets and vote compatibility before checkpoint creation.
# v23 permits valid blocked milestone ballots and reports no executable approval.
# Proposal pin requirements and on-ledger execution barriers remain unchanged.
SPEC_VERSION='v23-trigger-only-milestone-voting'
# Authorized v21 behavior change, not equivalence to v20: five primary seats,
# three YES early approval, rights through close, configurable fresh bootstrap.
SPEC_DIGEST='5296cc8c8a4be56e09b733b5b218ed8a1f5df849b69115d69489839fc8304fcf'

for required in "$LIVE" "$SELECTOR" "$RULE"; do
  if [ ! -f "$required" ]; then
    echo "FAIL: cannot find $required" >&2
    exit 1
  fi
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM

BASELINE=""
if [ -n "$BASE_REV" ] && git -C "$HERE" rev-parse --git-dir >/dev/null 2>&1; then
  if git -C "$HERE" show "$BASE_REV:$REPO_REL" > "$WORK/baseline.daml" 2>/dev/null; then
    BASELINE="$WORK/baseline.daml"
  fi
fi

GUARD="$WORK/guard.py"
cat > "$GUARD" <<'PYTHON'
"""Strict narration guard. See the shell header for the contract."""
import re
import sys

# Each narration helper, with the exact body it is allowed to have. The body is
# compared after collapsing runs of whitespace, so line wrapping is free but the
# expression is not: `debug` applied to one Text built from the parameters.
HELPERS = {
    "business": 'debug ("[STEP " <> show step <> "][BUSINESS] " <> line)',
    "voteNarration": 'debug ("[STEP " <> show step <> "][VOTE] " <> line)',
    "decision": 'debug ("[STEP " <> show step <> "][DECISION] " <> line)',
    "outcomeLine": 'debug ("[STEP " <> show step <> "][RESULT] " <> line)',
    "expectedRefusal": 'debug ("[STEP " <> show step <> "][EXPECTED-REFUSAL] " <> line)',
    # Presentation structure. Same log-only contract: a heading must not be
    # able to submit, query or assert anything, and `evidenceLine` only
    # formats a string the caller derived from its own ledger query.
    "stepHeading": (
        'debug ("[STEP " <> show step <> "][PHASE] \\n################ STEP "'
        " <> show step <> \": \" <> title <> \" ################\")"
    ),
    "phase": (
        'debug ("[STEP " <> show step <> "][PHASE] \\n########## " <> title'
        ' <> " ##########")'
    ),
    "evidenceLine": 'debug ("[STEP " <> show step <> "][EVIDENCE] " <> line)',
}
# Most helpers take `line`; the heading helpers take `title`. Both are plain
# Text, so the shape check is the same with a different parameter name.
PARAMETERS = {"stepHeading": "title", "phase": "title"}
SIGNATURE = "Int -> Text -> Script ()"
PURE = "roundName"
PURE_SIGNATURE = "Text -> Text"
# roundName's span is removed before the logic comparison, so a changed case arm
# would otherwise be invisible. Pin the whole body here instead: any edit to a
# mapping, including a renamed round, must fail this check.
PURE_BODY = (
    'roundName roundId = let suffix = case DA.List.last (Text.splitOn ":" roundId) of'
    " name -> name"
    " in case suffix of"
    ' "rule-v2" -> "rule change"'
    ' "stale" -> "duplicate"'
    ' "grant" -> "grant"'
    ' "illustrative-milestone" -> "milestone"'
    ' "blocked-foreign" -> "bad-credential test"'
    ' "blocked-quorum" -> "quorum test"'
    ' "blocked-double" -> "double-vote test"'
    " _ -> suffix"
)
# A pure text helper must stay pure: no ledger vocabulary at all.
PURE_FORBIDDEN = re.compile(
    r"\b(Script|submit|trySubmit|query|queryContractId|exercise|exerciseCmd"
    r"|create|createCmd|archiveCmd|debug|assert|assertMsg|abort|error|getTime)\b"
)
# A narration call used as a value rather than as a standalone statement. A
# standalone call occupies its line from the first non-space character; anything
# to its left, such as a bind, an operator or an opening paren, means the result
# is being consumed and could influence control flow.
CALL_ANYWHERE = re.compile(r"(?:" + "|".join(HELPERS) + r")\s+\d+\s")
STANDALONE = re.compile(r"^\s*(?:" + "|".join(HELPERS) + r")\s+\d+\s")


def fold(text):
    return " ".join(text.split())


def balance(line):
    """Parenthesis balance of a line, ignoring parens inside string literals."""
    depth, in_string, escaped = 0, False, False
    for ch in line:
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
    return depth


def top_level_spans(lines, name):
    """Index spans of every top-level definition or signature of `name`.

    A definition starts at column zero with the name and continues through its
    indented continuation lines. Returns a list of (start, end) half-open spans.
    """
    spans, i = [], 0
    pattern = re.compile(r"^" + re.escape(name) + r"\b")
    while i < len(lines):
        if pattern.match(lines[i]):
            start = i
            depth = balance(lines[i])
            i += 1
            while i < len(lines) and (depth != 0 or lines[i][:1] in (" ", "\t")):
                if not lines[i].strip():
                    break
                depth += balance(lines[i])
                i += 1
            spans.append((start, i))
            continue
        i += 1
    return spans


def check_helpers(lines):
    """Every helper must have exactly one signature and one exact definition."""
    failures = []
    for name, expected_body in HELPERS.items():
        spans = top_level_spans(lines, name)
        texts = [fold("\n".join(lines[a:b])) for a, b in spans]
        signatures = [t for t in texts if re.match(r"^" + name + r"\s*:\s", t)]
        definitions = [t for t in texts if t not in signatures]
        if len(signatures) != 1:
            failures.append(
                "%s: expected exactly 1 type signature, found %d"
                % (name, len(signatures))
            )
        elif fold(signatures[0]) != fold("%s : %s" % (name, SIGNATURE)):
            failures.append(
                "%s: signature is %r, expected %r"
                % (name, signatures[0], "%s : %s" % (name, SIGNATURE))
            )
        if len(definitions) != 1:
            failures.append(
                "%s: expected exactly 1 definition, found %d" % (name, len(definitions))
            )
            continue
        wanted = fold("%s step %s = %s"
                      % (name, PARAMETERS.get(name, "line"), expected_body))
        if definitions[0] != wanted:
            failures.append(
                "%s: definition is not the exact log-only shape\n"
                "      found:    %s\n      expected: %s"
                % (name, definitions[0], wanted)
            )
    return failures


def check_pure(lines):
    """roundName must be a pure Text -> Text function."""
    failures = []
    spans = top_level_spans(lines, PURE)
    texts = [fold("\n".join(lines[a:b])) for a, b in spans]
    signatures = [t for t in texts if re.match(r"^" + PURE + r"\s*:\s", t)]
    definitions = [t for t in texts if t not in signatures]
    if len(signatures) != 1 or fold(signatures[0]) != fold(
        "%s : %s" % (PURE, PURE_SIGNATURE)
    ):
        failures.append(
            "%s: expected exactly one signature %r, found %r"
            % (PURE, "%s : %s" % (PURE, PURE_SIGNATURE), signatures)
        )
    if len(definitions) != 1:
        failures.append(
            "%s: expected exactly 1 definition, found %d" % (PURE, len(definitions))
        )
    for body in definitions:
        hit = PURE_FORBIDDEN.search(body)
        if hit:
            failures.append(
                "%s: pure text helper mentions %r; it must touch no ledger"
                % (PURE, hit.group(0))
            )
        elif body != fold(PURE_BODY):
            failures.append(
                "%s: body differs from the pinned round-name mapping\n"
                "      found:    %s\n      expected: %s" % (PURE, body, fold(PURE_BODY))
            )
    return failures


def narration_spans(lines):
    """Index spans of validated helper definitions, signatures and call statements.

    These, and only these, are removed before comparing executable logic.
    """
    drop = set()
    for name in list(HELPERS) + [PURE]:
        for start, end in top_level_spans(lines, name):
            drop.update(range(start, end))
    call = re.compile(r"^\s*(?:" + "|".join(HELPERS) + r")\s+\d+\s")
    i = 0
    while i < len(lines):
        if i not in drop and call.match(lines[i]):
            depth = balance(lines[i])
            start = i
            i += 1
            while depth > 0 and i < len(lines):
                depth += balance(lines[i])
                i += 1
            drop.update(range(start, i))
            continue
        i += 1
    return drop


def executable_logic(path):
    """Source with narration, comments and blank lines removed."""
    lines = open(path).read().splitlines()
    drop = narration_spans(lines)
    out = []
    for index, line in enumerate(lines):
        if index in drop:
            continue
        text = line.strip()
        if text and not text.startswith("--"):
            out.append(text)
    return out


def check_statements(lines):
    """Every narration call must begin its own line, with nothing to its left."""
    failures = []
    helper_spans = set()
    for name in list(HELPERS) + [PURE]:
        for start, end in top_level_spans(lines, name):
            helper_spans.update(range(start, end))
    for index, line in enumerate(lines):
        if index in helper_spans or line.strip().startswith("--"):
            continue
        hit = CALL_ANYWHERE.search(line)
        if hit and not STANDALONE.match(line):
            failures.append(
                "narration used as a value, not a statement, at line %d: %s"
                % (index + 1, line.strip())
            )
    return failures


def check_quorum_numbers(live_path, selector_path, rule_path):
    """The spoken numbers must be the numbers the code votes with.

    Narration is prose, so nothing stops it from saying "three" while the code
    casts four votes. These checks tie the words to the constants and the
    constants to the governance rule, so the three cannot drift apart
    silently.
    """
    failures = []
    live = open(live_path).read()
    selector = open(selector_path).read()
    rule = open(rule_path).read()

    def constant(name, text=live):
        match = re.search(r"(?m)^%s\s*=\s*(.+)$" % re.escape(name), text)
        return None if match is None else match.group(1).strip()

    quorum = constant("quorumSize")
    absent = constant("absentSize")
    below = constant("belowQuorumSize")
    if quorum != "3":
        failures.append("quorumSize must be 3 to match the governance rule, found %r" % quorum)
    if absent != "5 - quorumSize":
        failures.append("absentSize must be derived as 5 - quorumSize, found %r" % absent)
    if below != "quorumSize - 1":
        failures.append("belowQuorumSize must be derived as quorumSize - 1, found %r" % below)

    # The rule itself must still be the untouched atLeastPresent 3 /
    # shareOfVotes 1 2 decision. The demo illustrates it; it must not edit it.
    # Authorized protocol change: five primary seats, three YES, coverage through close.
    for required in ["eligible permanent alternates = permanent", "now >= proposal.closesAt || yesCount >= 3", "if yesCount >= 3 then Count.Decided Yes", "checkRight opener consortiumId member now right", "Count.Decided Yes -> Count.Undecided", "opensAt = now", "checkRight opener consortiumId member closesAt right", "checkRight opener consortiumId member proposal.closesAt right", "Set.fromList primaryMembers == permanent"]:
        if required not in rule:
            failures.append("primary protocol invariant missing: " + required)
    if "Count.decide (Count.atLeastPresent 3) (Count.shareOfVotes 1 2)" not in rule:
        failures.append("the governance rule in RuleVote.daml is no longer "
                        "Count.decide (atLeastPresent 3) (shareOfVotes 1 2)")

    # Voter selection must come from the recorded permanent membership through
    # the shared selector, never from ballot order.
    if "coreQuorum" not in selector or "coreQuorum : Int -> [Party] -> [Party]" not in selector:
        failures.append("LiveVote.daml must define the shared coreQuorum selector")
    for step in ("ruleVoteWith closeWindow", "proposalVote", "milestoneVote"):
        if not re.search(r"(?s)^%s runId = do.*?coreQuorum quorumSize state\.core" % step, live, re.M):
            failures.append("%s must select its voters with coreQuorum quorumSize state.core" % step)
    if "coreQuorum belowQuorumSize state.core" not in live:
        failures.append("the no-quorum case must select coreQuorum belowQuorumSize state.core")
    # A selection taken from ballot order would reintroduce the alternates bug.
    if re.search(r"zip\s+b\d+\s+indices", live) or re.search(r"(?m)^indices\b", live):
        failures.append("positional ballot selection has returned; voters must "
                        "come from the permanent membership, not ballot order")

    # Credentials must be projected onto the participants, not presented whole.
    if "presentedRights" not in live:
        failures.append("the credential projection helper presentedRights is missing")
    if re.search(r"rights = state\.credentials", live):
        failures.append("a resolve still presents the whole electorate's credentials; "
                        "only participating voters may present one")

    # Every vote count the narration says out loud must be interpolated from
    # those constants rather than written as a word, so prose cannot drift away
    # from the votes actually cast.
    #
    # Only vote counts are checked. "two of three requirements" is about rule
    # clauses, not attendance, and comments are not narration, so both are
    # excluded: a check that cried wolf about them would be turned off.
    VOTE_WORDS = re.compile(
        r"(?i)\b(three|four|two)\s+"
        r"(?:yes|no)\b|\b(three|four|two)\s+(?:permanent\s+)?members?\s+"
        r"(?:vote|voted|are\s+absent|were\s+absent|present)\b"
        r"|\bby\s+(three|four|two|seven)\s+votes?\b"
    )
    spoken = set()
    for line in live.splitlines():
        if line.strip().startswith("--"):
            continue
        for match in VOTE_WORDS.finditer(line):
            spoken.update(group for group in match.groups() if group)
    if spoken:
        failures.append("narration spells vote counts as words (%s); counts must be "
                        "interpolated from quorumSize/absentSize so they cannot drift"
                        % ", ".join(sorted(w.lower() for w in spoken)))
    if re.search(r"by seven votes to nil", live):
        failures.append("narration still claims a seven-vote unanimous decision")

    # LEDGER EVIDENCE must be derived from ledger queries, never from
    # constants. A hard-coded count would be a claim dressed as an
    # observation, which is exactly what this block exists to avoid.
    if "queryInterface @VoteRecord" not in live:
        failures.append("vote evidence must come from queryInterface @VoteRecord")
    for interface in ("VoteTopic", "VotingRights", "Resolver", "Executable"):
        if "queryInterface @%s" % interface not in live:
            failures.append("missing queryInterface @%s in the evidence block" % interface)
    if "decodeRuleVote" not in live:
        failures.append("the YES/ABSENT breakdown must decode on-ledger vote records")
    # Counts must be deduplicated: the same contract is visible to several
    # parties, so a plain length would double-count it.
    if "distinctCount" not in live or "DA.List.dedup" not in live:
        failures.append("evidence counts must deduplicate contract IDs across parties")
    # Every evidence block must state its scope and must not claim history.
    if "evidenceScope" not in live or "active now" not in live:
        failures.append("evidence must state its scope and say the counts are active-now")
    if "ledger.sh archived" not in live:
        failures.append("evidence must name the history commands that corroborate it")
    for suspicious in re.findall(r'evidenceLine \d+ \("[A-Za-z ]*(?:active|count)[^"]*: \d', live):
        failures.append("hard-coded count in an evidence line: %r" % suspicious)

    # Every step must open with a banner and carry phase headings, so the
    # output maps onto the deck rather than being a wall of prose.
    for step in range(1, 6):
        if not re.search(r'stepHeading %d "' % step, live):
            failures.append("step %d has no stepHeading banner" % step)
        # Every step must open with INITIAL STATE and close with FINAL STATE.
        # Step 1 prints its own INITIAL STATE inline, because no demo party
        # exists yet to scope a contract query to; the others use the shared
        # helper. Both forms are accepted, absence is not.
        has_initial = (re.search(r'initialState %d ' % step, live)
                       or (step == 1 and re.search(r'phase 1 "INITIAL STATE"', live)))
        if not has_initial:
            failures.append("step %d has no INITIAL STATE" % step)
        if not re.search(r'finalState %d ' % step, live):
            failures.append("step %d has no FINAL STATE" % step)
    if not re.search(r'phase \d+ "LEDGER EVIDENCE"', live):
        failures.append("no LEDGER EVIDENCE phase heading")
    if 'phase step "INITIAL STATE"' not in live:
        failures.append("the shared INITIAL STATE heading is missing")
    if 'phase step "FINAL STATE"' not in live:
        failures.append("the shared FINAL STATE heading is missing")

    # Single round per main step: step 2 must open exactly one agenda. The
    # interleaved second round that used to live there is now optional
    # round 1, reached only through `run-demo.sh opt-step 1`.
    rule_step = re.search(r'(?s)^ruleVoteWith closeWindow runId = do.*?^findAnchor', live, re.M)
    if rule_step is None:
        failures.append("could not isolate ruleVote for the single-round check")
    else:
        body = rule_step.group(0)
        proposes = len(re.findall(r'propose \(runId <> ":', body))
        if proposes != 1:
            failures.append("step 2 opens %d agendas; a main step must run ONE round"
                            % proposes)
        for forbidden in ('":stale"', 'staleBallots', 'staleExecution', 'stale twin'):
            if forbidden in body:
                failures.append("step 2 still contains the interleaved stale round (%s)"
                                % forbidden)
        if len(re.findall(r'inviteBallotsFor 2 ', body)) != 1:
            failures.append("step 2 must issue ballots for exactly one round")

    # The optional round must exist, be reachable, and be honest about what
    # fails: the execution, on snapshot drift, not the vote.
    if 'staleRuleChange : Text -> Script Text' not in live:
        failures.append("the optional stale round (staleRuleChange) is missing")
    if 'findAnchorAtLeast' not in live:
        failures.append("the optional round must use the non-advancing anchor lookup")
    opt = re.search(r'(?s)^staleRuleChange runId = do.*?^proposalVote', live, re.M)
    if opt is not None:
        obody = opt.group(0)
        if 'baseVersion = 1' not in obody:
            failures.append("the optional round must propose against baseVersion 1")
        if 'advance ' in obody:
            failures.append("the optional round must NOT advance the run anchor")
        if 'EXPECTED REJECTION' not in obody:
            failures.append("the optional round must label its expected rejection")
    # ASCII only in headings: the demo terminal rendered non-ASCII as escapes.
    for heading in re.findall(r'(?:stepHeading|phase) \d+ "([^"]*)"', live):
        if any(ord(ch) > 126 for ch in heading):
            failures.append("non-ASCII in heading %r" % heading)
    return failures


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "logic":
        for line in executable_logic(sys.argv[2]):
            print(line)
        sys.exit(0)
    if mode == "quorum":
        problems = check_quorum_numbers(sys.argv[2], sys.argv[3], sys.argv[4])
        for problem in problems:
            print("  " + problem)
        sys.exit(1 if problems else 0)
    source = open(sys.argv[2]).read().splitlines()
    if mode == "helpers":
        problems = check_helpers(source)
    elif mode == "pure":
        problems = check_pure(source)
    elif mode == "statements":
        problems = check_statements(source)
    else:
        print("unknown mode " + mode, file=sys.stderr)
        sys.exit(2)
    for problem in problems:
        print("  " + problem)
    sys.exit(1 if problems else 0)
PYTHON

FAILURES=0

report() {
  if [ "$2" -eq 0 ]; then
    echo "PASS: $1"
  else
    echo "FAIL: $1"
    FAILURES=$((FAILURES + 1))
  fi
}

echo "narration guard for $REPO_REL"

set +e
python3 "$GUARD" helpers "$LIVE"
report "each narration helper matches its exact log-only definition" $?

python3 "$GUARD" pure "$LIVE"
report "roundName is a pure Text -> Text helper" $?

python3 "$GUARD" statements "$LIVE"
report "every narration call is a standalone statement" $?

python3 "$GUARD" quorum "$LIVE" "$SELECTOR" "$RULE"
report "spoken vote counts match the voting constants and the governance rule" $?
set -e

# Check 3: the executable logic must equal the reviewed logic for SPEC_VERSION.
python3 "$GUARD" logic "$LIVE" > "$WORK/logic-current.txt"
ACTUAL=$(cksum < "$WORK/logic-current.txt" | awk '{print $1 "-" $2}')
if command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 < "$WORK/logic-current.txt" | awk '{print $1}')
fi

if [ "${BABYCDF_PRINT_LOGIC_DIGEST:-0}" != 0 ]; then
  printf 'current executable-logic digest: %s\n' "$ACTUAL"
  printf 'spec version: %s\n' "$SPEC_VERSION"
  echo 'Printing a digest is not approving it: update SPEC_DIGEST only together'
  echo 'with a reviewed, intentional change to governance behaviour.'
fi

set +e
[ "$ACTUAL" = "$SPEC_DIGEST" ]
report "executable logic matches reviewed spec $SPEC_VERSION" $?
set -e
if [ "$ACTUAL" != "$SPEC_DIGEST" ]; then
  echo "  expected digest $SPEC_DIGEST"
  echo "  actual digest   $ACTUAL"
  echo "  The executable governance logic is not the reviewed logic for"
  echo "  $SPEC_VERSION. Either the change is unintended, or the spec needs"
  echo "  updating in the same change that moves behaviour."
fi

# Informational only: the diff against a chosen revision, for a human to read.
# This is never a pass or a failure, because a difference here is expected
# whenever behaviour was deliberately revised.
if [ -n "$BASELINE" ]; then
  python3 "$GUARD" logic "$BASELINE" > "$WORK/logic-baseline.txt"
  set +e
  DIFF=$(diff "$WORK/logic-baseline.txt" "$WORK/logic-current.txt")
  DIFFSTATUS=$?
  set -e
  echo
  if [ "$DIFFSTATUS" -eq 0 ]; then
    echo "INFO: executable logic is identical to $BASE_REV"
  else
    echo "INFO: executable logic differs from $BASE_REV (informational, not a verdict):"
    echo "$DIFF" | sed 's/^/    /'
  fi
elif [ -n "$BASE_REV" ]; then
  echo
  echo "INFO: no baseline for $BASE_REV; the comparison against it was not run."
  echo "      This does not affect the verdict below, which is pinned to $SPEC_VERSION."
fi

echo
if [ "$FAILURES" -ne 0 ]; then
  echo "narration guard: $FAILURES check(s) failed"
  exit 1
fi
echo "narration guard: all checks passed against spec $SPEC_VERSION"
echo "Scope: proves the narration helpers are log-only, roundName is pure, the"
echo "spoken counts match the voting constants, and the executable logic is the"
echo "reviewed logic for $SPEC_VERSION. It does NOT prove behaviour never"
echo "changed: that is what the spec version is for."
exit 0
