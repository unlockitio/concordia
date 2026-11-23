# Source runtime slice (candidate; not live accepted)

## S2 evidence enrichment (no S3 opener queue)

R3: executable delivery requires combined complete issue/comments/events capture;
partial enumeration never pins. VerifiedAmendment requires BOTH proposal statuses
ready and complete valid nonempty pins, with strict duplicate-key JSON rejection.
Uncached fresh reads share pages403/429 category/backoff. Command parsing is
CommonMark-like for the tested cases, NOT a full CommonMark implementation.
Fixture pattern scanning is defense-in-depth, NOT proof that no secret exists.

R2 trust constraints: runtime PR collection confirms head with an UNCACHED final
metadata read; any change blocks the entire pin. Pins include EVERY changed path,
not just the eligibility-matching subset. Edited command history is always unknown:
no definitive result or executable pin, even when today contains /vote. Command,
body and result uncertainties are separate, authors recorded but authorization NOT
established. GitVote interval correlation is inferred, not explicit provider linkage;
ambiguous intervals/history gaps remain unresolved. Each entry records per-source
retrieval times/statuses. Pure Capture/text APIs require trusted provenance and are
not authoritative for partial/failed inputs.

Strict concordia-amendments.json schema binds repository/evidenceID and original+
amending numbers/pins; only parsed VerifiedAmendment has a private constructor.
Real manifest is empty: no explicit evidence linking475 was captured; every unlinked
eligible PR remains its own proposal round. check-fixtures.py sanitizes and validates
ALL fixture JSON/hash coverage/public-login allowlist; no new public requests inR2.

SourceEvidenceCollector composes paginated PR metadata/files/head-tree and
issue/comments/events into deterministic manifest batches. Its configured runtime
factory uses existing IntakeGitHub file-backed credentials; offline capture inputs
exercise the identical pipeline. It emits explicit open/unresolved GitVote states,
checks app login/type/numericID plus source URLs and unique creation/result interval,
and rejects conflicting/edited app evidence. Historical unknowns remain blocked.
Explicit amendment declarations require checked manifest digests and source IDs;
append corrections validate superseded entry digests without changing round identity.
Cache is process-local (256 entries/16MiB); partial resume is caller-carried, not an
S3 durable queue. Header/body deadlines are each five seconds, response2MiB.

SourceEvidence is a pure evidence model: proposal eligibility is ANY changed
proposals/** or rfps/** path, not title/merged state. Eligible file pins are sorted
path/status/blob records verified against the full head tree; removed files block
pending explicit base-tree proof. Rename previous path remains explicit. No title
inference establishes amendments. SourceEvidenceFetch offers bounded pages/request/
time, ETag cache reuse, partial resume and existing GitHubFetchFailure/SourceHttpFailure
rate-limit policy. Runtime transport is explicit IntakeGitHub.evidenceTransport;
construction does not read any PAT until existing configured runtime provisioning.

Milestone command identity is repository+issue+commentID. Standalone /vote outside
quotes/fences creates a round; unambiguous label fallback only without command
evidence. Edited/deleted history and trigger-time issue digest gaps remain explicit.
Delivery requires an explicit full40hex SHA in an unedited preceding comment and
matching proof SHA/key no later than trigger. ALL branch reachability/backdate
fallback is disabled; current branch head is never substituted. Missing proof is
blocked_pin. Provider results must correlate with trigger evidence; ambiguity or
conflicting results remain unresolved. Merge/closed is never a vote outcome.

Canonical manifest uses fixed array scheme, replay_as_of/retrieved timestamps and
total UTC/type precedence/repository/number/numericID order. Corrections carry same
roundID and supersedes identity rather than changing a historical round. Public
fixtures under demo/fixtures/concordia record retrieval/budget/gaps and SHA256SUMS.
47 anonymous requests used,13 of60 remain; no PAT was read. No ledger queue/opening
or S3 policy is added by S2.

`Intake.main` now selects source discovery with `INTAKE_DISCOVERY_MODE`:

- unset or `live`: token-authenticated GitHub source pages (explicit
  `INTAKE_GITHUB_TOKEN_FILE` required) committed through `JavaSourceIngestion`
  and `SourceCatalogueLedger`, using explicit impl and demo package arguments and opener.
- `fixture`: explicit offline advisory inventory only; no production SourceObservationCredentials.

The existing CLI `--offline-fixture grant-file ...` concerns the curated grant
verification bytes, **not** source discovery mode. It remains for compatibility.
Offline invocations must also set `INTAKE_DISCOVERY_MODE=fixture`. SourceObservationCredentials are
unverified candidate evidence, never additional voting topics or agenda authority.
No LLM calls or actAs rights were added.

Each source tick performs at most one GitHub request and one atomic engine batch.
Agenda polling uses a separate source worker so proof reads cannot block agenda
polls. GitHub header/body deadlines are each five seconds. The ordinary runtime
source interval is at least thirty seconds, including ordinary failures; HTTP
403/429 can delay or suspend attempts as described below. This is not a strict
aggregate 30-second bound on ledger proof reads (existing bounded 15-second RPCs
remain).

## Source credential trust contract (S1a)

The opener is the issuer and trust anchor. Credential signatories are derived
from the generated view's anchorers plus holders (sole opener anchorer, no
holders). Template invariants explicitly require the Party issuer among unique
anchorers, unique holders, required VerifiableCredential/BabyCDFSourceObservation
and kind subtype types, and exactly one subject. The pinned CIP structural
helpers are applied; this is an issuer assertion, not independent GitHub proof.

Factory-issued revisions enforce predecessor lineage against the fetched active
key. Direct opener creation is intentionally permitted: even a version greater
than one with a well-formed previous digest need not have a real predecessor.
The registered view is NOT proof of factory issuance or ledger-verified lineage.
Default Archive is available to signatories. Future S1b must verify fingerprint
and version against the copied pin; it must not treat either as lineage proof.

Raw body text is excluded from durable catalogue command payloads, contracts,
journals and logs. Transient HTTP/JSON discovery parsing necessarily holds body
text until raw UTF-8 hashing; separate Reviewer evidence fixtures and HTTP input
are not durable catalogue observations. SourceItem references in immutable legacy
captures and rejection tests are intentional legacy evidence, not current schema.

## Explicit credential configuration

See [operator token provisioning and Compose commands](../demo/GITHUB-TOKEN.md).
Both the live source worker and optional live inventory use `IntakeGitHub` with
an explicit file-backed Bearer token. Live startup fails closed before ledger or
journal construction if configuration is missing or invalid. Fixture mode needs
no secret. No ambient GitHub/LLM credential or anonymous fallback is used.
HTTP 401 suspends source requests without checkpoint mutation until deliberate
credential replacement and Intake-only restart. Tokens are never diagnostic data.

## HTTP 403/429 delay and process-local suspension

Non-200 responses fail the source cycle without advancing its checkpoint. Error
bodies are not read or logged. Both live transports retain only
`X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`, and
`X-GitHub-Request-Id`; diagnostics emit only single-valued, bounded, sanitized
values. Returned reset values are evidence from that response, not proof that an
unrelated historical HTTP 403 was caused by rate exhaustion. No historical cause
is established by this change.

For HTTP 403 or 429, a valid `Retry-After` supplies integer seconds or an HTTP
date. If `X-RateLimit-Remaining` is exactly `0`, a valid epoch-seconds
`X-RateLimit-Reset` is required. When both supply deadlines, the later is used.
The scheduler delays from receipt/failure using its monotonic clock, for at least
60 seconds and at least the configured source interval. Supported delays up to
24 hours permit autonomous resumption on a later tick after the deadline; there
is no sleeping worker or immediate retry loop. Zero or past deadlines still get
the 60-second minimum.

Missing/unsupported retry evidence, invalid present `Retry-After`, an invalid or
missing reset when remaining is `0`, or a computed delay over 24 hours suspends
automatic source requests for that scheduler instance. Unknown HTTP 403 is not
unconditionally retried. Suspension is **process-local**, not a durable ledger
flag, and has no automatic expiry. Ordinary non-403/429 failures retain the
configured interval. The original failure propagates; delayed/suspended ticks
return `SKIPPED`, never synthetic completion or successful recovery, and do not
clear the remembered error.

For a suspended process, the operator must establish from available safe evidence
that the provider restriction/cause has cleared before authorizing a controlled
restart. Restart recreates the scheduler and loses its local delay/suspension;
it must not be used to bypass an active or unknown restriction, nor placed in an
automatic restart loop. Preserve the existing checkpoint and all journal files:
do not reset cursors, remove markers, or replay ledger commands to recover from
an HTTP failure. Known supported deadlines normally resume without a restart.
This documentation does not authorize a restart or a diagnostic public request.

Only repeated FULL sweeps are implemented: pulls then issues, state=all, 50 rows,
updated ascending. High water is not used as a cursor. The ledger's FullSweep
phase stays unchanged when the endpoint changes. Tokens bind a fixed repository,
endpoint, page, row index and split-page digest; they never accept arbitrary URLs.
Each observation is measured using the certified protobuf codec before selecting
subbatches, with a 700KB observation budget and the adapter's exact 999992-byte
request check retained. Tokens advance only with atomic ledger confirmation.
Changed split pages durably rewind to the page start before processing shifted
indices; fingerprint reconciliation suppresses repeated unchanged observations.
GitHub page-number scans are not snapshots: concurrent mutations can move rows;
periodic full sweeps provide eventual reconciliation, not snapshot completeness.

Compose and Java both default to `live`; `INTAKE_DISCOVERY_MODE=fixture` is an
explicit offline-only synthetic inventory override and starts no source worker.
`INTAKE_SOURCE_INTERVAL_MILLIS` defaults to 30000 and accepts 30000..3600000.
The curated `--offline-fixture` grant bytes do not select discovery mode.

The source adapter gets its own `/journal/sources` child journal, created as the
existing unprivileged container UID with mode 0700. Existing journal symlink
checks apply; parent/child owners must match and existing permissions must be
0700. Invalid directories fail closed without repair. Confirmed mutation logs
come only from the engine's transaction proof, never inventory counts.

## Explicit unresolved live-acceptance limits

This slice does **not** solve the adapter's whole-ACS lookup (20MB/10000 responses)
or journal exhaustion at 10000 intents. No automatic rotation/deletion occurs.
There is no server-side business-key filter in GetActiveContracts. A reconciled
key-to-CID index and validated rotation design require a separate change before
unbounded live acceptance. Source bodies are transient discovery input only; durable observations carry raw
UTF-8 body SHA-256 hashes, not body text. HTTP response bounds still apply.
Credential fingerprints use canonical schema v2 and bind revision lineage;
legacy checkpoints/intents require reviewed reconciliation and fail closed.
No credential-bound agenda behavior is introduced in S1a. Compose now defaults to live ingestion;
ledger-derived stage counts, runner narration and full deck alignment remain
separate acceptance work. Existing log-tail client counts are not ledger counts.
Do not run the live mode as an acceptance demonstration until those are reviewed.
