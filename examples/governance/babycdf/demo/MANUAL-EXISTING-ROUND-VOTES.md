# Manual voting on existing source rounds

Steps 3 and 4 are explicit operator actions by the synthetic demo opener and actual selected ballot holders. They do not submit an Intake request, create a source credential, open a proposal, change authority, extend a deadline, or reopen an expired round. Step 5 remains retired.

Step 3 requires one existing, verified, pinned PR184 round. Step 4 requires one existing pinned milestone540 round with a DeliveryPin and an actual approved, pinned PR184 prerequisite. Missing or ambiguous evidence is an error, not a reason to create a replacement. An immutable source snapshot may remain usable after its original source credential is archived.

Before voting, the script verifies the proposal, action, copied source and round receipt agree, including subject URL and fingerprint. The electorate must match the current governor's five primary seats. Two credentialed substitutes retain observer visibility but have no independent votes; their assignment protocol is PENDING. Selected voters are actual primary parties with existing credential references, not inferred login names.

For a round with no ballots, invitation is allowed only before its deadline. Complete existing ballot sets are reused. Partial or duplicate sets fail closed. Only absent selected votes are cast; existing YES votes are verified and never recast. Conflicting votes refuse the operation.

The command queries the existing public `VoteTopic` and binds its CID and view to exactly one verified `AgendaProposal`, receipt, source snapshot and executable pin. Before any invitation or cast, it checks the deadline, the complete existing ballot set, absent votes and the selected primary holders' rights through the deadline. It invites ballots only when none exist, casts three primary YES votes, resolves the existing proposal immediately and executes the CID returned by `ResolveAgenda`. Milestone #540 also requires an actual pinned approval of PR184.

Each host command is one-shot. An existing attempt marker or result file refuses another submission. If submission is uncertain, stop and reconcile the ledger manually using read-only evidence; do not automatically retry, recast, reset the network or delete markers. There are no manual phase-checkpoint contracts or automatic resume. A successfully resolved topic is normally consumed. A missing or ambiguous existing target fails closed; the command never opens or reopens a round, fabricates source evidence, or extends a deadline.

## Credential expiry is not extended

Fresh bootstrap credentials default to 30 days, configurable with `BABYCDF_CREDENTIAL_VALIDITY` as whole hours or days from `1h` through `365d` (for example `2d`). All seven are factory registered. Before opening, all five primary rights must be valid both now and strictly beyond `closesAt`; expiry equal to the deadline is rejected. Fresh 30-day rights cover a fresh 21-day round, but aged rights or a longer window may not. Resolution independently checks participating holder rights at resolution and closing time. Existing contracts are not modified. This command does not renew, grant or extend credentials, reset deadlines, or create replacement authority. A coverage failure requires appropriately valid credentials through the separately authorized authority process before opening.

Offline tests use preopened short-window rounds and advance test time within credential validity. Such fixtures are not live evidence.

## Semantic guard procedure

This is an intentional manual cast/resume behavior change, not narration equivalence. Preserve the old guard failure before changing its version or digest. Review the normalized delta: only existing-round selection, exact source/action/receipt identity checks, ballot reuse, absent-only casts, nonblocking pending return, and atomic phase checkpoints are authorized. Step 2, optional rounds, seven-member governance, quorum thresholds and credential validity must remain unchanged. Include checkpoint templates in normalized logic; never exempt them as narration. Update the guard to the named v19 existing-source manual-resume specification only after behavioral tests and delta review. Record the old/new digest and exact validation commands in the implementation report.
