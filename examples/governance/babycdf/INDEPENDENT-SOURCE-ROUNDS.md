# Independent automatic source rounds

Automatic intake keeps the existing bounded static eligibility list: proposal PRs 184, 473, 474, 475 and 1028, and milestone issues 538 through 545 in `canton-foundation/canton-dev-fund`. The broader catalogue discovery scope is unchanged.

A pending proposal does not prevent a different eligible subject from opening in a subsequent intake cycle. The opener remains a single locked queue writer and submits at most one new round per tick. Uncertain submissions are reconciled using exact receipt and linked-action proof, never replayed. A pending or uncertain round blocks subsequent rounds for the same repository, source kind and source number. Intake opens at most one vote per milestone: additional comments do not request another vote, even after resolution. Comment identities remain catalogue evidence. Different milestone issues remain independent even when they share an original proposal.

Before milestone submission, Intake checks durable milestone receipts by repository and issue URL, including receipts retained after resolution. Missing or conflicting ledger evidence fails closed; existing duplicate receipts require reconciliation and are never automatically archived or resolved. This is an application guard serialized by the opener journal lock, not an atomic ledger uniqueness constraint across independent writers or journal directories. Explicit issuer receipt archival/reset can remove the durable evidence and is outside this guarantee.

## Voting window

`INTAKE_VOTING_WINDOW` defaults to `21d`. Accepted values are positive whole hours (`h`) or days (`d`), from `1h` through `365d` inclusive. Examples: `2h`, `7d`, `21d`. Zero, negative values, fractions, unknown units and overflowing values are rejected before the automatic intake ledger adapters are constructed. Compose passes this variable to the intake container.

A new round receives one absolute microsecond deadline immediately before its first submission attempt. The deadline is durably recorded before the uncertain-submission latch and RPC. Changing the environment or restarting does not change that deadline. Binding digests retain their existing format; deadline metadata is stored separately in the v3 opener journal.

The reader accepts existing v2 journals. An unattempted queued legacy row may receive its first deadline. A legacy pending or uncertain row does not receive a replacement voting window. An active legacy row with an unknown deadline fails closed: round ID and active proposal metadata alone cannot establish the deadline's operation provenance. Completed receipt/action proof does not require an active deadline. An expired round is not extended, archived, resolved or reopened automatically.

## Evidence and operation

Submission acknowledgement alone is not success. Intake requires the exact round receipt and linked action proof. Active proposal metadata supplies the proposal CID and deadline, and a mismatch with the persisted deadline fails closed. The proven-opening message includes subject, round, proposal CID and deadline only after proof; it is not a claim that an expired deadline remains open for voting. Unchanged proven rows do not generate repeated opening messages on polling or restart.

Manual protocol steps 3 and 4 remain disabled. This slice does not automatically cast ballots, change reviewer seats, resolve rounds or run the next protocol stage. Daml authorization and milestone-head predecessor checks are unchanged.
