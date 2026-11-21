# Java Intake: steps 3/4, packaging and inventory narration; live acceptance pending

## Discovery inventory (advisory client report, not ledger evidence)

Compose and Java default to `INTAKE_DISCOVERY_MODE=live`: token-authenticated read-only
GitHub pages (explicit `INTAKE_GITHUB_TOKEN_FILE` required; see [token setup](GITHUB-TOKEN.md)) are committed through `SourceCatalogueLedger` by a separate bounded
source worker while agenda polling continues. Source attempts default to 30000 ms;
`INTAKE_SOURCE_INTERVAL_MILLIS` accepts 30000..3600000. Each tick fetches at most
one page/batch, with five-second HTTP deadlines and durable ledger checkpoints.
Only the explicit file-backed GitHub credential is used; ambient `GITHUB_TOKEN`,
LLM keys and anonymous fallback are never used by Intake. No synthetic fallback is permitted.
HTTP 401 suspends source requests until the credential is replaced and Intake is deliberately restarted;
existing 403/429 retry evidence and checkpoint rules remain unchanged.

Only explicit `INTAKE_DISCOVERY_MODE=fixture` runs the synthetic startup inventory
with a fixed 2026-01-01 timestamp; it starts no source worker and does not create
SourceItems. This is offline test data, NOT a fresh GitHub observation. Neither
mode supplies governance authority. Observations are not atomic snapshots, and
only issues matching the milestone-title convention are milestone candidates;
PR mirrors and ordinary issues are excluded. See `../java/SOURCE-RUNTIME.md` for
exact batch bounds and unresolved live-acceptance limits.

Only explicit fixture mode prints the synthetic bounded report and atomically
publishes `discovery-inventory.txt` inside the agenda journal. Live mode does not
publish this artifact: it records candidate observations as ledger SourceItems
and checkpoint batches through the source worker. Steps 3/4 query active SourceItems
as opener before opening their own curated agenda, reporting active contracts,
distinct (opener, repository, number) keys and SourceProposal/SourceMilestone counts.
Only opener visibility is claimed. Zero is valid at startup; sync completeness is
unknown, and no sweep completion is awaited. Recent Intake logs remain separately
labeled client reports, not ledger-derived counts. These persisted observations
carry observed/updated provenance, not a complete or verified GitHub snapshot. Neither inventory nor catalogue observations confer governance authority.
Counts must be computed from their stated evidence; no hundreds are invented.
Exactly two curated demonstration subjects remain selected independently of the
inventory, and each step creates only its own request. The pinned grant-byte check
is separate; milestone selection remains illustrative/unverified.


Java initiates the real ledger submission as opener by exercising consuming
`IntakeRequest.OpenAgenda`. Daml fetches the supplied governor, checks its opener,
selects exactly the pinned Grant or illustrative Milestone, computes `closesAt`
from transaction `getTime + windowSeconds`, and exercises ProposeAgenda. Consuming
the request and creating the proposal/action are atomic. No testing TimeService,
host clock, separate Archive command, expanded validAgenda, votes or LLM calls.

Request fields remain opener, runId, deterministic `runId:sourceKey` roundId,
sourceKey (`proposal-184` or `milestone-540`), windowSeconds (1..86400), baseVersion.
Create one request per current step, never both upfront. Accepted round IDs are
`runId:sourceKey`, or `runId:grant` only for proposal-184 and
`runId:illustrative-milestone` only for milestone-540; cross-source suffixes fail. Java verifies grant bytes
through IntakePlan before submission. Milestone evidence stays CURATED_UNVERIFIED.
The bridge itself does not claim byte verification: only the configured Java
Intake path performs it; the opener already has direct ProposeAgenda authority.

## Explicit local invocation

Provision separate `readback-intake` actAs-opener rights, supply exact deployed
package IDs and governor CID, and an existing writable canonical journal directory
(no symlink path components, including macOS `/var` alias; use `/private/...`).
Bootstrap already provisions the dedicated `babycdf-agent-intake` user. Runtime
`up` now publishes separate demo-package metadata and starts the Intake service
after bootstrap/publication alongside Advisory and Reviewer. Reader readback keeps
exactly five fields; `intake-demo-package` supplies only the demo package ID.
The container uses the existing unprivileged UID 65532, read-only root, dropped
capabilities, no-new-privileges, tmpfs and 512 MiB limit. Its named journal volume
is initialized from an image-owned 0700 directory. No automatic restart or retry
budget reset is configured. `down --volumes` removes this dedicated project volume;
normal process restarts preserve it. `up` refuses an existing named journal before
starting a fresh ledger and tells the operator to use dedicated demo down first.
The compose helper pins project `babycdf-live`, ignoring ambient project overrides.
Steps 3/4 check that Intake is running before writing attempt markers or creating
requests. Running is not health/readiness proof; participant acceptance remains pending. No host journal deletion is performed.

```
INTAKE_DISCOVERY_MODE=fixture mvn -o exec:java -Dbabycdf.mainClass=org.concordia.babycdf.Intake \
  -Dexec.args="--offline-fixture GRANT_FILE PORT OPENER IMPL_PACKAGE DEMO_PACKAGE GOVERNOR_CID readback-intake JOURNAL_DIRECTORY"
```

**`--offline-fixture` pins only the curated grant bytes; it does not select
source-discovery mode.** The explicit environment assignment above disables live
source discovery, but agenda processing still reads/writes the loopback sandbox.
Without that assignment, live discovery is the default and can create ledger
SourceItems, checkpoint contracts and the private `/journal/sources` journal.
This is not a no-ledger dry run.

This executes one poll against the loopback sandbox. For bounded service polling,
append `--poll ATTEMPTS INTERVAL_MILLIS` (1..86400 attempts, 1..60000 ms).
Repeated unchanged statuses are suppressed. Any submission/read error exits the
polling run; it never automatically enables rejected-command retry. Explicit
`--retry-rejected` is a separate one-shot mode and cannot be combined with polling. Fixture mode is explicit;
there is no network content fallback. Package changes require rebuilt/uploaded
DARs before use. The read-only reviewer identity is never a write gateway.

## Journal states and bounded recovery

Before RPC, exact protobuf command bytes are exclusively persisted to
`COMMAND.intent`, POSIX 0600, file force then parent-directory fsync. Any persistence
failure is PreTransmission: no submission RPC occurred. Partial markers remain
blocked. Preserve the same journal across restarts and run only one participant
configuration against it. No file is deleted or overwritten by recovery.

A successful response still requires matching active proposal/action proof.
A crash after receiving rejection but before durably recording `.rejected` is
still uncertain: obtain participant-admin completion evidence; do not reconstruct
that file by hand or bypass the marker. Timeout, DEADLINE_EXCEEDED, UNAVAILABLE, INTERNAL, ABORTED, UNKNOWN, duplicate or
other unclassified RPC errors remain uncertain. An existing intent without a
recorded definite rejection blocks replay, including after restart/dedup expiry.
Consumed proposals require historical reconciliation, not inference from absence.

PERMISSION_DENIED, UNAUTHENTICATED, INVALID_ARGUMENT and NOT_FOUND responses are
recorded durably as `COMMAND.rejected`. They are rejections, not success. After
correcting the cause and stopping other runners, the operator may explicitly add
`--retry-rejected` to the same invocation. This is authorization for ONE bounded
retry, not automatic recovery. The adapter requires identical protobuf bytes,
recorded definite rejection and absent matching proof; it creates exclusive
`COMMAND-retry.intent` before RPC. Original command ID and payload stay unchanged.
A second explicit retry, changed payload, missing rejection or uncertain outcome
is refused. A retry rejected again is recorded separately but cannot be replayed.

For uncertain or exhausted recovery: stop runners, preserve all journal files,
record participant/user/actAs/command/request identity, obtain participant completion
and update history (including consumed proposals and request archive). Matching
success means completed, never replay. Missing/pruned history remains UNKNOWN.
Escalate with evidence for a separately reviewed recovery; never remove markers,
change command IDs or create another run just to bypass uncertainty.

## Remaining integration

Clock compatibility is resolved by the ledger-native bridge; no optional testing
clock service is needed. Main steps 3/4 now create one request and wait at most
120 one-second polls for a unique matching opener-signed proposal/action, using its
ledger-derived closesAt before existing voting. Legacy round IDs `runId:grant`
and `runId:illustrative-milestone` remain accepted alongside source-key IDs; no
arbitrary suffix is accepted. The unpinned milestone negative test still directly
exercises ProposeAgenda, but successful proposals have no script fallback.
The narration guard v7 explicitly records this intentional executable change.

Container packaging, automatic service startup and persistent journal mounting
are implemented. Runtime up starts the Java bounded poll before steps 3/4;
without a healthy poll they fail closed after the bounded wait. The default poll
budget is 86400 one-second iterations (roughly one day plus cycle durations);
expiration is explicit process exit, not an automatic restart. Real-participant E2E remains pending; existing two-topic selection
and reviewed LLM wiring are unchanged. Timeout leaves the pending request visible and deliberately
blocks another request; do not manually archive requests or delete intent markers
to retry. Stop runners and reconcile ledger/journal evidence. If the operator
chooses to abandon that sandbox run, choose a reviewed whole-network reset/rebootstrap procedure before starting a
new run; this slice implements no automatic reset or journal cleanup. Do not
substitute manual marker deletion. The same applies if a consumed request has a
mismatched active proposal/action or the matched proposal is too near closing. No full demo integration claim is made.
Bootstrap input defaults to 60 seconds. Although the bootstrap authority accepts
5–300 seconds, steps 3/4 require at least 15; configure that before bootstrap.
The main request helper requires a window of at least 15 seconds and rejects a
matching proposal with less than 10 seconds remaining before ballot issuance.
This is a fail-closed minimum, not a promise that network voting always finishes.
Ledger evidence proves matching opener-signed contracts, not which external
client submitted them. Do not commit/land until packaging and full-flow review complete. Optional tests remain
independent and unchanged; main still ends at step 5.


## Ledger source catalogue (Java ingestion wired; live acceptance pending)

`SourceItem` records `SourceProposal` (GitHub PR) or `SourceMilestone` (candidate
issue), actual title/body, source URL, open/closed state, update/observation times,
optional parent PR and explicit relationship evidence. The unified key is
(opener, repository, number), deliberately excluding kind because GitHub shares
its issue/PR number namespace. Kind and number cannot change on revision.
Only `canton-foundation/canton-dev-fund` is accepted. Payloads and batches are
bounded. Opener is the sole signatory, observer and choice controller; there is
no membership claim or governor dependency.

`SourceItem.ReviseSourceItem` requires changed ledger-derived content digest,
nondecreasing observed/update times, and increments version with previousDigest.
An older content digest replay after a newer revision is not an unchanged-content
no-op: with a current receipt time but older updatedAt it is rejected as
`source updated time regressed`. The adapter must reject/reconcile stale remote
revisions rather than replay them as new observations. An older observedAt is
separately rejected as `source observation time regressed`.
The digest covers all content fields including state, title/body, links, update
time and verification classification; observedAt is receipt metadata, not content.
`ContentDigestChecked` is an opener assertion, NOT GitHub authenticity verification.
These records prove only what the opener recorded, never remote truth.

`SourceCheckpoint.CommitBatch` consumes the checkpoint and atomically upserts up
to 50 unique-number observations with progress. Unchanged content retains the same
item CID/version. Stale checkpoint consumption rolls back all batch writes.
High-water is nondecreasing and derived only from confirmed batch item numbers;
updating an already-known highest item needs no fresh create. Older-number edits
are accepted. Full sweeps are required initially and after at most three overlap
sweeps. Generation, phase, opaque continuation token and rolling content digest
record partial scan progress; completedFullGeneration changes only on declared
full-sweep completion. Adapter correctness must bind continuation to processed
pages and implement bounded overlap plus periodic complete repository pagination:
the ledger cannot verify GitHub coverage or that an opaque token skips no items.
A declared completion/digest is NOT proof all missing items were processed.
No absence action exists, even after completion: no deletion, MarkMissing or
Reclassify, and closed/reopened are explicit observed revisions, not disappearance.

This catalogue implements no VoteTopic, ballots, governor choice or agenda bridge.
The two existing pinned voting subjects remain unchanged. Default Java discovery
submits catalogue contracts and checkpoint batches through the source adapter;
explicit fixture mode does not. This wiring is not evidence of successful live
ingestion, authenticity, participant acceptance or full-flow integration.
The whole-ACS 20MB/10000-response bound and 10000-intent journal bound remain.
At a 30-second interval, successful ticks that each commit one batch can add up to
2880 batch intents per day: a 10000-intent budget is roughly 3.5 days at that
rate, before allowing for checkpoint initialization or existing intents. This is
an illustrative upper-rate budget, not a promise of exhaustion timing: failures,
latency, pauses and configured intervals change the rate. Even unchanged pages
commit checkpoint progress. No automatic rotation or deletion exists; reviewed
rotation and capacity monitoring are required before sustained live operation;
reviewed key lookup/indexing and rotation are prerequisites for unbounded live
acceptance. Stage 3/4 log-tail counts remain client reports, not ledger queries.
