#!/usr/bin/env python3
"""Validate a step-4 handoff file before step 5 is allowed to start.

This is a shape and presence check only, and it is deliberately read-only: it
opens one file, writes nothing and touches no ledger. Its job is to turn a
missing, truncated, malformed or approval-free step-4 result into a refusal
BEFORE the runner writes step 5's one-shot marker, so an operator is never
left with a permanent marker for a step that could not have submitted.

It also binds the handoff to THIS network's run, by comparing its runId with
the run id `up` recorded in run-id.json. That check belongs here and not in
Daml: findAnchorAtLeast selects the anchor by demoRunId alone, so a handoff
naming a different but perfectly valid run awaiting step 5 would otherwise
bind and allocate against that run's approval, and a script input cannot know
which run the host bootstrapped.

It does not decide whether the approval is still valid on the ledger. Only
the Daml side can bind a contract id to live state, and it re-checks every
field inside the allocating entry point. A pass here means "worth starting",
never "safe to allocate".
"""
import json
import pathlib
import sys

REQUIRED = ('runId', 'opener', 'electorate', 'outcome', 'approval')
APPROVAL_REQUIRED = ('approvalCid', 'milestoneUrl', 'contentVersion', 'roundId')


def local_run_id(path):
    """The run id THIS network bootstrapped, read from run-id.json.

    `up` writes it as a bare JSON string (json.dumps(user_name)). A missing,
    unreadable or non-string value is a refusal, never a reason to skip the
    comparison: without it the handoff's own run id cannot be checked against
    anything.
    """
    source = pathlib.Path(path)
    if not source.is_file():
        fail('no run-id.json at %s, so the handoff cannot be bound to this '
             'network; run up first' % source)
    try:
        value = json.loads(source.read_text())
    except (OSError, ValueError) as error:
        fail('%s is not readable JSON (%s)' % (source, error))
    if not nonblank(value):
        fail('%s must contain a nonblank run id string' % source)
    return value


def nonblank(value):
    return isinstance(value, str) and value != '' and not any(c.isspace() for c in value)


def fail(message):
    raise SystemExit('step-4 handoff unusable: ' + message)


def validate(path, run_id_path=None):
    source = pathlib.Path(path)
    if not source.is_file():
        fail('no step-4 result at ' + str(source) + '; run step 4 first')
    try:
        handoff = json.loads(source.read_text())
    except (OSError, ValueError) as error:
        fail('%s is not readable JSON (%s)' % (source, error))
    if not isinstance(handoff, dict):
        fail('expected a step-4 handoff object, found ' + type(handoff).__name__)
    missing = [field for field in REQUIRED if field not in handoff]
    if missing:
        fail('missing field(s) %s; this looks like an older step-4 result, '
             'so rerun step 4 to produce a step-5 handoff' % ', '.join(missing))
    if not nonblank(handoff['runId']):
        fail('runId must be a nonblank run identifier')
    # Bind the handoff to THIS network's run.
    #
    # findAnchorAtLeast selects the anchor by demoRunId alone, so a handoff
    # naming any other valid run that is awaiting step 5 would bind and
    # allocate against that run's approval. The Daml side cannot catch it: a
    # script input carries no knowledge of which run the host bootstrapped.
    # The runner owns that scope, so the comparison happens here, before the
    # prevalidation script and before any marker or lock exists.
    if run_id_path is not None:
        local = local_run_id(run_id_path)
        if handoff['runId'] != local:
            fail('this handoff belongs to run %r but this network bootstrapped '
                 'run %r. Step 5 never allocates against another run, even a '
                 'valid one awaiting step 5. Use the step-4 result produced on '
                 'this network.' % (handoff['runId'], local))
    if not nonblank(handoff['opener']):
        fail('opener must be a nonblank party id')
    electorate = handoff['electorate']
    if not isinstance(electorate, list) or not all(nonblank(p) for p in electorate):
        fail('electorate must be a list of nonblank party ids')
    if len(set(electorate)) != len(electorate):
        fail('electorate contains duplicate parties')
    approval = handoff['approval']
    # An absent approval is a legitimate step-4 outcome (a blocked or
    # incomplete review). It is still a refusal for step 5, and it is
    # reported as the governance fact it is rather than as corruption.
    if approval is None:
        fail('step 4 recorded no executable milestone approval (outcome: %r). '
             'A blocked or incomplete review is not an approval, and step 5 '
             'never cites an illustrative one.' % handoff['outcome'])
    if not isinstance(approval, dict):
        fail('approval must be an object or null, found ' + type(approval).__name__)
    missing = [field for field in APPROVAL_REQUIRED if field not in approval]
    if missing:
        fail('approval is missing field(s) ' + ', '.join(missing))
    for field in APPROVAL_REQUIRED:
        if not nonblank(approval[field]):
            fail('approval.%s must be nonblank' % field)
    print('step-4 handoff names approval %s for round %s'
          % (approval['approvalCid'], approval['roundId']))


if __name__ == '__main__':
    # The run-id path is required in the runner call. It stays optional in the
    # signature only so the shape checks remain independently testable.
    if len(sys.argv) not in (2, 3):
        raise SystemExit('usage: step5-handoff.py <step-4.json> [run-id.json]')
    validate(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else None)
