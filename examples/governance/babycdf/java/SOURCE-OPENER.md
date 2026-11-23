# S3 durable source opener

R2: milestone collection returns one immutable batch+derived capability object;
that exact batch is retained before admission, never a second observation. Queued
milestone predecessor is selected JIT only for the next opening and durably pinned
before uncertainty/RPC; submitted/uncertain bindings are never rebound. Evidence
CID/version/fingerprint/pin never changes. Amendment journal schema v2 retains all
relation fields including unique active original round, normalized full both pins,
manifest commitment and evidenceID. Restart verifier restoration must equal persisted
commitment; recovery compares every relation field. If more than one original receipt
is active, fail closed rather than pick one. Legacy v1 journals require reconciliation.

Durability requires file force and directory force support: a failure propagates
and no subsequent send is authorized. Atomic rename alone is NOT claimed crash
durability. Host filesystems without directory-force support cannot run this queue.
Writer locks are tested with an independent child JVM; failures close the channel.
Canonical ordering uses proposal-before-milestone precedence as the evidence manifest.

Explicit Intake CLI `--automatic-source PORT OPENER IMPL DEMO GOVERNOR USER EXISTING_JOURNAL AMENDMENT_MANIFEST_OR_DASH [ATTEMPTS INTERVAL_MILLIS]`
discovers bounded source inventory from registered credential ACS, re-loads the
strict amendment manifest through verifier+fresh collector pins on every restart,
collects coherent source evidence, binds the active registered source credential,
enqueues collector-private Ready capability and ticks the durable opener. Existing
demo CLI is unchanged. No S4 ballots/S5 reviewer/S6 journey is added.

Only runtime collector-produced READY proposal capabilities can enqueue executable
rows; public records/Capture payloads/deserialized status cannot manufacture Ready.
Journal resume requires collector revalidation before queued submission. Exact
credential CID/version/fingerprint/body evidence digest/full immutable pin/round ID
are persisted atomically (fsync file+directory, private permissions). States queued,
submission_uncertain,proven_open,completed,blocked. One active proposal blocks new
opening; automatic window120seconds. Ordering UTC/type/repo/number/numericID.

Before any send, uncertainty is durable. Lost ACK/crash never grants retry; receipt
plus linked action (bounded historical lookup when archived) proves opening. Local
journal loss can reconcile re-enqueued exact identity from on-ledger receipt. Missing
receipt after proven/completed is reset/dangling and blocks. Source revision/archive
blocks old CID, never silently upgrades. No API archives receipts or heads.

Ledger receipts/registration remain issuer assertions, not independent provenance.
No secret/body is journaled; pins contain only SHA/path/status metadata. Journal
schemas/digests/duplicates/bounds are failclosed. Operator must preserve journals.

Automatic collector capability supports proposals, checked VerifiedAmendment
pairs and milestones only when complete trigger-body/explicit commit evidence is
available. Historically unknown milestones remain blocked. Cross-PR relations are
issuer assertions with manifest commitments and both pins, not independent truth.

Original implementation note (superseded by continuation): capability was proposal-only. Milestone blocked/unresolved history
and unproven amendments do not become executable automatically. Verified amendments and milestone predecessor execution use explicit collector
capability paths and latest-head validation, never public ready JSON. The scheduler
has combined inventory entry points and the CLI automatically discovers credential
inventory with optional bounded attempts/interval arguments. EVERY collector batch,
including blocked/unresolved/incomplete, is retained atomically as immutable hashed
informational evidence, never executable admission. The queue can retain blocked categories
without upgrading them. No scheduler receipt/head archival is authorized.

Reads bound10000responses/20MB/15s; missing history blocks reconciliation. Explicit
production CLI is wired but no live runtime/network/stack validation was performed.
