# Optional LLM rule interpretation (advisory only)

This feature is offline validated; live acceptance is still pending. Never use it as a governance gate.
The deterministic literal-match findings retain all existing authority. Reviewer
and Advisory are read-only; LLM results never submit votes or write the ledger.

## Evidence bundles

Reviewer fetch public GitHub data anonymously by default, or use
the same validated `INTAKE_GITHUB_TOKEN_FILE` as Intake via the optional
`agents-github-auth.yaml` overlay. Ambient `GITHUB_TOKEN` is ignored. Tokens are
sent only to api.github.com and never to the LLM gateway. Requests embed a JSON-string evidence bundle with the subject URL,
head SHA, sorted file paths/blob SHAs/byte counts/full contents, and completeness.
Issue bundles carry title/body, no files, and completeness=false. No bundle is
written to read-only `/state`. Offline mode uses the same verified blob adapter.
The aggregate file text budget is 200 KiB. Excluded, failed, binary, invalid UTF-8,
oversized or unverified files make completeness=false; contents are never silently
truncated. A full 100-entry first page is conservatively incomplete; no claim of
exhaustive pagination is made. Request reservations use actual serialized bytes.
The run reservation ceiling is 2,000,000 conservative token units plus the existing
four-evaluation/eight-attempt limits; the response body cap remains unchanged.
Cache identity includes URL, head SHA, sorted paths/blob SHAs and completeness.
Completeness is evidence metadata only in this slice, not new absence semantics.

Large requests share the existing absolute 60-second request/parser deadline;
production does not retry. Bodies are byte-bounded before parsing. See
[GITHUB-TOKEN.md](GITHUB-TOKEN.md) for the exact Reviewer-only recreation
command for shared GitHub auth, preserving ledger and Intake. GitHub PATs never
enter bundles, LLM requests or cache identities.

Read-only secret mounts limit filesystem mutation, not the PAT's GitHub permissions.
Use a minimal public-read PAT: no publication path in this code does not mean a
more privileged credential cannot publish elsewhere. Body deadlines close the
stream cooperatively; the slow-stream test proves close-responsive behavior,
not forcible interruption of an arbitrary non-cooperative InputStream.
Proposal byte verification remains independent of supplemental completeness.
An invalid/incomplete bundle blocks supplemental evaluation but never changes
otherwise verified deterministic findings.

## Rule-by-reference authority

Rule clauses live in `rules/rule-v1.json` and `rules/rule-v2.json`, not Daml.
The ledger votes on a RuleRef location, optional commit and exact-byte SHA-256.
Whitespace and terminal newlines are part of the hash. Agents resolve and verify
before parsing. Relative paths are confined to `babycdf.rules.root` (default the
BabyCDF root, `/app` in containers); traversal and symlinks are rejected. Rules
are copied into `/app/rules` in the image, under the read-only agent filesystem.
To point elsewhere, vote a new reference to an immutable GitHub blob/raw HTTPS
URL at a full commit and its matching SHA-256. The shared controlled GitHub API
client retrieves it; other schemes fail closed. Unavailable/hash-mismatched rules
yield rule_unverified; invalid schemas yield malformed_rule. No local fallback
may replace an unverified ledger reference.

## Clause kinds and code-side presence resolution

Ledger kinds are CONTRADICTION_OR_SUPPORT (the unchanged existing default) and
PRESENCE_REQUIRED. Unknown kinds yield INCONCLUSIVE. For presence clauses, code
resolves missing verified support as FAIL(absent) only on a complete valid pinned
PR bundle, otherwise INCONCLUSIVE(incomplete_evidence). A model FAIL accompanied
by verified support is INCONCLUSIVE(conflict), never a decisive failure.
Option A is used: conflict quotes remain as verified supporting evidence for
cache re-resolution, with `conflict_quote_role=verified_supporting_non_authoritative`
in the displayed assessment. They do not authorize the inconclusive verdict.
The same resolution function is applied to fresh and cached candidates.

## Exact versus commentary

`verdicts` is the exact part: one id, verdict, bounded rationale and evidence
quotes per supplied clause. Allowed verdicts are PASS, FAIL, INCONCLUSIVE and
NOT_APPLICABLE. Insufficient evidence is INCONCLUSIVE, not a separate category.
Code computes overall: any FAIL => FAIL; otherwise any INCONCLUSIVE =>
INCONCLUSIVE; otherwise at least one PASS => PASS; all NOT_APPLICABLE =>
NOT_APPLICABLE. An empty/invalid response is INCONCLUSIVE. Every PASS/FAIL must
have a quote with at least eight non-space characters verified as a whitespace-normalized substring of supplied bounded
evidence; otherwise that clause becomes INCONCLUSIVE (`unverified_quote`).

`commentary` is separate optional free text, labelled **advisory, not verified**.
It cannot affect exact parsing, clause verdicts or aggregation. Missing/non-text
commentary does not invalidate exact results. Displayed commentary is length
capped, controls/format/line separators removed and credentials redacted. Cache
stores both; verdict identity is defined only by the exact part.

Temperature is zero. No seed is required from the gateway. LLM sampling is not
mathematically deterministic: reproducibility means reusing the stored exact
result for identical prompt-version, configured model, rule and evidence digests.
The bounded, file-locked cache uses the existing `/tmp` tmpfs in each agent,
without new writable mounts. Stopping or restarting a Compose container clears
its tmpfs cache, so valid verdicts can be paid for again (at most four calls per
process). Only direct CLI runs on a normal persistent `/tmp` can reuse the file
cache across process restarts. The returned
model is recorded. Only schema-parsed provider results are persisted, including
quote-downgraded results. Transport, HTTP, timeout, budget, invalid-rule and local
failures are deduplicated only within the process and retried after restart.
There is deliberately no timed retry within a running process, including for
HTTP 429/5xx, timeout and transport failures; an operator must restart the agent
to retry those inputs, which also resets the process budget.
A direct CLI process restart resets the process budget without removing valid
cached verdicts on normal `/tmp`. A Compose container restart also clears tmpfs
and therefore removes its cached verdicts.
Cache roots must be current-user-owned and not group/world writable. Corrupt or
unverified cached results are re-evaluated and atomically replaced. Failures must
never be interpreted as successful compliance.

## File secret provisioning

Use an absolute regular non-symlink file, readable by the agent. Never paste keys
into command history, tracked files, logs or chat. For example in Bash:

```bash
install -d -m 0700 "$HOME/.babycdf-secrets"
umask 077
read -r -s -p 'LLM API key: ' key; printf '\n'
printf '%s\n' "$key" > "$HOME/.babycdf-secrets/llm-key"
unset key
chmod 0444 "$HOME/.babycdf-secrets/llm-key"
export LLM_API_KEY_FILE="$HOME/.babycdf-secrets/llm-key"
export BABYCDF_LLM_ENABLED=1
```

A configured invalid file fails closed; no fallback env key is used. Contents
must be 1–4096 printable ASCII characters with at most one terminal LF.
`LLM_API_KEY` and legacy environment resolution remain supported when no file is
configured. Defaults remain `https://ai-gateway.unlockit.io/v1` and
`claude/claude-sonnet-4-6`; override with `LLM_BASE_URL` and `LLM_MODEL`.

From the repository root, after offline review and when explicitly authorized to
operate the live stack, recreate **only** the two read-only agents:

```bash
docker compose -p babycdf-live --profile agents \
  -f examples/governance/babycdf/demo/compose/compose.yaml \
  -f examples/governance/babycdf/demo/compose/llm-auth.yaml \
  up -d --no-deps --force-recreate reviewer
```

Do not run down or recreate Canton/Intake: the ledger and source inventory must
be preserved. The runner selects this overlay only when enabled with an explicit
absolute file path. No live provider calls are needed for tests.

### Deterministic presence collection

When the effective verified rule contains a `PRESENCE_REQUIRED` clause, agents collect the bounded verified file bundle even when LLM assessment is disabled. Deterministic findings therefore do not depend on the LLM switch. Contradiction-only rules retain the existing supplemental collection gating. Blob and bundle caches bound repeated GitHub reads. Absence still requires pinned, reread-consistent, complete and affirmatively valid evidence. The dedicated rules root defaults to `../rules` from the Java working directory (`/app/rules` in the image); seeded `rules/` locations are resolved relative to that root.

Daml-side commit-reference validation remains deferred because adding it changes the package. Seed hashes are checked against exact rule document bytes without changing Daml.

## S5: runtime rule references and grounded outcomes

This supersedes earlier demo clause examples. Bootstrap script input supplies `initialRuleRef` and `proposedRuleRef`; the runner hashes exact JSON bytes at runtime. The signed demo anchor carries the proposed reference into the v2 vote. No rule text hash is compiled into the live Daml flow; fixed Daml references are synthetic test data only. Rule-document edits after this migration do not require a new Daml package.

Pinned PR #184 (one complete verified file): v1 fails the three-proving-domain requirement using “Both reference flows (auction and governance) run end-to-end on a Canton sandbox.” and fails the independent audit presence requirement as `absent`. V2 passes composition, auditable deterministic state, and the acceptance-criteria heading. Issue #540 remains INCONCLUSIVE: it has none of those v2 support phrases and is not eligible for absence. Downstream execution hooks are deliberately not a v2 criterion because that phrase also occurs in #540.

Verified unsafe model quotes are represented in cache by normalized-file offsets and SHA-256, not plaintext; text is reconstructed against current evidence and displayed only redacted. A verified unsafe contradiction can retain FAIL; presence without an indicator remains `semantic_support_unconfirmed`.

Additional log reason codes: `semantic_support_unconfirmed` (verified semantic quote without code indicator), `presence_evaluation_failed` (contained predicate error), `malformed_clause` (invalid rule clause), `unsafe_quote_redacted` (unsafe display content; quote references avoid plaintext persistence), `rule_unverified` (document authority unavailable).

### S5 cache reference integrity

Quote references expose only an opaque index into the bundle sorted by file path (slot 0 for single text or issue title/body), normalized-text start/end offsets (and hence length), and redacted display text. File paths and per-quote digests are not persisted. All verified quotes are whitespace-normalized before resolution. The evidence-bound cache key selects the current bundle; references are bounds-checked, reconstructed, normally quote-verified and re-resolved. The private owner-checked cache directory is the integrity boundary; offsets are not a cryptographic authenticity proof. This supersedes the earlier SHA-256 quote-reference description.

### Live assessment and recovery clarifications

Issue #540 is expected to be INCONCLUSIVE, but contradiction/support outcomes remain model-dependent; deterministic quote verification rejects invented support, not all possible interpretations. Presence without eligible complete proposal evidence deterministically yields `incomplete_evidence`. Diagnostics exist only on fresh assessments; memory and disk cache hits report `diagnostics: unavailable_cached`. Diagnostic quotes are redacted, control-cleaned and capped at eight quotes of 240 bytes each. They are not persisted to the verdict cache.

Generic uncertain or pending Intake failures are retried automatically at the configured interval, without blind replay. Progress resumes only when authoritative reconciliation succeeds. Definitely-rejected commands or unavailable historical proof require operator investigation; do not remove intents or reset checkpoints. HTTP-policy suspension is a separate condition and may require controlled restart after its cause clears.

### Agent evidence mode

With the shared PAT overlay (`INTAKE_GITHUB_TOKEN_FILE` set), Reviewer use live authenticated GitHub evidence by default. The fixed PR #184 head/blob identity remains required; mismatch fails closed. Without the overlay, or with `AGENT_EVIDENCE_MODE=offline`, they use the explicit offline PR fixture plus packaged issue #540 observation. Startup logs identify authenticated_file, anonymous, offline_fixture or offline_repository without credentials. Canton must already be running. To recreate only the agents after selecting the overlays, run `docker compose -p babycdf-live --profile agents -f compose/compose.yaml -f compose/intake-auth.yaml -f compose/agents-github-auth.yaml -f compose/llm-auth.yaml up -d --no-deps --build --force-recreate reviewer` from the demo directory. This command is documented only, not run by validation.

### Supplemental scheduling and cost

Within each cycle, budget is consumed by pinned proposals first (#184), explicit ledger/default milestones next (#540), then other searched milestones by issue number. Deterministic findings and output ordering do not change. `BABYCDF_LLM_MAX_ASSESSMENTS` defaults to 4; empty means default, otherwise only integers 1–32 are accepted and invalid configuration fails startup. Compose forwards it only to Reviewer. For the demo, `BABYCDF_LLM_MAX_ASSESSMENTS=6` allows the main subjects and a few others. Increasing it increases potential paid calls and proportional attempt/token reservations (2 attempts and 500,000 reserved-token units per assessment); per-input byte limits remain fixed. Cache hits consume no run budget. This remains a per-process run budget, not a fresh allowance each poll.

## Single reviewer runtime

Reviewer is the sole read-only evidence agent. One ledger rule read and one evidence collection feed deterministic review, at most one supplemental assessment per subject, narration and non-submitted ballot previews. Advisory remains a library; its main and old service invocation are retired, not aliases. Existing schema-1 preview state is accepted via `Reviewer --state-file`; existing Reviewer cache location remains unchanged. `--offline-pr184-file` and `--offline-pr184-repo` are evidence modes only and never replace active ledger rule authority. No submission adapter is reachable from Reviewer. Budget/log cleanup is deferred to S2.

### Bounded migration from the old Advisory container
Source retirement affects new invocations only; an already-running old container must be retired separately. Canton must already be running. Do not perform this migration during offline validation.

```sh
old=$(docker ps -aq --filter label=com.docker.compose.project=babycdf-live --filter label=com.docker.compose.service=advisory) || { echo 'BLOCKED: cannot list containers' >&2; exit 2; }
set -- $old
if [ "$#" -gt 1 ]; then
  echo 'Refused: multiple old Advisory containers match' >&2
  exit 2
fi
if [ "$#" -eq 1 ]; then
  docker stop "$1" && docker rm "$1" || exit 2
fi
docker compose -p babycdf-live --profile agents -f compose/compose.yaml -f compose/intake-auth.yaml -f compose/agents-github-auth.yaml -f compose/llm-auth.yaml up -d --no-deps --build --force-recreate reviewer
```
