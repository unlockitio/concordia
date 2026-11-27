# Integrated demo without source ballots

R3 proposal absence eligibility comes from the complete verified immutable proposal
bundle and copied snapshot, consistently for deterministic and supplemental checks.
`head_consistent` means exact pinned-tree/blob consistency here, not a reread of a
moving provider head. Delivery evidence is the defined `.md`/`.daml` subset under
proposals/rfps/daml/docs at the exact commit; it never establishes repository-wide
absence or proposal-only presence eligibility. Committer-time comparison checks
consistency only and does not prove historical branch reachability.

Integrated emission identity includes round, evaluated immutable RuleRef, evidence
identity and substantive findings/exact assessment. Fresh diagnostics versus cached
`unavailable_cached` are excluded; timing/cache logging is unchanged. The sink uses
the evaluated cycle identity without rereading active rules. Connected tests cover
fresh, memory-cache, disk-cache and restart suppression, changed rule/verdict output,
and failed emission without state advancement.

R2: integrated Reviewer uses the approved SupplementalAssessment lifecycle, exact
verified bundle and selected rule, provider budget/cache/quote/verdict diagnostics.
Change suppression and DemoLog remain active. Integrated `--state-file` uses the
existing merged-state serialization and advances only after successful output;
failed emission leaves state unchanged and restart re-emits. Connected acceptance
loads actual runtime v1/v2 JSON RuleRefs with exact SHA-256 verification, not
synthetic clauses. Delivery commits require a committer timestamp no later than
the stored trigger; missing timestamps fail closed. Valid DeliveryPin is decoded separately
from ProposalPin and resolves only immutable commit-tree/blob evidence; blocked
unknown delivery does not call a provider. Absence eligibility requires complete,
verified text and excludes NUL/binary input.

Public steps 3 and 4 are explicit manual commands that vote on existing source
rounds; they do not open replacement rounds. Step 5 is an explicit manual coupon
allocation command requiring the actual executable approval produced by step 4
for this network's run. Missing or invalid approval evidence refuses allocation.
`all` runs only the existing explicit Architecture step 2 and never invokes steps
3, 4 or 5, so it leaves source topics unvoted. Optional source-ballot fixtures
2..5 remain retired in integrated mode.
Strict snapshot decode requires source CID, lineage/version, original proposal and
previous-round fields even when the fingerprint is valid. Malformed runtime ledger
input is categorized without echoing contents and does not kill Intake polling.

`run-demo.sh up` starts one Intake process. Its integrated mode runs the existing
catalogue sweep worker and the S3 automatic opener queue, preserving their journals,
backoff, proof-before-retry, one active source round and 120-second window. It never
casts, mirrors, scripts or resolves source votes. Existing explicit step2 Architecture
votes remain unchanged. Reviewer remains query-only with opener read visibility.

The launcher selects `--integrated-demo` for Intake and `--integrated-agenda` for
Reviewer. Only explicit `AGENT_EVIDENCE_MODE=offline` selects legacy fixed evidence.
Integrated Reviewer has explicit idle when no in-scope pending source agenda exists.
Pending means active AgendaProposal, even after its voting deadline: an unprocessed
source topic remains reviewable under the currently selected ACTIVE rule. Ambiguous
pending scope or active rule fails closed. No unrelated discovery fallback occurs.

The immutable source snapshot fingerprint, version, lineage digest, opener, round,
subject and pin are validated. Proposal head tree membership and Git blob bytes are
checked at the exact stored IDs, never replaced with current head contents. Verified
bundles are cached by immutable evidence identity; each rule version reevaluates the
same bundle. Unknown delivery/milestone evidence remains INCONCLUSIVE.

Intake admission and Reviewer share ConcordiaDemoScope. Root184 requires the known
head/blob. Other independent eligible PRs require immutable changed Concordia paths,
not title guesses or invented amendment lineage. Catalogue inventory can be broader.
Empty amendment fixture stays empty. Issue540 has no invented delivery proof.

Offline checks include launcher stub arguments, Java evidence/rule cache and idle
behavior, source/queue regression tests, and Daml sourcePendingAcrossExistingStep2:
a source agenda remains byte-identical across the existing step2 v1-to-v2 vote with
zero ballots issued for that source round. No live-stack acceptance is claimed.
