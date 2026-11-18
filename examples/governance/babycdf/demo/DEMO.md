# BabyCDF live sandbox demo

**Status: steps 1-6 and `all` implemented and live-verified on a wall-clock
Canton network.** Sandbox only. No Foundation authority, no official approval,
no payment and no mint. The attestation model is planned, not built.

## What this is, and what it is not

Seven synthetic parties govern a demo architecture rule, a grant proposal, a
milestone and a Development Fund coupon allocation on a real Canton network with
real ledger time. Every refusal shown is a real rejected submission.

It is not a Canton Foundation process, it does not prove any real proposal or
milestone was approved, and it never moves value to anyone. The coupon step
allocates a `DevelopmentFundCoupon` in a sandbox-local Amulet configuration
whose DSO and fund manager are parties this demo created seconds earlier.

Presentation: [`docs/babycdf-pitch.html`](../docs/babycdf-pitch.html) is a self-contained slide deck; open it in a browser and navigate with the left and right arrow keys, or press `N` for presenter notes. The deck describes the attestation model and LLM-assisted evaluation as **planned design**; what this demo implements is deterministic text matching.

## Pane layout

Terminal 1, the narration of the system:

```sh
sh examples/governance/babycdf/demo/run-demo.sh logs
```

Terminal 2, the journey:

```sh
export LEDGER_PORT=41010 AGENT_INTERVAL=5
export BABYCDF_BUILD_ROOT=<approved-external-temp>
sh examples/governance/babycdf/demo/run-demo.sh up
sh examples/governance/babycdf/demo/run-demo.sh all    # or: step 2 .. step 6
sh examples/governance/babycdf/demo/run-demo.sh down
```

`all` runs steps 2-6 in order with `PAUSE` seconds between them, default 5. It
refuses unless the network is fresh: `bootstrap.json` must exist and no step may
have run or been attempted. `help` lists every command.

Prerequisites: Docker Compose >= 2.24, the existing dpm 1.0.22 / SDK 3.5.11,
Python 3, the pinned CIPS object and the sibling `canton-dev-fund` proposal blob.
`BABYCDF_BUILD_ROOT` must be absolute, existing and outside Concordia. Canton is
given 4 GiB with a 2 GiB heap, tested on one arm64 Docker Desktop host with
8 CPUs and about 8 GiB. The Ledger API is published on loopback only, with no
default token and no GitHub writes.

Each step runs once per network. After any attempt, run `down` then `up`: the
attempt markers are deliberate, and DARs must not be rebuilt between `up` and a
step because Canton rejects a changed package under the same name and version.

Every voting step waits for its wall-clock window with a bounded poll that
prints a `[TIMING] waiting tick` line every ten seconds and aborts after 600
ticks, so a stopped clock fails loudly instead of hanging. If the Docker host is
suspended mid-run, Canton can resume with a ledger time behind the script's
clock and refuse a resolution with `voting still open`. The quorum resolve in
step 6 retries **only that one condition**, up to ten times; any other refusal
aborts at once, so a genuine rejection is never retried into a pass. That retry
path has **not been exercised in a live run** — every recorded run resolved on
the first attempt — and it protects only the quorum resolve. The resolves in
steps 2-4 and in case 1 use a plain submit and still fail loudly.

## The journey

### Step 1, `up`: synthetic setup

Issues seven unit voting credentials, five direct and two registered, creates
the `AgendaGovernor`, and activates `EffectiveRule` v1 as an **opener-created
sandbox seed: v1 is unvoted, created directly by the opener, and is not a voted
or Foundation rule**. The first voted rule in the journey is v2, in step 2. The dedicated `babycdf-agent`
user is created with exactly two read-only rights, `CanReadAs <member>` and
`CanReadAs <opener>`, verified by exact equality; it is granted no `CanActAs`. A signed `DemoAnchor`
carries the run state; every later step finds it by a primitive run ID and
refuses unless it is at the expected step. The host scripts submit as the
synthetic opener and voters, never through the agent user.

The package gate before each step checks only that **local DAR bytes are
unchanged since bootstrap**. It is not a participant package inventory.

### Step 2: rule change, and a stale twin

Opens two competing `baseVersion` 1 agendas, casts 14 Yes votes, waits for the
60-second window plus a 3-second margin, then resolves and executes v2. The
losing twin is then executed against the now-advanced registry and is rejected:
the real participant error reports that the target `AuthenticKey` for
`babycdf-architecture-registry` *has drifted from its snapshot*. The code
asserts only that the rejection happened. The anchor advances afterwards and is
not what causes the rejection.

### Step 3: proposal review and vote

Votes on the compile-time pinned proposal subject and logs the pinned blob SHA
`f591787a772848e4537aba5d8ba02be1764647da`. The agents verify that digest
separately. Seven Yes votes produce an `ApprovedGrantProposal`.

### Step 4: advisory findings versus the agenda pin

Prints every clause finding and asserts the exact pair:

- `compose-with-asset-layer: InsufficientEvidence`
- `governance-execution-hooks: Pass`

The milestone evidence contains the execution excerpt only, not the composition
one. **These findings are advisory and do not gate the vote.** What gates it is
the hard-coded `validAgenda` pin, which accepts only `illustrativeMilestone`.
So the unpinned milestone subject is rejected, and the curated illustrative
revision is accepted in the same window: an implicit positive twin. The
rejection prints `AssertionFailed` with message `invalid agenda or window`,
which is a shared assertion covering both the agenda and the window, so it does
not uniquely prove the pin was the cause; the narration keeps reason unverified
by code. The word "immutable" inside the illustrative revision string is
illustrative only: it is not real pinned GitHub evidence.

### Step 5: Development Fund coupon allocation, live

This runs the real `AmuletRules_AllocateDevelopmentFundCoupon` choice on
wall-clock time with **no `setTime` and no weakened check**. The existing
`CouponDemo` script cannot be reused unchanged, because it calls
`setTime (addRelTime now (minutes 10))` and invokes the static governance
journey. A live adaptation is nevertheless possible, and the reason is specific
to the pinned `splice-amulet-0.1.22`:

- `validAmuletConfig` requires only `tickDuration > days 0`, so a short positive
  tick is a valid configuration;
- `AmuletRules_Bootstrap_Rounds` requires only `tickDuration <= round0Duration`.

The step therefore configures a 20-second tick, bootstraps the rounds, then
waits for real time to pass round 0's `targetClosesAt` before advancing. All
three `AmuletRules_AdvanceOpenMiningRounds` deadline requirements are satisfied
by the real clock and are left intact:
`roundToArchive.targetClosesAt <= now`, `latestRound.opensAt <= now` and
`middleRound.opensAt + tickDuration <= now`.

The configuration sets `isDevNet = True`, which is what a throwaway sandbox is;
it is not a production or MainNet configuration. The allocation's `reason` text
is deliberately prefixed `sandbox` and cites the step-4 demo contract and issue
#540 as narration provenance only. It is not a claim that issue #540 was
approved or funded by anyone.

The source coupon is minted by Splice's own DSO-controlled round transition, not
created by this demo. Two refusals are then exercised against the real choice
and their reasons **are asserted**, because the messages are stable:

- a wrong manager: `controller is the configured Development Fund manager`;
- an over-amount request: `insufficient amount to cover the requested allocation`.

The successful allocation is verified by exact equality on beneficiary, amount,
fund manager and reason, plus the leftover unclaimed amount, before the anchor
advances. **No Amulet is minted and no beneficiary collection happens**: in this
package version the coupon's only choices are `Withdraw`, `Reject` and
`DsoExpire`. The stated reason cites the step-4 sandbox milestone, which confers
no entitlement.

### Step 6: four blocked paths

Each case is a real submission whose participant error is printed.

1. **Invalid and foreign credentials.** Both attempts resolve the same closed
   proposal, and the step first asserts the window has actually closed, so
   neither case can pass merely because voting was still open.
   - The wrong-consortium credential is issued by this demo's issuer, so the
     opener can fetch it and it reaches the credential check itself. The
     refusal text `Contract group identifier mismatch` **is asserted**, because
     the credential is fetched through a `RightKey` carrying the consortium.
   - The foreign-issuer credential is not visible to this opener, so its
     refusal is a contract lookup failure rather than the credential check.
     The narration keeps reason unverified by code; the only assertion is the
     negative one, that the error is *not* `voting still open`.
2. **No quorum.** Only two of seven cast, below `atLeastPresent 3`. This path
   does not error: the engine returns a verdict. The code therefore **asserts
   `Lapsed` with no executions**, which is the strongest available check.
3. **Double vote.** The consumed ballot cannot be exercised again, and the
   already-cast ballot refuses a second vote with the stable message
   `already cast`, which **is asserted**.
4. **Non-member.** Two attempts, and what each one proves is stated exactly.
   - The outsider cannot see the governor, so proposing fails as a lookup
     failure and the `not a member` assertion is never reached. Reason
     unverified by code.
   - For the cast, the outsider targets a **live, uncast** ballot: a later
     ballot of the same round, asserted `Absent` and owned by someone else
     before the attempt. This matters because an earlier version reused the
     ballot case 3 had already consumed, which proved nothing about
     membership. After the refusal the ballot is re-read, still `Absent`, and
     then cast successfully by its own voter: that is what demonstrates the
     ballot was genuinely live when the outsider was turned away. The refusal
     itself is a visibility failure, so reason unverified by code.

Finally the step re-queries the step-4 `ApprovedMilestone` and asserts it is
still intact: no approved result is mutated by these attempts.

## Per-action logs

No ledger action happens silently. Every individual submission prints one
greppable line, alongside the `[STEP n]` narration and the `[TIMING]` lines:

```text
[STEP 3][ACTION] member-a (party member-a-9b3970be::1…) exercises AgendaBallot.CastAgendaVote on 00ab12… → Voted Yes → new ballot 00cd34…
[STEP 3][ACTION] opener exercises AgendaGovernor.ResolveAgenda (7 ballots, 7 credentials) → Decided Yes; execution 0012…
[STEP 6][ACTION][REJECTED] babycdf-non-member (party …) exercises AgendaBallot.CastAgendaVote on live ballot 0070… → participant error: … (BLOCKED as expected)
```

Each line names the acting party with a readable display name and a short party
ID, the template and choice (or `create`), the key arguments, and the result:
created contract IDs, shortened, or the decoded outcome. Expected failures are
tagged `[REJECTED]` and carry the participant error.

To show only the ledger actions:

```sh
sh examples/governance/babycdf/demo/run-demo.sh step 3 2>&1 | grep ACTION
```

Covered: party allocation, user and rights creation, credential invitations and
acceptances, the registry and rule seed, proposals, invite-ballot calls, every
cast, resolves, executions, anchor creation and advances, the Splice round
bootstrap and advance, issuance, the coupon allocation, and every blocked
attempt. The anchor lookup is a query, so it is one line rather than several.

## What the agents log

The Advisory and Reviewer containers run throughout, non-root, read-only, with
bounded resources, sharing Canton's network namespace.

The Reviewer's rule authority always comes from the live ledger, so it logs
`active EffectiveRule v1` during step 1 and `v2` from step 2 onward, with the
contract ID. Its evidence is offline and pinned, labelled
`offline_pinned_pr184_seed_not_fresh_github_poll`; `errors=1` is the missing
milestone issue in that PR-only seed, not a failed network call. Advisory
defaults to read-only with submission opt-in, which is not exercised live. There
is no `GITHUB_TOKEN` in the default agent environment and no authority fallback.

### The agent really validates the proposal text

The Reviewer evaluates the **actual pinned PR #184 text** against the clauses of
the **active ledger `EffectiveRule`**. The criterion is ledger data, not a
constant in the agent: each clause carries a human-readable `criterion`, a list
of `requiredPhrases` that must all appear, and `forbiddenPhrases` that must not.
The only constants in code are the matching mechanics.

**This is deterministic literal text matching, not comprehension.** A phrase
counts as present when it appears after case folding and whitespace
normalization, so a phrase wrapped across a markdown line break still matches.
No semantic, inferential or LLM-assisted understanding is performed or claimed;
LLM-assisted evaluation remains planned. Every finding cites the clause ID, the
rule ID, version and contract, the criterion it checked, and the phrases
involved. The result is `pass`, `fail` naming the missing or forbidden text, or
`insufficient_evidence` when there is no verified pinned text.

#### Rule v1 fails, rule v2 passes, on the same text

| | v1 (unvoted seed) | v2 (voted in step 2) |
| --- | --- | --- |
| `compose-with-asset-layer` | requires "CAP is designed to sit above existing Canton asset and settlement infrastructure" | unchanged |
| `independent-security-audit` | requires "independent security audit" | **removed** |
| `auditable-state-and-acceptance` | — | requires "auditable contract state" and "Acceptance Criteria" |

The v1 audit clause is grounded in the real document. PR #184 does discuss
auditability, at `#### Architectural Notes`:

```text
- auditable contract state and deterministic resolution logic
```

but it never commits to an independent security audit: the phrase
"independent security audit" (and "security audit", and "threat model") does not
occur anywhere in the pinned blob. That is a plausible Fund requirement the
proposal genuinely does not meet, so under v1 the agent reports:

```text
independent-security-audit | fail | required text absent: "independent security audit"
```

and its recommendation is `REJECT`. Rule v2 replaces that clause with one the
same text can evidence — auditable contract state plus a stated acceptance bar,
both present — so every clause passes and the recommendation becomes `APPROVE`.
Nothing about the proposal changed; the voted rule did.

#### What the vote actually decides

Step 2 votes to **activate the predefined v2 clause set**, and activation
publishes those clauses as ledger data on the new `EffectiveRule`. A vote does
not author arbitrary clause text: the candidate clause values are compiled into
the package, and `ActivateRule` selects the set for the version being activated.
What the vote changes is *which published clause set is authoritative*.

#### Two separate advisory evaluators

The Java Reviewer and the on-ledger `ApplyRule` are different evaluators with
different inputs, and neither gates the agenda:

| | Java Reviewer | on-ledger `ApplyRule` |
| --- | --- | --- |
| Input | the full pinned PR #184 document | a curated `PinnedExcerpt` list |
| Method | literal phrase matching on the text | excerpt list membership |
| Audit clause result | `fail` (the phrase is absent from the document) | `InsufficientEvidence` (no matching excerpt was curated) |

Both are advisory. That is why the same clause can read `fail` in the agent log
and `InsufficientEvidence` in a ledger `RuleApplication` without contradiction:
the agent searched the real document and found the phrase missing, while the
on-ledger review simply had no curated excerpt for it. Neither result gates
`ProposeAgenda`, the vote, or the resolution; the hard-coded `validAgenda` pin
does that.

**Presenter cue.** Keep terminal 1 on `logs`. Before step 2, point at the
`fail` line and the `REJECT`. Run `step 2`. When `EffectiveRule` v2 activates,
the next reviewer cycle re-evaluates the same pinned blob and the verdict flips
to `APPROVE`. That is the moment to pause on: the agent's answer changed because
the rules changed, not because the evidence did.

### The advisory recommendation

While an agenda is open, the Reviewer prints one textual recommendation per
agenda per rule version:

```text
[reviewer] RECOMMENDATION proposal_pr <roundId> under architecture-review v2 (cid 00fc2449f3a5…): APPROVE — compose-with-asset-layer: pass; governance-execution-hooks: pass; evidence: offline pinned PR#184 blob f591787a77… (advisory only, members decide)
```

The Reviewer was chosen over the Advisory agent because it already derives its
rule authority from the live ledger, so the recommendation can cite the active
rule version and contract it was actually computed against.

The mapping from findings is explicit and conservative:

| Findings | Recommendation |
| --- | --- |
| every clause `pass` | `APPROVE` |
| any clause `fail` | `REJECT` |
| any `insufficient_evidence` (and no failure) | `HOLD` |
| no rule, or no findings at all | `BLOCKED` |

A failure outranks missing evidence, and an unrecognized status is treated as
`HOLD`, never as approval. Each line cites the rule id, version and contract,
the evidence pin, and the `advisory only, members decide` label.

Each visible agenda is matched to the review of **its own subject**, and both
the subject URL and the exact subject body must match the revision the review
pinned. The URL alone is not enough: a rule-change (Architecture) agenda
deliberately reuses the pinned proposal's URL while carrying its own design
text, so a URL-only match previously handed `rule-v2` and its stale twin the
proposal's passing findings and recommended `APPROVE` on evidence that was never
about them. A Grant agenda's body is exactly the blob SHA, so it matches; an
Architecture body is design text plus a `proposal-blob:` suffix, so it does not.
Anything that does not match is reported `BLOCKED — not reviewable against
proposal evidence`, never assumed good.

Agendas already past their `closesAt` are skipped, since advice nobody can act
on is not useful. If agenda discovery fails, the reason appears on the
`[reviewer]` activity line rather than being silently dropped.

**The recommendation is advisory and changes nothing.** The `babycdf-agent`
ledger user holds exactly two rights, both read-only:
`CanReadAs <member>` and `CanReadAs <opener>`. It has no `CanActAs`, so it is
not merely restrained from voting but incapable of it, and incapable of any
submission. Bootstrap asserts that exact set by equality. In this demo the
voters are scripted to vote Yes regardless, so the recommendation does not drive
the outcome: the two are deliberately not coupled.

Presenter tip: keep terminal 1 on `logs` and point at the `RECOMMENDATION` line
before the window closes in step 3 and step 4.

### The agents never publish

The agents respond **in text only**: stdout and stderr. The GitHub
comment-posting path was deleted from `Reviewer`, not merely disabled, together
with its HTTP client, so no flag or environment variable can make it post; a
unit test asserts the absence of any publication method or HTTP type.
`ADVISORY_SUBMIT` is gone from the compose file, and the entrypoint now fails
closed if it is set to anything other than `0`, so the ledger-submission
invocation is unreachable from the demo. The `Advisory.prepareBallot` ledger
adapter still exists in the library with its own tests, because those tests are
meaningful and the class is not reachable from this demo's entrypoint.

One thing to expect when presenting: the agents report
`compose-with-asset-layer: pass` while step 4 asserts `InsufficientEvidence`.
That is not a contradiction. The agents review the pinned PR blob, whose text
contains the composition excerpt; step 4 reviews the milestone subject, whose
curated evidence omits it. Different inputs, different findings. The agents do
not decode `ApprovedGrantProposal`, `ApprovedMilestone` or the coupon: their
logs are evidence and rule observations only.

## Verified run

A fresh `up` (448.57 s) then `all` (571.15 s, `PAUSE=5`) then `down`, all exit 0.
Timestamps are from `docker compose logs --timestamps reviewer`.

**The verdict flip, same pinned PR #184 blob, clauses read from the ledger:**

```text
2026-11-18T12:15:58Z  rule v1
   compose-with-asset-layer    | pass | all 1 required phrase(s) present and no forbidden text found
   independent-security-audit  | fail | required text absent: "independent security audit"

2026-11-18T12:17:58Z  rule v2        (after step 2 voted it in)
   compose-with-asset-layer       | pass | all 1 required phrase(s) present and no forbidden text found
   governance-execution-hooks     | pass | all 1 required phrase(s) present and no forbidden text found
   auditable-state-and-acceptance | pass | all 2 required phrase(s) present and no forbidden text found
```

Recommendations from the same run:

```text
12:18:48Z RECOMMENDATION proposal_pr babycdf-agent:grant under architecture-review v2 (cid 00a66dabf855…): APPROVE — compose-with-asset-layer: pass; governance-execution-hooks: pass; auditable-state-and-acceptance: pass; evidence: offline pinned PR#184 blob f591787a77… (advisory only, members decide)
12:16:54Z RECOMMENDATION agenda babycdf-agent:rule-v2 under architecture-review v1 (cid 00c0f381a4e0…): BLOCKED — not reviewable against proposal evidence (subject concordia.md) (advisory only, members decide)
12:20:24Z RECOMMENDATION agenda babycdf-agent:illustrative-milestone under architecture-review v2 (cid 00a66dabf855…): BLOCKED — not reviewable against proposal evidence (subject issue#540) (advisory only, members decide)
```

| Step | Evidence from that run |
| --- | --- |
| 2 | 14 casts across two agendas; v2 executed; stale twin rejected with snapshot drift |
| 3 | voting opens 12:18:44.067212Z; `ApprovedGrantProposal` `00ce3c6ad6f6…` |
| 4 | accepted proposal 12:20:22.394510Z; `ApprovedMilestone` `0067ce14520e…` |
| 5 | 20 s tick; allocated 1.0; `DevelopmentFundCoupon` `0098e0bb50e9…` |
| 6 | four cases blocked; `Lapsed` asserted; step-4 approval re-checked intact |

The rule-change agendas are **not** approved from the proposal's evidence, since
matching requires the subject body to equal the pinned revision as well as the
URL. The milestone shows `BLOCKED` rather than `HOLD` because `BLOCKED` and
`HOLD` answer different questions: `HOLD` means the subject was reviewed and its
evidence is incomplete, `BLOCKED` means it was never reviewable against the
evidence held. The agent's offline seed contains the pinned PR blob only, so
issue #540 falls in the second category. With milestone evidence present the
same mapping yields `HOLD`, which the unit tests cover.

`[ACTION]` coverage in that run: 28 lines in step 1, 25 in step 2, 14 in step 3,
16 in step 4, 15 in step 5 and 38 in step 6 — 136 in total.

Contract IDs are from that run only; they are stale after `down` and are not
operational state. Negative guards verified on a fresh network: `step 5` and
`step 6` before their prerequisites both exit 2, an out-of-range step number
exits 2, and `all` refuses once any step has run or been attempted.

## Pitch mapping

| Slide | Step | What it shows |
| --- | --- | --- |
| Credentialed membership | 1 | Direct and registered unit credentials, verified agent rights |
| Governed change | 2 | A rule advancing by vote, and a stale twin blocked |
| Proposal review | 3 | A pinned proposal reviewed and approved |
| Evidence versus authority | 4 | Advisory findings separated from the agenda allowlist |
| Funding mechanics | 5 | A real coupon allocation with manager and amount checks |
| Failure modes | 6 | Credentials, quorum, double votes and non-members blocked |

## Limitations

- **Sandbox only.** Every party is synthetic and created by this demo.
- **No Foundation authority.** No real approval, mandate or governance decision.
  The illustrative milestone pin is not a verified immutable GitHub revision.
- **No payment and no mint.** Step 5 allocates a coupon; it does not transfer
  value, and this package version has no beneficiary mint path.
- **The attestation model is planned**, not implemented.
- **Rule v1 is a seed**, created by the opener rather than voted in.
- **Host submissions.** Steps submit as the synthetic opener and voters on an
  unauthenticated loopback sandbox, not through the agent user.
- **The package gate is byte-level**, not a participant inventory.
- **Not exercised:** Advisory submission, agent decoding of approval or coupon
  contracts, any GitHub write, any authenticated or multi-node topology, and the
  in-image dash/awk entrypoint cases, which were verified in an earlier round
  rather than every run.
- **Error reasons are asserted only where stable.** Where a refusal is a
  visibility failure or a shared assertion, the narration says the reason is
  unverified by code instead of implying a specific check fired.
