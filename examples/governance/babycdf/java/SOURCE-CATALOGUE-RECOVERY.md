# Source catalogue adapter recovery

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
queries request only SourceCheckpoint. Item queries request SourceItem separately;
this implementation scans bounded ACS, not unlimited/key-paginated inventory.
Exceeding a cap fails closed, never returns an empty inventory. A future scalable
key lookup implementation is required beyond these bounds.

Serialized command intents are limited to 1,000,000 bytes including the offset.
Fifty maximum-sized observations can exceed that limit: the adapter rejects them
before persistence/submission. The caller must split the page while preserving its
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
