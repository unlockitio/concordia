# Manual voting on existing source rounds

Steps 3 and 4 are explicit operator actions by the synthetic demo opener and actual selected ballot holders. They do not submit an Intake request, create a source credential, open a proposal, change authority, extend a deadline, or reopen an expired round. Step 5 remains retired.

Step 3 requires one existing, verified, pinned PR184 round. Step 4 requires one existing pinned milestone540 round with a DeliveryPin and an actual approved, pinned PR184 prerequisite. Missing or ambiguous evidence is an error, not a reason to create a replacement. An immutable source snapshot may remain usable after its original source credential is archived.

Before voting, the script verifies the proposal, action, copied source and round receipt agree, including subject URL and fingerprint. The electorate must match the current governor and retain all seven members. Selected voters are actual core parties with existing credential references, not inferred login names.

For a round with no ballots, invitation is allowed only before its deadline. Complete existing ballot sets are reused. Partial or duplicate sets fail closed. Only absent selected votes are cast; existing YES votes are verified and never recast. Conflicting votes refuse the operation.

Before the deadline, a successful cast returns `PENDING` immediately. It does not wait or resolve, and a pending output file is not approval. Run the same explicit step command after the deadline to resume. Keep attempt markers; do not reset or delete them to recover an uncertain operation. The ledger phase checkpoints, ballots and execution evidence govern safe resume. Resolution and recording its execution CID are atomic; execution and the completion marker are atomic.

## Credential expiry is not extended

A 21-day round outlives the demo bootstrap credentials' 12-hour validity. Casting now can therefore succeed while resolution after the deadline fails for expired rights. Resolution still requires independently valid existing holder credentials at the actual resolution time. This command does not renew, grant or extend credentials. A credential-expiry failure must be addressed through the separately authorized authority process, not by bypassing the deadline or rights checks.

Offline tests use preopened short-window rounds and advance test time within credential validity. Such fixtures are not live evidence.

## Semantic guard procedure

This is an intentional manual cast/resume behavior change, not narration equivalence. Preserve the old guard failure before changing its version or digest. Review the normalized delta: only existing-round selection, exact source/action/receipt identity checks, ballot reuse, absent-only casts, nonblocking pending return, and atomic phase checkpoints are authorized. Step 2, optional rounds, seven-member governance, quorum thresholds and credential validity must remain unchanged. Include checkpoint templates in normalized logic; never exempt them as narration. Update the guard to the named v19 existing-source manual-resume specification only after behavioral tests and delta review. Record the old/new digest and exact validation commands in the implementation report.
