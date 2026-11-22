# BabyCDF live sandbox demo

Optional shared GitHub file-secret authentication for Reviewer is
specified in [GITHUB-TOKEN.md](GITHUB-TOKEN.md), including the exact agent-only
recreate command preserving the ledger. Reads are anonymous without that overlay;
legacy ambient GITHUB_TOKEN is ignored. The same Intake validator is reused.

**Status of this revision: OFFLINE VALIDATED, LIVE ACCEPTANCE PENDING.**

Current runtime: two Java processes (Intake and Reviewer). Intake has CanActAs opener and submits one
OpenAgenda bridge per step 3/4; Reviewer have CanReadAs opener only.
The unauthenticated sandbox API is not an enforced security boundary. Main `all`
ends at step 5; optional rounds 1–5 remain separate. Intake defaults to bounded
token-authenticated GitHub pagination (first provision the explicit file secret:
[GITHUB-TOKEN.md](GITHUB-TOKEN.md)) and SourceItem catalogue ingestion in a separate
worker; agenda polling continues independently. Source attempts default to 30
seconds, configurable with `INTAKE_SOURCE_INTERVAL_MILLIS` (30000..3600000).
Only explicit `INTAKE_DISCOVERY_MODE=fixture` selects synthetic offline inventory
and starts no source worker. No synthetic fallback or extra voting topics exist.
Mutation lines follow transaction proof. Steps 3/4 separately query SourceItem
active contracts as opener before opening their curated agenda: total, distinct
(opener, repository, number) keys, SourceProposal and SourceMilestone counts.
Zero is valid; sync completeness is unknown, and voting does not wait for a sweep.
Stage log-tail client reports remain separate from these persisted observations;
neither establishes GitHub completeness or verification of an upstream snapshot. HTTP deadlines are five seconds per request/body;
ledger proof RPCs have separate bounds. Whole-ACS and journal limits remain; see
`../java/SOURCE-RUNTIME.md`. No live ingestion success is claimed by this wiring. Real LLM calls are
opt-in supplemental assessments through the configured gateway (including
Gertrudes when configured); returned-model metadata, not an alias, identifies the
actual response. Offline tests do not establish live participant acceptance.

Steps 1-5 and optional rounds 1-5 are implemented; `all` finishes at step 5. Retired step 6 refuses without submitting. The current behaviour — decisions carried
by a quorum of three core members, and the presentation-first agent logs — is
validated **offline only**: isolated Daml build and script suite, Java unit
tests, the narration and quorum guard, the shell and entrypoint suites, and
captured agent transcripts. **It has not been run on a live Canton network.**

Sandbox only. No Foundation authority, no official approval, no payment and no
mint. The attestation model is planned, not built.

> **Earlier live verification describes earlier behaviour.** The recorded run in
> [Verified run](#verified-run) was taken **before** this revision. It exercised
> the *prior* behaviour: **seven Yes votes per round** and the **verbose agent
> log format**. Its timings, contract IDs, `[ACTION]` counts and log excerpts are
> evidence for that prior revision, **not for the current one**. Nothing in this
> document should be read as a live verification of three-core quorum or of the
> concise agent logs.

## What this is, and what it is not

Seven synthetic parties govern a demo architecture rule, a grant proposal, a
milestone and a Development Fund coupon allocation on a real Canton network with
real ledger time. Every refusal shown is a real rejected submission.

Decisions are carried by a **quorum of three core members**, not by a full
house: in each positive step exactly three of the five permanent members vote
Yes and the remaining four members are absent. That is deliberate, because it is
the case worth demonstrating — quorum is met by exactly one vote's margin, and
step 6 shows the same round with two votes lapsing for want of quorum. See
[Attendance, quorum and majority](#attendance-quorum-and-majority). This is the
current, offline-validated behaviour; it is awaiting live acceptance.

It is not a Canton Foundation process, it does not prove any real proposal or
milestone was approved, and it never moves value to anyone. The coupon step
allocates a `DevelopmentFundCoupon` in a sandbox-local Amulet configuration
whose DSO and fund manager are parties this demo created seconds earlier.

Presentation: [`docs/babycdf-pitch.html`](../docs/babycdf-pitch.html) is a self-contained slide deck; open it in a browser and navigate with the left and right arrow keys, or press `N` for presenter notes. The attestation storage model remains planned. The running Java agents support opt-in real LLM-assisted supplemental assessments; deterministic evidence checks and ledger rules retain authority. Intake now submits as opener through OpenAgenda, while Reviewer remain read-only.

## Compose service and network naming

Offline naming regression check (requires the existing pinned CIPS export;
only renders configuration, without creating containers or networks):

```sh
python3 -B examples/governance/babycdf/demo/test-compose-config.py
```

The demo-owned Compose service is `canton` (default container
`babycdf-live-canton-1`); it still extends the upstream CIPS service
`credential-canton`. No explicit `container_name` is set, preserving project
isolation; the default Compose project name remains `babycdf-live`. The local
network is also `canton`, rendered as `babycdf-live_canton`. Its attachment
uses `!override` (Compose 2.24.4 or newer, already needed for the port override)
to replace, not append to, the inherited `credential-network`. Agent services
continue to share `service:canton`'s network namespace. Upstream CIPS exports,
Canton node identities and the `credential-synchronizer` healthcheck identity
remain unchanged; these are upstream internals, not Docker service/network
names. The inherited `CREDENTIAL_CANTON_MEM_LIMIT` compatibility setting also
remains unchanged. This source
rename does not affect an already-running container. The old
`babycdf-live-credential-canton-1` becomes an **orphan** relative to the new
configuration: plain `./run-demo.sh down` does not remove that old service.
The updated runner and `ledger.sh` target `canton`; the ledger helper cannot
query the old service until this transition is completed and does not
auto-detect it or silently select another network namespace.

**Deferred transition — do not execute during live validation.** Ending the
current run is the user's choice: teardown loses the participant's in-memory
ledger state. When ready to end that run, use the following commands from
`examples/governance/babycdf/demo`. First inspect the explicitly named project;
the label-filtered listing also includes orphan containers that the current
Compose configuration may not list:

```sh
# Deferred, read-only inspection: verify these are all demo-owned containers.
docker compose -p babycdf-live --profile agents -f compose/compose.yaml ps -a
docker ps -a --filter label=com.docker.compose.project=babycdf-live
```

Only after verifying there are no unrelated services in that project, perform
the deferred transition below. `--remove-orphans` removes **all** services no
longer defined in the configuration within the same `babycdf-live` project,
not just `credential-canton`. The explicit `-p babycdf-live` prevents selecting
a different project identity. Do not add `-v`; volume removal is not needed.
The runner's separate `down` then clears its local demo state before `up`:

```sh
# Deferred and destructive to this run: execute only when ready to end it.
docker compose -p babycdf-live --profile agents -f compose/compose.yaml down --remove-orphans
./run-demo.sh down
```

The old `babycdf-live_credential-network` may remain: Compose `down` removes
networks defined by the current configuration, and `--remove-orphans` concerns
containers, not arbitrary old network definitions. Before starting again,
inspect the exact old network if it exists (these are also deferred commands):

```sh
docker network inspect babycdf-live_credential-network
```

Verify its Compose project label is `babycdf-live`, that it is the obsolete
demo network, that nobody still needs it, and that `Containers` is empty.
Only if all checks hold, remove that exact network; if it is absent, skip
removal. If containers remain attached or ownership is unclear, stop rather
than disconnecting them, forcing removal, or pruning other networks.

```sh
# Deferred: only after the ownership and empty-attachment checks above.
docker network rm babycdf-live_credential-network
# Start the new demo only when ready; it creates babycdf-live_canton.
./run-demo.sh up
```

Do not restart a live validation just to apply these renames.

## Pane layout

Terminal 1, the narration of the system:

```sh
sh examples/governance/babycdf/demo/run-demo.sh logs
```

Terminal 2, the journey:

```sh
export LEDGER_PORT=41010 AGENT_INTERVAL=5
sh examples/governance/babycdf/demo/run-demo.sh up
sh examples/governance/babycdf/demo/run-demo.sh all    # or: step 2 .. step 5
sh examples/governance/babycdf/demo/run-demo.sh down
```

`all` runs steps 2-5 in order with `PAUSE` seconds between them, default 5. It
refuses unless the network is fresh: `bootstrap.json` must exist and no step may
have run or been attempted. `help` lists every command.

Prerequisites: Docker Compose >= 2.24, the existing dpm 1.0.22 / SDK 3.5.11,
Python 3, the pinned CIPS object and the sibling `canton-dev-fund` proposal blob.
Build artifacts go to `examples/governance/babycdf/.build/` by default, which is
gitignored and created automatically — **no `export` is needed**. Set
`BABYCDF_BUILD_ROOT` to an absolute, existing directory to override it, for
example to keep scratch off a slow volume. Startup prints its full stage-log
directory as `[up] Full stage logs: …/demo-logs/up-<timestamp>-<pid>/`.

Why the build root needs care at all: `dpm` resolves the nearest ancestor
`multi-package.yaml` from both the package path and the working directory, and
inside Concordia that is the repository root, which does not list the BabyCDF
packages or the CIPS credentials export. Each build therefore gets its own
`mktemp -d` directory inside the build root, holding a generated
`multi-package.yaml` that names exactly the packages being built, and runs from
that directory with `--multi-package-path` pointing at it. Two consequences
worth knowing: a `multi-package.yaml` you leave in the build root is never read
or overwritten, and two preparations running at once cannot disturb each
other's manifest. The root manifest is never modified and `dpm build --all` is
unaffected.

A note on the flag: `dpm 1.0.22 --help` labels `--multi-package-path` as `FILE`,
but this build appends `/multi-package.yaml` to the value, so it must be given
the **directory**. Passing the file itself fails with
`.../multi-package.yaml/multi-package.yaml does not exist`. Canton is
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

## Attendance, quorum and majority

Two different thresholds decide every round, and the demo keeps them apart
because conflating them is how a governance claim becomes false.

| | Question it answers | Rule in force | Where it is applied |
| --- | --- | --- | --- |
| **Quorum** | Did enough of the electorate take part at all? | `Count.atLeastPresent 3` — at least 3 of the 7 present | checked first; if it fails the verdict is `Lapsed` and the votes are never weighed |
| **Majority** | Of those who did take part, what did they decide? | `Count.shareOfVotes 1 2` — one option holds at least half the weight cast | applied only to the votes actually cast |

The governance rule itself is unchanged by this demo:
`Count.decide (Count.atLeastPresent 3) (Count.shareOfVotes 1 2)` in
`impl/daml/Cap/Examples/BabyCdf/RuleVote.daml`. The demo illustrates that rule;
it does not edit it, and the narration guard asserts the rule text is still
exactly that.

**The positive story: 3 core Yes, 4 absent.** Steps 2, 3 and 4 each issue all
seven ballots, have exactly three permanent members cast Yes, and leave the
other four ballots uncast. So:

- quorum is met **exactly**, 3 of 7, with no margin to spare;
- the majority is **3 of 3 votes cast**, since nobody voted No or abstained;
- the four absences are real on-ledger records: an `AgendaBallot` created by
  `InviteBallots` carries `vote = Absent`, and an untouched ballot stays that
  way, so "four absent" is a ledger fact rather than a narrator's claim.

**What this does not mean.** Three Yes votes are not unconditionally decisive.
They decided these rounds *because nobody voted against*. Had the absent
members voted No instead of staying away, the same three Yes votes would have
been a minority of seven and the verdict would have differed. The narration says
this explicitly rather than leaving the audience to infer a rule that does not
exist. The only scenario demonstrated is 3 Yes + 4 Absent.

**Who the three are, and why not "the first three ballots".** `InviteBallots`
creates one ballot per member of the electorate in party-ID order, and an
alternate's party ID can sort before a permanent member's. Taking the first
three ballots would therefore have selected alternates while the narration said
"three core members". The voters are instead selected from the governor
contract's own `permanent` set through one shared pure helper,
`coreQuorum` in `demo/daml/Cap/Examples/BabyCdf/LiveVote.daml`, which sorts and
dedups so the choice is deterministic across runs. The permanent membership is
read from the ledger at bootstrap and carried on the signed `DemoAnchor`, so
every later step selects from the real membership.

**Credentials are projected onto the participants.** `ResolveAgenda` requires
exactly one presented credential per *participating* eligible voter. Presenting
all seven in a three-voter round is itself a refusal, and so is omitting a
participant's. The demo derives the presented set from the voter list with
`presentedRights`, and the offline test asserts both refusals.

### Offline assertions behind these claims

Every claim above is asserted in `capRuleAndSeparateApprovalsWithMilestone`
(`demo/daml/Cap/Examples/BabyCdf/RuleVoteDemo.daml`), which runs under
`dpm test` with no network, container or wall-clock dependency. The Daml
`===` operator is assert-equals, and `submitMustFail` requires the submission
to be rejected.

| Claim | Assertion in the script |
| --- | --- |
| Three voters are selected, from the **permanent** membership | `length three === 3`; `unique three === True`; `forA_ three $ \v -> assert (v `elem` permanentList)` |
| **No alternate** can be selected | `forA_ three $ \v -> assert (not (v `elem` alternateList))` |
| Selection is deterministic and the no-quorum round is the same round one voter lighter | `two === take 2 three`; `length two === 2` |
| Selection never invents a voter, and a non-positive count selects nobody | `coreQuorum 9 permanentList === sort permanentList`; `coreQuorum 0 permanentList === []` |
| **All seven** vote records exist, one per member of the electorate | `length quorateBallots === 7`; `Set.fromList (map ((.voter) . snd) loadedQuorate) === Set.fromList (permanentList ++ alternateList)` |
| **Exactly four Absent**, as real on-ledger records | `length absentVotes === 4`; `forA_ absentVotes (=== Count.Absent)` |
| **Exactly three** credentials are presented | `length (presentedFor three) === 3` (and `length (presentedFor two) === 2`) |
| Presenting **all seven** credentials in a three-voter round is **rejected** | `submitMustFail opener $ exerciseCmd governor ResolveAgenda with … rights = allCredentials` |
| Presenting **two** (omitting a participant) is **rejected** | `submitMustFail opener $ exerciseCmd governor ResolveAgenda with … rights = presentedFor two` |
| **Positive:** three core Yes → `Decided Yes`, one execution | `quorateVerdict === Count.Decided Yes`; `length quorateExecutions === 1` |
| **Negative:** two core Yes + five Absent → `Lapsed`, no executions | `shortVerdict === Count.Lapsed`; `shortExecutions === []` |
| The impl `Count` rule is **unchanged** | asserted by the narration guard, not the script: `Count.decide (Count.atLeastPresent 3) (Count.shareOfVotes 1 2)` must still appear verbatim in `impl/daml/Cap/Examples/BabyCdf/RuleVote.daml` |

The live journey uses the same selector and projection (`coreQuorum`,
`presentedRights`) and additionally asserts, per step,
`length voters == quorumSize` and `length rights == quorumSize`, so a
mis-specified voter aborts the step rather than quietly producing a different
turnout from the one the narration claims.

## The journey

### Step 1, `up`: synthetic setup

Issues seven unit voting credentials, five direct and two registered, creates
the `AgendaGovernor`, and activates `EffectiveRule` v1 as an **opener-created
sandbox seed: v1 is unvoted, created directly by the opener, and is not a voted
or Foundation rule**. The first voted rule in the journey is v2, in step 2. The dedicated `babycdf-agent`
reader user is created with exactly `[CanReadAs <opener>]`, verified by exact
equality; it is granted no `CanActAs` or member read right. A separate
`babycdf-agent-intake` user has exactly `[CanActAs <opener>]` (which includes
read access). These are provisioned rights, not an enforced caller boundary on
the unauthenticated demo endpoint. Reader nonsubmission comes from state-query-only
wiring. Intake provisioning and `readback-intake` do not implement Intake submission. A signed `DemoAnchor`
carries the run state; every later step finds it by a primitive run ID and
refuses unless it is at the expected step. The host scripts submit as the
synthetic opener and voters, never through the agent user.

The package gate before each step checks only that **local DAR bytes are
unchanged since bootstrap**. It is not a participant package inventory.

### Step 2: one rule-change round

Opens one `baseVersion` 1 agenda, issues all seven ballots, casts exactly
3 core Yes votes and leaves 4 Absent. After the 60-second window plus a
3-second margin it resolves and executes v2. The round is quorate at exactly
3 of 7 and carries by 3 of 3 votes cast. No stale round is interleaved here
or included in `all`.

### Optional round 1: approved vote, refused execution

After successful step 2 (or a later compatible main step), run:

```sh
./run-demo.sh opt-step 1
```

This separately proposes a fresh rule change with `baseVersion` 1 against the
live v2 registry. It issues all 7 ballot records, casts 3 core YES, leaves
4 ABSENT and presents exactly 3 credentials. The vote resolves APPROVED;
**execution**, not approval, is refused because the agenda's v1 snapshot does
not match the live registry. This is not a replay of a consumed contract ID.
The script asserts rejection and unchanged registry version; inspect the raw
participant error for the precise cause.

Presentation order is INITIAL STATE → PROPOSAL → VOTING → RESOLUTION →
EXPECTED REJECTION → FINAL STATE. The round does not advance or reset the
main anchor; main step 3 can still follow it. Its own `opt-step-1.attempted`
and `opt-step-1.json` markers enforce once-per-network execution and its raw
log is `opt-step-1.log`. Before step 2, on duplicate attempts, or for unsupported
optional numbers, the runner refuses with exit 2. After a partial failure,
the same down/up reset guidance applies; do not reset a running demonstration
without deciding to restart it. Up/down cleanup includes the optional markers.

### State blocks and sequential presentation

All six main steps open with INITIAL STATE and close with FINAL STATE.
Steps 2–6 query actual contracts/interfaces visible to the stated demo
witnesses, rather than a participant-wide inventory or hard-coded counts.
Counts describe active state; archived history is separate (`./ledger.sh archived`).
A zero count is neutral and does not by itself prove consumption or absence
outside the witness scope. Step 1 has no demo party yet: its initial block
lists participant parties/users only, explicitly not a contract query.
Main voting rounds run sequentially, not interleaved; setup, allocation and
blocked-case steps retain their distinct business purposes.

### Step 3: proposal review and vote

Votes on the compile-time pinned proposal subject and logs the pinned blob SHA
`f591787a772848e4537aba5d8ba02be1764647da`. The agents verify that digest
separately. Three core Yes votes, with four members absent, produce an
`ApprovedGrantProposal`.

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

### Independent optional security rounds (formerly step 6)

The main journey ends at step 5; `all` runs only steps 2-5. `step 6` and the
legacy `blockedPaths` entry point refuse without ledger mutation. After `up`,
run `./run-demo.sh opt-step 2`, `3`, `4`, or `5`, alone or in any order.
Each needs only bootstrap, owns fresh round IDs, ballots, `opt-step-N` markers
and raw logs, returns the run ID, and never advances or resets the main anchor.
Optional 1 is unchanged and still needs step 2 (active v2).
Each optional runs once per network; an attempted round requires the usual
explicit down/up reset before retry. No cleanup was run to validate this change.

Each refusal is a real submission whose participant error is printed.

2. **Optional 2: invalid and foreign credentials.** Both attempts resolve the same closed
   proposal, and the step first asserts the window has actually closed, so
   neither case can pass merely because voting was still open.
   - The wrong-consortium credential is issued by this demo's issuer, so the
     opener can fetch it and it reaches the credential check itself. The
     refusal text `Contract group identifier mismatch` **is asserted**, because
     the credential is fetched through a `RightKey` carrying the consortium.
   - The foreign-issuer credential is not visible to this opener, so its
     refusal is a contract lookup failure rather than the credential check.
     The script asserts invisibility to the opener and that the error is
     *not* `voting still open`. Issuer-authority enforcement is **not proven**.
3. **Optional 3: no quorum.** Exactly two core members cast Yes and five are absent, one
   short of `atLeastPresent 3`. This is deliberately the *same* round as the
   approved ones, one vote lighter: the voters are permanent members selected by
   the same `coreQuorum` helper, their credentials are genuine and valid, and
   both vote Yes. Only the attendance differs. This path does not error: the
   engine returns a verdict. The code therefore **asserts `Lapsed` with no
   executions**, which is the strongest available check. Put beside steps 2-4 it
   is the clearest statement of the rule the demo can make — three present and
   voting Yes decides, two present and voting Yes decides nothing — because
   attendance is checked before the votes are ever weighed.
4. **Optional 4: double vote.** The consumed ballot cannot be exercised again, and the
   already-cast ballot refuses a second vote with the stable message
   `already cast`, which **is asserted**.
5. **Optional 5: outsider.** Two attempts, and what each one proves is stated exactly.
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

Optional 5 additionally submits with **outsider actAs and opener readAs**:
visibility is supplied without making the opener the controller. It asserts
`not opener or eligible member`, proving the proposer authorization branch.
Optional 2 queries the foreign credential as invisible to the opener; this is
lookup/privacy evidence, **not proof of foreign-issuer authority rejection**.
The isolated Daml regressions run each fixture alone and in shuffled order,
asserting the same anchor contract ID and payload after every fixture.

## Step output: phases and ledger evidence

Each step prints ASCII-banner phases that map onto the deck slides, so the
terminal can be followed from the back of a room:

```text
################ STEP 2: RULE GOVERNANCE ################

########## PROPOSAL ##########
Rule architecture-review: v1 -> v2.
v2 requires: composes-with-asset-layer + execution hooks + auditable state and acceptance criteria.

########## VOTING ##########
member-a: YES (core)
alternate-f: ABSENT (no cast; not an abstention, not a NO)

########## LEDGER EVIDENCE ##########
After casting, both rounds [active now; visible to opener+member; dedup by CID. History: ./ledger.sh archived | history]
VoteTopic active: 2; Resolver active: 1
VoteRecord active: 14 (6 YES, 0 NO, 8 ABSENT)
VotingRights active: 7
Action active: 2; Executable active: 0

########## RESOLUTION ##########
APPROVED - 3 YES, 0 NO, 4 ABSENT. Quorum 3/3; majority 3/3.
```

Every main step begins with `INITIAL STATE` and ends with `FINAL STATE`.
Ledger evidence is interspersed at the relevant operation boundaries.

| Step | Banner | Phases between state blocks |
| --- | --- | --- |
| 1 | `SETUP AND CREDENTIALS` | sandbox notice, committee, credentials, rule book, agent rights, ledger evidence |
| 2 | `RULE GOVERNANCE` | proposal, voting, resolution, execution — one round |
| 3 | `PROPOSAL REVIEW AND VOTE` | proposal, voting, resolution, execution |
| 4 | `MILESTONE REVIEW AND VOTE` | subject, advisory review, safety check: unpinned subject, proposal, voting, resolution, execution |
| 5 | `COUPON ALLOCATION` | setup, safety check: wrong manager and over-amount, allocation |
| 6 | `BLOCKED CASES` | what this step does, case 1-4 |
| opt-step 1 | `OPTIONAL ROUND 1: STALE RULE CHANGE` | proposal, voting, resolution, expected rejection |

The stale execution check lives only in `./run-demo.sh opt-step 1`, after
step 2 completed with rule v2 active. It is excluded from `all`.

### Offline guard and source-rendered transcripts

`sh test-narration.sh` includes `check-workflow.py`: INITIAL STATE must precede
mutations, state helpers must retain their ledger queries, optional execution
must not advance the anchor, and `all` must exclude optional rounds. With
`BABYCDF_BUILD_ROOT` set to an existing approved temporary directory,
`python3 -B test-workflow-tamper.py` runs the real guard against an unchanged
baseline and seven isolated mutations, including a second step-2 agenda,
missing/late INITIAL STATE, optional-in-all, anchor advancement, hard-coded
counts and a weakened Count rule. It never mutates the running demo.

`python3 -B render-source-transcript.py daml/Cap/Examples/BabyCdf/Live.daml OUT`
regenerates six main-step renderings and optional round 1 from final source.
These are **rendered offline source, not a live run or live counts**: dynamic
expressions remain expressions and branches are not executed. They are a
presentation/source audit aid, not substitutes for pending live acceptance.

### Ledger evidence is queried, not claimed

`LEDGER EVIDENCE` blocks appear before and after each major phase, and every
number in them is read from the ledger at that moment through Daml Script
queries — `queryInterface` for the governance interfaces that the templates
actually implement, and `query` for templates that implement none:

| Counted via | Contracts |
| --- | --- |
| `queryInterface` | `VoteTopic` (AgendaProposal), `VoteRecord` (AgendaBallot), `VotingRights` (Direct/RegisteredVotingRight), `Resolver` (AgendaGovernor), `Action` (AgendaAction), `Executable` (AgendaExecution) |
| `query` | `EffectiveRule`, `ApprovedGrantProposal`, `ApprovedMilestone`, `DemoAnchor`, `RuleRegistry`, `UnclaimedDevelopmentFundCoupon`, `DevelopmentFundCoupon`, `AmuletRules`, `OpenMiningRound` |

The YES/NO/ABSENT breakdown is decoded from each `VoteRecord`'s own on-ledger
interface view, so it reports what the ledger holds rather than what the script
intended to cast. The narration guard enforces that these counts come from
queries: a hard-coded number, a removed deduplication or a missing scope line
fails it.

**Scope, and what a count cannot prove.** Every block states its scope: counts
are **active now**, visible to the named witness parties, deduplicated by
contract ID because the same contract is visible to more than one party. Active
counts are deliberately **not** presented as history. A cast ballot replaces its
uncast predecessor and resolution consumes the records, so `VoteRecord active`
legitimately returns to 0 after a resolve — which is why the line says
`(consumed when counted)` rather than implying the votes never happened. The
transitions are what carry the meaning:

| Moment | `VoteRecord active` | Line printed |
| --- | --- | --- |
| before ballots are issued | 0 | `(no active records visible in this scope)` |
| after `InviteBallots` | 7, all ABSENT | `(0 YES, 0 NO, 7 ABSENT)` |
| after casting | 7, with the split | `(3 YES, 0 NO, 4 ABSENT)` |
| after `ResolveAgenda` | 0 | neutral line, **plus** a separate sentence attributing it to the resolve just observed |

**A zero is never explained by inference.** The first and last rows are both
`0`, and a count cannot say which case it is in. The neutral wording is
therefore used for every zero, and only `resolutionEvidence` — reached after a
resolve has actually been observed on records it had already counted — adds the
consumption sentence, which cites the surrounding snapshots and
`./ledger.sh archived` rather than reasoning from the number.

**Snapshot ordering is a real sequence, not a label.** The
"after ballots issued, before any cast" snapshot is only true if issuance has
already run, so issuance is its own statement (`inviteBallotsFor`) sequenced
before the snapshot; in step 2 both rounds are issued first, so the snapshot
covers both electorates. An earlier revision took that snapshot before
`InviteBallots` executed. Asserting the label or the source order would not
have caught it, so the Daml suite instead **queries the ledger at each stage**
— before issuance, after each round's issuance, after each round's casting,
after each resolve — and checks the counts the snapshot would report
(7 then 14 records, 0 YES, all ABSENT; then 3 and 6 YES; then back to 0).
Reversing that order fails the suite with `expected 0 == 7`.

For the archived side, the evidence lines name the corroborating commands
directly: `./ledger.sh archived` pairs each archived contract with its
creation, and `./ledger.sh history` shows the update stream. Those read the
ledger's own transaction history, which an ACS query cannot.

In step 6 the before/after blocks are the proof that a refusal changed nothing:
`ApprovedGrantProposal` and `ApprovedMilestone` counts are unchanged across all
four cases, and the quorum case shows `Lapsed` with `Executable active: 0`.

### Readable output, and the full technical log

`run-demo.sh` pipes each step through `demo/format-log.py`. The SDK wraps every
`debug` payload in a source location and an escaped string literal, which is
what made the raw terminal unreadable:

```text
[DA.Internal.Prelude:557]: \"[STEP 2] proposing rule v2; host submits as ...\"
```

The filter unwraps that to the payload alone. It is a parser, not an evaluator:
an explicit string scanner plus a fixed escape table, with no general decoder
and no interpreter applied to demo output, and a unit test asserts that none of
those constructs appear in the file. Anything it does not recognise is passed
through unchanged, and **every non-debug line — real errors, stack traces,
runner output — is always printed**.

Tags are matched as an **anchored** leading `[STEP n][TAG]`, never by substring
search, so a presentation line whose own words mention `[ACTION]` or `[TIMING]`
is neither hidden nor stripped, and an unrecognised tag is preserved with its
text rather than swallowed.

Three guarantees that are tested rather than assumed:

- **The raw log is a precondition, not a side effect.** The directory is
  created and the log file is genuinely **opened for append before the
  mutating command is launched**. If that fails — a file in place of the
  directory, an unwritable directory, a directory where the log should be, no
  directory configured — the step is **refused and the command is never
  invoked**, because running a ledger mutation whose output cannot be recorded
  is worse than not running it. `test-render-step.sh` proves the command did
  not run by checking a marker file in each of those four cases.
- **dpm's exit code is preserved.** POSIX `sh` has no `pipefail`, so a naive
  pipe would report the last stage's status and a failed step would look
  successful. The status is carried out of the pipeline through a status file
  and re-raised; cleanup discards its own result so it cannot mask the
  command's. Asserted for exit code 7, with and without the formatter.
- **Nothing is only summarised, formatter or not.** Every original line is
  appended verbatim to a per-step raw log under the gitignored build root, and
  its path is printed (`Full technical log: …/.build/demo-logs/step-2.log`).
  The raw log keeps the SDK prefix and the `[ACTION]`/`[TIMING]` lines the
  terminal withholds. With no `python3` or no formatter the step still runs,
  still records the complete stream (via `tee`), still prints the path, still
  shows the output unformatted, and still propagates the exit code.

`up` is quiet by default: passing readback/entrypoint tests, pinned CIPS export,
checksum/dependency checks, DAR builds, package inspection, Canton readiness,
readback publication and agent start each print one concise status line. All
checks still run. Full output is captured before each stage starts under the
printed `$BABYCDF_BUILD_ROOT/demo-logs/up-<timestamp>-<pid>/` directory (the
unset build root defaults to `.build`). An unusable log refuses the command.
On failure, startup stops with the original exit code, stage name, last 30
log lines and log path; it never reports that failed stage as successful.

`VERBOSE=1 ./run-demo.sh up` shows each stage's full captured output as well.
Step 1 remains visible through the same renderer as the main steps, including
INITIAL/FINAL STATE, ACTION/BUSINESS and ledger evidence; only known SDK/JVM
INFO lines are hidden in default startup output and remain in its raw log.
Warnings, errors and unknown output remain visible. No startup safety check
is skipped by quiet mode. `python3 -B test-up-stage.py`, with an existing
approved `BABYCDF_BUILD_ROOT`, tests this entirely with stubs, not Docker.

For main steps, by default the terminal shows the presentation lines and hides
`[ACTION]` and `[TIMING]`. `DEMO_VERBOSE=1` shows them too:

```sh
DEMO_VERBOSE=1 sh examples/governance/babycdf/demo/run-demo.sh step 2
```

Output streams: headings appear as the actions run, not after the step ends,
which `test-render-step.sh` also checks.

## Per-action logs

Every individual submission still prints one greppable line. These are in the
raw log always, and on the terminal with `DEMO_VERBOSE=1`:

```text
[STEP 3][ACTION] member-a (party member-a-9b3970be::1…) exercises AgendaBallot.CastAgendaVote on 00ab12… → Voted Yes → new ballot 00cd34…
[STEP 3][ACTION] no cast for ballot 00ef56… held by alternate-f (party alternate-f-…) → Absent (ballot left uncast)
[STEP 3][ACTION] opener exercises AgendaGovernor.ResolveAgenda (7 ballots, 3 credentials) → Decided Yes; execution 0012…
[STEP 6][ACTION][REJECTED] babycdf-non-member (party …) exercises AgendaBallot.CastAgendaVote on live ballot 0070… → participant error: … (BLOCKED as expected)
```

Note the resolve line: **7 ballots, 3 credentials**. All seven ballots are
presented so the count sees the exact electorate including the absences, while
credentials are presented only for the three members who took part. Members who
stay away get an explicit `Absent (ballot left uncast)` action line, so absence
is visible in the log rather than being an unexplained gap.

Each line names the acting party with a readable display name and a short party
ID, the template and choice (or `create`), the key arguments, and the result:
created contract IDs, shortened, or the decoded outcome. Expected failures are
tagged `[REJECTED]` and carry the participant error.

To show only the ledger actions, read them from the step's raw log, which holds
the complete unfiltered stream:

```sh
grep ACTION examples/governance/babycdf/.build/demo-logs/step-3.log
```

Or keep them on the terminal while the step runs:

```sh
DEMO_VERBOSE=1 sh examples/governance/babycdf/demo/run-demo.sh step 3 2>&1 | grep ACTION
```

Covered: party allocation, user and rights creation, credential invitations and
acceptances, the registry and rule seed, proposals, invite-ballot calls, every
cast, resolves, executions, anchor creation and advances, the Splice round
bootstrap and advance, issuance, the coupon allocation, and every blocked
attempt. The anchor lookup is a query, so it is one line rather than several.

## Inspecting the ledger: `ledger.sh`

`demo/ledger.sh` is a read-only viewer for the running network. It runs from any
working directory, reads the party and contract IDs from `compose/state/`, and
fetches the current ledger end itself. It never creates, exercises, submits or
uploads anything, and never starts or stops a container.

```sh
./ledger.sh [http|grpc] <command> [args]
```

Two transports serve the same commands. `http` is the default:

| Transport | How it reaches the participant |
| --- | --- |
| `http` | HTTP JSON Ledger API v2 on container port 7575, via `docker compose exec`. The port is deliberately not published, so curl runs inside the container. |
| `grpc` | gRPC Ledger API v2 on `127.0.0.1:${LEDGER_PORT:-41000}`, via a short-lived `docker run --rm` grpcurl client, pinned to the immutable digest `fullstorydev/grpcurl@sha256:085e183c…` (`grpcurl`, `linux/arm64`, verified 2026-11-18; override with `GRPCURL_IMAGE`). Nothing is installed on the host; the participant has gRPC server reflection enabled, so no `.proto` files are vendored. |

The gRPC transport is verified on Docker Desktop for macOS, reaching the
published loopback port through `host.docker.internal`. Setting
`BABYCDF_GRPC_VIA=container` instead joins the participant's own network
namespace and dials port 6865 directly, which needs no published port and is
the portable choice on Linux; both paths are tested.

| Command | Shows |
| --- | --- |
| `status` | ledger end, API version, synchronizer, current demo step |
| `parties` | parties and users on the participant |
| `packages` | the babycdf and `splice-amulet` packages: name, version, ID |
| `contracts [who]` | active contracts for `opener` (default), `member`, or a party ID |
| `archived [who]` | archived contracts, paired with their creation offset |
| `rule` | the active `EffectiveRule`: version, design text, clauses |
| `votes` | proposal, ballot and approval counts, plus approval payloads |
| `credentials` | direct and registered voting rights, per member |
| `coupons` | `DevelopmentFundCoupon` counts |
| `history [cid]` | the opener's update log, or one contract's create/archive events |
| `raw <path> [body]` | query-only escape hatch: `raw /v2/version`, or `grpc raw StateService/GetLedgerEnd` |

Read-only is enforced, not merely intended. `raw` accepts only allowlisted
query operations — the state, update, event-query, party, user, package and
version lookups — and refuses anything else, including submission, allocation,
DAR upload, user and rights mutation, interactive submission and pruning, with
exit 2 **before any network call is made**. Transport errors are checked too: a
non-zero curl or grpcurl exit, an HTTP status of 400 or above, or a malformed
body fails loudly instead of being reported as an empty result.

The update stream is paged. The HTTP endpoint applies `take(limit)` and the
server imposes its own cap, so a single request silently truncates; `history`
and `archived` therefore loop on `beginExclusive` up to the ledger end captured
at the start, requiring progress on each page. `BABYCDF_PAGE_LIMIT` sets the
page size, which changes only the number of round trips, never the result.

```sh
./ledger.sh status            # http, the default
./ledger.sh grpc rule
./ledger.sh grpc archived member
```

`demo/test-ledger.sh` covers the viewer offline, with a stub `docker` on `PATH`:
the allowlist (including proof that refused operations never reach Docker),
paging completeness past the first page, gRPC stream collection, every
transport failure mode, and the exit codes. It never contacts the live ledger:

```sh
sh examples/governance/babycdf/demo/test-ledger.sh
```

Both the agents and `ledger.sh` are read-only observers; only `run-demo.sh`
submits to the ledger. Reaching the JSON API from the host without
`docker compose exec` would mean publishing 7575 on loopback in `compose.yaml`;
that is deliberately **not** done, and `ledger.sh` does not need it.

## Offline checks

All of these run without a network, a container or a ledger, so they are safe
to run while a demo is live:

```sh
sh examples/governance/babycdf/demo/test-narration.sh      # narration/presentation guard
python3 -B examples/governance/babycdf/demo/test-format-log.py   # 35 formatter cases
sh examples/governance/babycdf/demo/test-render-step.sh    # 34 runner cases
sh examples/governance/babycdf/demo/test-ledger.sh         # 106 viewer cases
python3 -B examples/governance/babycdf/demo/test_readback.py
sh examples/governance/babycdf/demo/compose/agents/test-entrypoint.sh
sh examples/governance/babycdf/test-build-root.sh          # 24 cases
cd examples/governance/babycdf/java && mvn test            # 87 cases
```

### The narration guard, and what it proves

`demo/test-narration.sh` guards the narration in `Live.daml`. Four checks:

1. each of the **eight** narration and presentation helpers — `business`,
   `voteNarration`, `decision`, `outcomeLine`, `expectedRefusal`,
   `stepHeading`, `phase` and `evidenceLine` — matches its exact log-only
   definition, so none can acquire a second effect;
2. `roundName` is a pure `Text -> Text` function with no ledger vocabulary;
3. every narration call is a standalone statement, never consumed as a value;
4. the **spoken counts, the structure and the evidence provenance** all hold:
   `quorumSize = 3` with `absentSize`/`belowQuorumSize` derived from it, voters
   selected via `coreQuorum` from `state.core`, credentials projected with
   `presentedRights`, the rule still
   `Count.decide (atLeastPresent 3) (shareOfVotes 1 2)`, no vote count spelled
   as a word, every step carrying a `stepHeading` banner and a `RESULT` phase,
   headings ASCII-only, and the evidence blocks genuinely query-derived —
   `queryInterface` present for each counted interface, votes decoded from
   on-ledger records, counts deduplicated, and the scope and history commands
   stated.

**It is pinned to a reviewed spec, not to "nothing changed".** The guard used
to assert that the executable logic was byte-identical to `HEAD`'s, which was
the right check while the narration was being added and the wrong one now:
this change moves governance behaviour deliberately, so that assertion would
either fail forever or, worse, be relaxed into blessing any change at all.
Instead the executable logic is digested and compared to `SPEC_DIGEST`, which
names a reviewed behaviour via `SPEC_VERSION` — currently
`quorum-of-three-core-v2-presentation`. Both are updated together, in the same
change that moves behaviour.

The `-v2-presentation` spec added the phase output and evidence blocks on top
of `-v1` **without changing governance behaviour**: an operation-level
extraction compared every `exerciseCmd`, `createCmd`, assertion, verdict
comparison, quorum constant, `coreQuorum` selection and `presentedRights`
projection against the `-v1` baseline, and the only difference was three
`Count` constructors inside the read-only evidence tally.

The current `-v3-ordered-evidence` spec is different in kind, and the spec
comment says so: it is a **deliberate reorder**. Ballot issuance became its own
statement sequenced before the "before any cast" snapshot, so the *set* of
governance operations is unchanged but their order relative to the read-only
snapshots is not. **No "operations unchanged" claim is made for that step** —
the ordering is instead proven by the per-stage ledger queries described above.

So the guard proves *"the logic is the reviewed logic for this named spec, and
the narration is still log-only and numerically honest"*. It explicitly does
**not** prove "behaviour never changed"; the script prints that scope statement
on success so a passing run cannot be quoted as more than it is. Passing a git
revision still prints the executable-logic diff against it, but as labelled
information rather than a verdict:

```sh
sh examples/governance/babycdf/demo/test-narration.sh 5fd0c9d8
BABYCDF_PRINT_LOGIC_DIGEST=1 sh examples/governance/babycdf/demo/test-narration.sh
```

Verified by tampering: changing `quorumSize` to 4 fails checks 3 and 4; giving
a narration or heading helper a second statement fails check 1; weakening
`atLeastPresent 3` in the impl package fails check 4; removing the credential
projection fails checks 3 and 4; hard-coding an evidence count, removing the
count deduplication, dropping a step's banner, or putting a non-ASCII character
in a heading all fail too.

## What the agents log

The Reviewer containers run throughout, non-root, read-only, with
bounded resources, sharing Canton's network namespace.

### Presentation-first logs

In the containers the agents log for a **room**, not for a parser. Each prints a
startup banner, then one `Evaluating …` / `Result: …` pair whenever a conclusion
actually changes, and nothing at all while it has not:

```text
[Reviewer] Starting. Recommendations only; no votes or publication.
[Reviewer] Evaluating proposal PR #184 using rule v1.
[Reviewer] Result: REJECT — missing “independent security audit”.
[Reviewer] Re-evaluating proposal PR #184 using rule v2.
[Reviewer] Result: APPROVE — all requirements met.
[Reviewer] Evaluating the milestone vote (milestone #540).
[Reviewer] Result: BLOCKED — no verified evidence about this subject to review.
[Advisory] Proposal PR #184 loaded; pinned evidence verified.
```

What is suppressed is **volume, never a check**. Every verdict printed is the
one the agent's own clause evaluation produced against the rule it read from the
ledger; the digest verification, authority checks, read-only rights and the
absent publication path are all untouched. Specifically:

- **Deduplication is by meaning.** A line reprints when the subject, rule
  identity and version, evidence pin, verdict or reason changes, and otherwise
  stays silent, so a ten-second poll no longer repeats an unchanged answer. A
  genuine change always prints, and a repeat evaluation says
  `Re-evaluating`. An agenda's identity is its **immutable contract ID** plus
  its round ID and the subject URL and body it puts to the vote — not the round
  ID alone, which is a caller-chosen label that two distinct `AgendaProposal`
  contracts could share. Unit tests cover exactly that case: two different
  agenda contracts carrying the same round ID are both reported, the same
  contract is not reported twice, and a changed subject or re-pinned evidence
  reprints. Deduplication affects only which lines repeat; it is not a security
  boundary and influences no verdict.
- **No per-cycle noise.** `reviews=1`, `errors=1`, `open agendas=0`, clause
  dumps, contract IDs, blob hashes and the JSON document are all withheld by
  default.
- **Errors are never hidden.** A failure prints once as one short sentence with
  no stack trace (`Waiting: BLOCKED: local sandbox ACS read failed: …`),
  identical repeats are silent, a *different* failure prints, and recovery is
  announced once (`Ledger readable again; resuming evaluation.`). The Advisory
  seed's missing milestone issue is summarised as a single `Note:` line instead
  of a per-cycle error list.
- **Health is tracked per category, and recovery means recovery.** A cycle can
  be healthy in one respect and degraded in another: the Reviewer can read the
  rule while agenda discovery fails, and it can read both while the evidence
  behind every review is unverifiable. The categories — cycle, agenda
  discovery, evidence verification, and the routine evidence note — are
  therefore tracked separately, each cycle's health is assessed in full
  *before* any recovery is announced, and a category is cleared only when that
  category is genuinely clear. A recovery line consequently always means
  something actually recovered. Unit tests drive several identical degraded
  polls through the production cycle and require exactly one failure line, no
  intervening recovery, no repeated failure, and exactly one recovery when the
  fault clears.
- **A verdict is withdrawn when its evidence stops being verifiable.**
  `Advisory.collect` does not throw on a blob digest mismatch, an unverified PR
  identity or an unverified blob response: it records the reason and omits the
  input. The ledger read still succeeds, so a cycle can legitimately produce
  errors and no reviews — and since the default demo mode suppresses the JSON
  and activity lines that would carry those errors, that case used to print
  **nothing at all**, leaving an earlier `APPROVE` on screen as though it still
  applied. Silence is not neutral, so now the standing verdict is explicitly
  retracted and the failure is stated:

  ```text
  [Reviewer] Previous finding for proposal PR #184 is no longer current: its evidence could not be verified.
  [Reviewer] Result: BLOCKED — evidence verification failed: proposal blob digest mismatch; …
  ```

  A verification failure is never downgraded to the routine `Note:` wording,
  classification fails closed so an unrecognised error counts as a verification
  failure, and when evidence becomes valid again the recomputed result is
  printed rather than suppressed as "unchanged" against the verdict just
  withdrawn.
- **Failure wording does not overclaim provenance.** The review step acquires
  both the ledger rule and the proposal evidence, and a thrown failure does not
  reliably say which stage failed. That category is therefore reported
  neutrally (`Waiting: …` / `Cycle completed again; resuming evaluation.`)
  rather than asserting that the ledger specifically failed or recovered. The
  one shortfall that genuinely is about the ledger rule — no verified active
  rule — still says so.
- **Continuous evaluation and agenda advice stay distinct.** The Reviewer
  evaluates the pinned proposal against the active rule on every cycle whether
  or not anything is being voted on — that is what prints `REJECT` under v1
  before step 2 opens any agenda. Advice about a specific round is separate,
  only exists while that round is open, and carries the
  `advisory only, members decide` label.
- **No false approvals.** A result line is only printed by a path that has
  already evaluated the subject. An agenda with no evidence about its own
  subject reads `BLOCKED — no verified evidence about this subject to review`,
  which is deliberately not the vocabulary of rejection, and the illustrative
  milestone is never described as verified.

### Getting the machine-readable output back

The original CLI contract is intact. The agents default to their previous
behaviour when invoked directly; presentation mode is opt-in via `--demo`, which
is what the container entrypoint passes. Either `--verbose` or `--json`, or
`DEMO_AGENT_VERBOSE=1` in the environment, restores the full detail *in addition
to* the concise lines:

```sh
DEMO_AGENT_VERBOSE=1 sh examples/governance/babycdf/demo/run-demo.sh up
```

Measured on the real pinned blob: one Advisory poll prints 3 concise lines in
demo mode against 2186 bytes of activity line plus JSON by default.

### Rule authority and evidence

The Reviewer's rule authority always comes from the live ledger, so it reports
rule `v1` during step 1 and `v2` from step 2 onward; with `--verbose` the
`active EffectiveRule v<n> cid=…` line and the contract ID return. Its evidence
is offline and pinned, labelled
`offline_pinned_pr184_seed_not_fresh_github_poll`; the single error is the
missing milestone issue in that PR-only seed, not a failed network call.
The demo Advisory entrypoint is read-only: it uses `--read-only-sandbox` for
opener-filtered state queries and rejects nonempty, nonzero `ADVISORY_SUBMIT`.
The pre-existing standalone `--local-sandbox` submission CLI is separate and
unreachable from this demo entrypoint; there is no demo submission opt-in.
There is no `GITHUB_TOKEN` in the default agent environment and no authority fallback.

### The agent really validates the proposal text

The Reviewer evaluates the **actual pinned PR #184 text** against the clauses of
the **active ledger `EffectiveRule`**. The criterion is ledger data, not a
constant in the agent: each clause carries a human-readable `criterion`, a list
of `requiredPhrases` that must all appear, and `forbiddenPhrases` that must not.
The only constants in code are the matching mechanics.

**This is deterministic literal text matching, not comprehension.** A phrase
counts as present when it appears after case folding and whitespace
normalization, so a phrase wrapped across a markdown line break still matches.
These authoritative findings do not claim semantic understanding. Optional Java
LLM assessments interpret each supplied rule clause against bounded evidence, but are not replacements for these checks. See [LLM.md](LLM.md) for file-secret provisioning, opt-in flags, and the command to recreate only Reviewer. Exact `PASS`/`FAIL`/`INCONCLUSIVE`/`NOT_APPLICABLE` verdicts have verified quotes and deterministic aggregation; optional commentary is separately labelled advisory and cannot affect the exact result. Reproducibility is per cached input, not mathematical determinism; container recreation loses the existing tmpfs cache. No live LLM acceptance is claimed by offline tests;

returned-model/log metadata identifies the actual provider response. Every finding cites the clause ID, the
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

#### Rule-reference migration

The ledger now records a rule-document location, optional commit and SHA-256.
Clause semantics live only in versioned JSON documents. ApplyRule records the
reference and subject, with no on-ledger findings. Java resolves and hash-verifies
the document before interpretation. Changing to another repository requires a
new voted immutable URL/commit/hash reference; see [LLM.md](LLM.md).

The following evaluator comparison describes the previous package, not the
rule-reference package:

#### Historical: two separate advisory evaluators

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

While an agenda is open, the Reviewer recommends once per agenda per rule
version. In demo mode that reads:

```text
[Reviewer] Evaluating the grant vote on proposal PR #184 using rule v2.
[Reviewer] Result: APPROVE — all requirements met; advisory only, members decide.
```

and with `--verbose` the original full-detail line returns alongside it:

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
reader user holds exactly `[CanReadAs <opener>]`, with no member read grant or
`CanActAs`. Bootstrap asserts that exact set by equality. The separate Intake
user holds exactly `[CanActAs <opener>]`; Intake submission is not delivered here.
On this unauthenticated endpoint, these rights describe provisioning, not an
enforced API authorization boundary. The demo readers do not submit because
their entrypoint uses state-query-only wiring, not because rights readback proves
that the endpoint would reject a command. In this demo the
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

> **This run is PRIOR-REVISION evidence. It does not validate the current
> behaviour.** It was recorded before the quorum-of-three change and the
> presentation-first agent logs, and it exercised the **prior** behaviour:
> **seven Yes votes per round**, resolves presenting **7 ballots and 7
> credentials**, and the **verbose** agent log format. Its timings, contract
> IDs, `[ACTION]` counts and log excerpts therefore describe that revision
> only, and must be re-measured before being quoted for this one.
>
> The current revision is **OFFLINE VALIDATED, LIVE ACCEPTANCE PENDING**: the
> Daml script suite, the Java unit tests, the narration and quorum guard, the
> shell and entrypoint suites and the captured agent transcripts all pass, and
> **no live `up`/`all`/`down` has been run for it**.
>
> Why live acceptance is still pending: this host is already running the demo
> (`babycdf-live`), and a second stack cannot be started safely alongside it on
> the current Docker allocation. Compose project **isolation itself is not the
> obstacle** — `docker compose -p <name>` overrides the project name in
> `compose.yaml`, so a separately named stack is possible. The obstacle is
> **memory**: the Docker VM reports 7.75 GiB total, the running stack already
> commits 5.00 GiB of limits (4.00 GiB Canton + 2 × 0.50 GiB agents), and a
> second stack would commit another 5.00 GiB, i.e. 10.00 GiB of limits against
> a 7.75 GiB VM, with roughly 2.4 GiB free on a 16 GiB host. Starting it would
> risk the running demo, so it was not attempted. Live acceptance needs either
> a larger Docker allocation or a window in which the current demo is down —
> **the running network was deliberately left untouched.**

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

**Prior-revision history only:** this run predates both the quorum change and
the sequential-round/optional-stale restructure. Its two-agenda counts and
stale-twin placement are not acceptance evidence for the current source.
Current live acceptance is **PENDING**; offline source renderings are not live counts.

| Step | Historical evidence from that prior revision (pre-quorum-change) |
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
16 in step 4, 15 in step 5 and 38 in step 6 — 136 in total. These counts are
stale for the current behaviour: absences now emit their own action lines while
casts are fewer, so the per-step totals have moved and have not been
re-measured live.

Contract IDs are from that run only; they are stale after `down` and are not
operational state. Negative guards verified on a fresh network: `step 5` and
`step 6` before their prerequisites both exit 2, an out-of-range step number
exits 2, and `all` refuses once any step has run or been attempted.

## Pitch mapping

The terminal banner for each step is the row to point at, so a presenter can
match the slide to the output without reading ahead.

| Slide | Step | Terminal banner | What it shows |
| --- | --- | --- | --- |
| Credentialed membership | 1 | `STEP 1: SETUP AND CREDENTIALS` | Direct and registered unit credentials, verified agent rights |
| Governed change | 2 | `STEP 2: RULE GOVERNANCE` | One rule-change round advancing on a three-member quorum |
| Proposal review | 3 | `STEP 3: PROPOSAL REVIEW AND VOTE` | A pinned proposal reviewed and approved by three core members |
| Evidence versus authority | 4 | `STEP 4: MILESTONE REVIEW AND VOTE` | Advisory findings separated from the agenda allowlist |
| Funding mechanics | 5 | `STEP 5: COUPON ALLOCATION` | A real coupon allocation with manager and amount checks |
| Failure modes | 6 | `STEP 6: BLOCKED CASES` | Credentials, quorum (two Yes lapses), double votes and non-members blocked |

### Optional pitch section (not part of `all`)

| Slide | Command | What it shows |
| --- | --- | --- |
| Stale decision safety | `./run-demo.sh opt-step 1` | After step 2 and active v2: a fresh v1-based agenda is approved by vote, but execution is refused on snapshot drift; main anchor unchanged |

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
- **Only one voting pattern is demonstrated.** The positive steps show 3 Yes
  and 4 Absent. A contested round — three Yes against four No — is *not* run
  live, so nothing here demonstrates how the majority rule behaves when
  somebody votes against. The narration says so rather than letting three Yes
  votes look unconditionally decisive.
- **Agent logs are presentation output.** The concise container logs are
  derived from the agents' own evaluation results and suppress unchanged
  repeats; they are not a complete audit trail. Use `DEMO_AGENT_VERBOSE=1`, or
  `--verbose`/`--json` directly, when the full machine-readable record is
  wanted.
- **The live run for the current behaviour is unrecorded.** See the warning in
  [Verified run](#verified-run): the quorum change, the agent logs and the
  phase/evidence output are proven offline, and the previous run's timings,
  contract IDs and `[ACTION]` counts are stale.
- **Ledger evidence counts are active-now, not history.** They are real queries
  with a stated scope and deduplicated contract IDs, but an ACS query cannot
  show an archived contract. A consumed ballot is simply absent, which is why
  the evidence lines say `(consumed when counted)` and point at
  `./ledger.sh archived` and `history` for the transaction-history side rather
  than inferring past events from present absence.
- **Evidence scope is the witness parties, not the participant.** Counts are
  what the named parties can see — opener plus member for governance, DSO plus
  beneficiary for coupons. A contract visible to neither would not appear, and
  nothing here claims a participant-wide inventory.

## S5: runtime rule references and grounded outcomes

This supersedes earlier demo clause examples. Bootstrap script input supplies `initialRuleRef` and `proposedRuleRef`; the runner hashes exact JSON bytes at runtime. The signed demo anchor carries the proposed reference into the v2 vote. No rule text hash is compiled into the live Daml flow; fixed Daml references are synthetic test data only. Rule-document edits after this migration do not require a new Daml package.

Pinned PR #184 (one complete verified file): v1 fails the three-proving-domain requirement using “Both reference flows (auction and governance) run end-to-end on a Canton sandbox.” and fails the independent audit presence requirement as `absent`. V2 passes composition, auditable deterministic state, and the acceptance-criteria heading. Issue #540 remains INCONCLUSIVE: it has none of those v2 support phrases and is not eligible for absence. Downstream execution hooks are deliberately not a v2 criterion because that phrase also occurs in #540.

Verified unsafe model quotes are represented in cache by normalized-file offsets and SHA-256, not plaintext; text is reconstructed against current evidence and displayed only redacted. A verified unsafe contradiction can retain FAIL; presence without an indicator remains `semantic_support_unconfirmed`.

Additional log reason codes: `semantic_support_unconfirmed` (verified semantic quote without code indicator), `presence_evaluation_failed` (contained predicate error), `malformed_clause` (invalid rule clause), `unsafe_quote_redacted` (unsafe display content; quote references avoid plaintext persistence), `rule_unverified` (document authority unavailable).

### Agent evidence mode

With the shared PAT overlay (`INTAKE_GITHUB_TOKEN_FILE` set), Reviewer use live authenticated GitHub evidence by default. The fixed PR #184 head/blob identity remains required; mismatch fails closed. Without the overlay, or with `AGENT_EVIDENCE_MODE=offline`, they use the explicit offline PR fixture plus packaged issue #540 observation. Startup logs identify authenticated_file, anonymous, offline_fixture or offline_repository without credentials. Canton must already be running. To recreate only the agents after selecting the overlays, run `docker compose -p babycdf-live --profile agents -f compose/compose.yaml -f compose/intake-auth.yaml -f compose/agents-github-auth.yaml -f compose/llm-auth.yaml up -d --no-deps --build --force-recreate reviewer` from the demo directory. This command is documented only, not run by validation.

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

## Intake summary before proposal/milestone steps

Steps 3 and 4 print a compact informational catalogue summary, not raw container lines. The shared helper reads the complete current-project Intake log with `compose logs --no-color intake`, without tail/since limits. Added and updated events are deduplicated by kind and number; repeated restart lines do not inflate counts. Distinct pause/error messages are sanitized and limited to five displayed messages plus an omitted count. Log-read or summary failure prints a categorical unavailable line and never changes the vote, markers or step outcome. Counts describe retained container logs, not ledger evidence, authoritative catalogue totals, archived containers or voting authority. Steps 2/5 and optional steps do not paste agent log slices; `all` uses the same step helper. The explicit `logs` command remains an intentional interactive raw-log viewer.
