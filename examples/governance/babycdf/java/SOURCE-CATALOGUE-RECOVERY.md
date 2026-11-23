# Source catalogue adapter recovery

## Credential schema v2 (S1a)

Source observations now use the impl `SourceObservationCredential` template,
keyed by `(opener, repo, number)`, implementing Credential and RegisteredCredential.
Only the raw UTF-8 body SHA-256 is retained in durable catalogue observations;
raw bodies never enter durable catalogue command payloads, contracts, journals or
logs. Transient HTTP/JSON parsing and separate Reviewer evidence fixtures are
distinct from durable catalogue state. SourceItem in immutable legacy/rejection
fixtures is intentional evidence of the rejected old schema. Fingerprints hash the fixed canonical v2 JSON array,
including version and previous credential fingerprint, excluding receipt time.
The factory validates the complete credential/registration mapping and derives
revision lineage from the fetched old keyed credential atomically. The opener is
the issuer/trust anchor and may also create assertions directly: a direct v2 with
a well-formed previous digest does not prove a real predecessor. RegisteredCredential
is NOT evidence of factory issuance. Template invariants apply the pinned CIP
issuer-in-anchorers and structural helpers, unique anchorers/holders, required
types and one subject; signatories derive from anchorers plus holders. Default
Archive remains available. S1b must compare fingerprint/version to the copied pin,
not infer ledger-verified lineage from the registered view.

Checkpoints explicitly require schema `babycdf-source-observation/v2`. New markers
use `babycdf-source-v2-<sha256>.intent|receipt`. Legacy checkpoints and legacy
`babycdf-source-<sha256>` intents fail closed: retain them for independently
reviewed reconciliation; do not delete, reinterpret, replay, or bypass them.
Receipt binding remains over the original exact intent bytes. No batch replay
is introduced. Credential-bound agenda semantics are deferred to S1b.

This explicit loopback adapter is not wired into polling. Its journal must be an
existing private, non-symlink directory dedicated to one opener/package/repository.
Do not edit, delete or transplant journal records to make a submission retry.

An exclusive intent is durable before submission. A crash between intent creation
and RPC cannot distinguish non-submission from a lost response: reconciliation is
mandatory, and absent transaction evidence blocks progress. Historical ACS pruning
also blocks proof; current ACS is not a substitute. Preserve the journal and obtain
retained participant transaction/history evidence before an independently reviewed
operator recovery. There is no automatic or exposed batch retry command in this slice. The sole
exception is initial keyed checkpoint CREATE: an existing intent may resume only
its identical serialized request/command ID. The ledger key prevents a second
checkpoint; authoritative key/CID readback resolves an existing or racing create.
Batch CommitBatch intents never receive this exception.

A definite gRPC rejection writes a REJECTED terminal record and leaves the ledger
cursor unchanged. It is not confirmation and deliberately blocks a subsequent scan.
Correct authorization/payload problems and arrange explicit reviewed recovery;
restarting is not a retry authorization. Uncertain errors never write rejection.

CONFIRMED receipts are compact trusted local completion status, bound to the exact
intent hash, transaction offset and update ID. They prevent checkpoint() from
rescanning completed history. They are never returned as Proof: proof() fetches
bounded ledger transactions and historical ACS again. The journal is not a defense
against an attacker with write access to the adapter's local state.

Reads have 15-second deadlines, 10,000-response and 20MB aggregate caps. Checkpoint
queries request only SourceCheckpoint. Item queries request impl SourceObservationCredential separately;
this implementation scans bounded ACS, not unlimited/key-paginated inventory.
Exceeding a cap fails closed, never returns an empty inventory. A future scalable
key lookup implementation is required beyond these bounds.

Serialized command intents are limited to 1,000,000 bytes including the offset.
Body text no longer contributes to that limit. Oversized encoded commands are
still rejected before persistence/submission. The caller must split the page while preserving its
uncommitted remainder and cursor; automatic splitting is not implemented here.
No ledger-clock claim is made for source observation receipt timestamps.

Journal scanning stops at 10,000 intents. Rotation is not automated. Before that
limit, an operator may plan a new journal only after validating every old intent's
terminal outcome against ledger evidence and rereading the authoritative checkpoint;
retain the old journal for audit. No pending/uncertain or definitely rejected batch
may be bypassed through rotation. NOT_FOUND is not blanket permission to use a fresh
journal: checkpoint absence/current-key state and the particular rejected command
must be independently reconciled first. Do not delete markers as a retry mechanism.
A persistence failure proven to leave no intent marker is PreTransmission and the
engine can retry a fresh cycle; any partial/existing marker remains uncertain.
