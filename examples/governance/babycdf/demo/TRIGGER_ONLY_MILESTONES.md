# Current-comment milestone opening policy v1

For the existing standalone milestone subjects 538 through 545, a currently observed standalone `/vote` or `/git-vote` comment can request a topic. Quoted, fenced, indented-code, list-item, and inline commands do not qualify. Edited comments may qualify on their current observed text; this does not establish their historical text or historical authorization.

Opening readiness binds the current observed issue-body SHA-256 to the current registered source credential. Historical body digests, delivery commit claims, author uncertainty, result uncertainty, and incomplete enumeration remain separate evidence. Missing delivery evidence produces an explicit non-executable `BlockedPin`, not a synthetic delivery pin. This policy neither expands the catalogue nor casts votes automatically.

Distinct milestone comments create distinct rounds. After exact receipt proof for one round, the next round prepares against the latest source head even when the earlier topic remains pending. An uncertain submission still requires proof reconciliation and must not be resent.

Policy-v1 revalidation applies only to freshly collected, never-submitted blocked milestone rows in the supported categories with no persisted deadline. Before transition, the complete previous journal is retained in a content-addressed file. Attempted bindings, receipts, intents, deadlines, manifests, and uncertain rows are not rewritten by this migration.

Manual milestone voting may record ballots for a valid blocked snapshot, subject to the existing primary-right and actual proposal-grant dependencies. A decided YES with no executable is reported as votes recorded and review incomplete. It creates no milestone approval, delivery proof, or payment. The on-ledger execution barrier remains unchanged.

Reviewer Java already accepts blocked snapshots and reports them INCONCLUSIVE. The running Reviewer's exact build has not been established by this offline task. The opener does not request optional commit enrichment. SHA-like text in unrelated comments cannot consume commit-fetch budget or block admission; delivery enrichment remains a Reviewer concern. Source observation failures and cancellation are not swallowed, and rate-limit backoff remains active.

Deployment is not part of this change; any later deployment is Intake-only and must preserve Canton, Reviewer, persistent volumes, and journals. An Intake-only restart updates Java opening behavior, not the Live.daml manual consumer: activating that consumer requires a separately published compatible demo DAR. The implementation DAR is unchanged.
