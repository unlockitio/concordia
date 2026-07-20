<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# First delivery: cap-core and cap-governance

This document describes the two packages of the first delivery, file by file.
For each Daml module it states what the file is, the types and choices it
defines, and which design decision it realizes. Reading it top to bottom shows
how a governance workflow — propose, vote, resolve, execute — is assembled from
generic parts.

The first delivery includes two layers:

- **cap-core** — the generic mechanism layer. It knows nothing about voting. It
  defines what a *mechanism awaiting resolution* is, what a *submission* into it
  is, and what an *executable outcome* out of it is. Any domain (governance,
  auctions) builds on it.
- **cap-governance** — the voting domain. It instantiates cap-core's interfaces
  as the governor of a proposal, a vote, the approved action (`GovernanceOutcome`), 
  and the contract a passed proposal acts on (`Target`).

A design principle runs through both layers: **an interface choice body is fixed
and implementations cannot override it.** Every guarantee that must hold for all
implementations lives in a fixed choice body or in a function that a fixed body
calls. Everything an implementation is free to vary is an interface method the
implementation supplies. The document points out, per file, which is which.

---

## 1. Package layout and dependency direction

```
cap-core/
  Interfaces/
    mechanism/    Cap.Core.MechanismV1      the mechanism face + ForMechanism group
    submittable/  Cap.Core.SubmittableV1    one live submission slot
    outcome/      Cap.Core.OutcomeV1        an executable outcome 
  internal/
    checks/       Cap.Core.ChecksV1         the fixed admission checks
  util/
    checked-fetch/ Cap.Core.CheckedFetchV1  contract-group admission vocabulary
    time/          Cap.Core.TimeV1          the timing convention
    policies/      Cap.Core.Policies        named timing profiles
    patchable/     Cap.Core.Patchable       field-wise state merge for drift

cap-governance/
  Interfaces/
    governor/     Cap.Governance.GovernorV1  proposal under vote (requires Mechanism)
    ballot/       Cap.Governance.BallotV1    a vote (requires Submittable)
    target/       Cap.Governance.TargetV1    a contract a passed proposal acts on
    outcome/      Cap.Governance.OutcomeV1   the approved action (requires Outcome)
  util/           Cap.Governance.{Policies,Tallies,TargetPolicies,TargetUtil}
```

Dependencies point one way. cap-core packages depend only on the Canton Token
Standard's metadata and util packages. cap-governance depends on cap-core.
Inside cap-governance the dependency is `Governor` → `Ballot`.
`Ballot` names the proposal it belongs to only by its generic
`Mechanism` face, so the vote package does not know the governor exists. This is
what lets a single `Ballot` interface serve one vote-per-voter and one
accumulate-all-votes-in-one-contract format without either format being wired
into the vote type.

Each interface is its own package and carries a `-v1` name. A version is frozen
once published; a breaking change is a new package (`-v2`), and the extension
constructors described below let result types grow without one.

---

## 2. cap-core — the generic mechanism layer

### 2.1 `Cap.Core.MechanismV1` — the mechanism face

A **mechanism** is anything awaiting resolution: a governance vote, an auction.
A domain interface `requires Mechanism`, so every domain mechanism carries this face.

`MechanismView` is the single source for the fields the shared checks read:

- `authorities : Set Party` — the signature anchor. It's the identity whose 
  ledger authority every action on that contract runs under.
- `size : Optional Int` — the completeness switch and the participant count in
  one field. `Some n` means the mechanism proves *exact cover* of slots `0..n-1`
  at resolution (no slot omitted); `None` means no completeness is claimed. 

Why a count, not the party list ? The gate proves no slot was dropped, not that the
right party filled each one — binding identity to slot is enrollment's job, done 
when one slots are minted. A bare n keeps the frozen face
identity-agnostic, so secret ballots and non-party authorities are not locked 
out. Splice needs a live svs list Map read at close so it must carry the real list. 




The interface declares **timing-policy hooks**: To tight the fixed check of one
lifecycle action. The domain's choice bodies musr invoke them, so a policy can
be permissive but not absent. Standard profiles are supplied in
`Cap.Core.Policies` (see 2.7).

The module also defines `ForMechanism` — the contract group a submission or
outcome belongs to. It is only ever compared, so the bound mechanism may
already be archived when the comparison runs.

*Realizes:* authority-as-a-set; the completeness switch; one timing convention
consumed through mandatory `Time`s; metadata never security-relevant.

### 2.2 `Cap.Core.SubmittableV1` — one live submission slot

A **submittable** is the single live artifact a slot holds while a mechanism
runs. It records which mechanism and which slot; the submitted content is read
by downcasting the submittable to the implementation's own template.

`SubmittableState` is `S_Empty` (slot registered, nothing submitted), `S_Full`
(holds the submission — the only state resolution scores), `S_Withdrawn`, plus
an `ExtSubmittableState` extension constructor so the state set can grow without
a package major.

`SubmittableView` carries `mechanism` (the binding, compared not fetched),
`authorities` (the same anchor every member signs), `slot : Optional Int`
(`Some` on every artifact of a completeness mechanism, `None` otherwise), and
`state`. `state` is an implementation-written claim the interface cannot verify;
the implementation must mint `S_Full` exactly when the contract carries a
submission, because the fixed resolution bodies filter on it.

The module declares `instance HasCheckedFetch SubmittableView ForMechanism`,
which is what lets a resolution fetch a presented submittable *bound to this
mechanism* and reject one belonging to another.

*Realizes:* the submission's binding-by-group; the extension-constructor rule
for result and state types.

### 2.3 `Cap.Core.OutcomeV1` — an executable outcome

An **outcome** carries pre-committed authority and is enactable at most once
inside a window. This interface is deliberately
domain-neutral — identity, window, executor set, expiry — and the *execute*
choice is the domain's own, layered on via `requires`. Governance's target-bound
execute door lives in `Cap.Governance.OutcomeV1`.

`OutcomeView` carries `authorities` (whose joint authority the outcome carries),
`source : ContractId Mechanism` (the mechanism whose resolution issued it — the
outcome's contract group, so a relying contract admits "the outcome of *this*
mechanism" and a look-alike issued elsewhere aborts), `executeAfter : Time` (the
timelock — execution is blocked strictly before it), and
`executeUntil : Optional Time` (the deadline; `None` = executable forever, which
the field documents as a cost to avoid).

The interface declares `isExecutor : [Party] -> Bool` (who may trigger the
domain's execute choice; a list because settlement can need several executors
jointly) and one fixed choice, `Outcome_Expire`, which any visible party may
call to archive an outcome past its deadline. It is effect-free and refuses an
undated outcome, so archival is decoupled from execution.

*Realizes:* resolution issues, execution enacts (the two are separate choices);
the gate proves while a separate anyone-callable expiry archives. 
They can executed in the same transaction with Outomce as a transient contract.

### 2.4 `Cap.Core.ChecksV1` — the fixed admission checks

These are the checks the domain's submit and resolve choices run. They are
plain functions, not interface methods, because their enforcement locus is the
domain interface's *fixed* choice body, which calls them and which
implementations cannot override. A domain that skips a call here is
non-conforming at the interface-author level, never at the template level.

- `checkAuthoritySigned` — the mechanism's full authority signature must be in
  scope. It reads the authority set from the artifact's own view, so it can
  never be checked against a foreign set.
- `admitSubmittable` — admit one presented submission: fetch it through its
  `Submittable` face *bound to this mechanism* (via the `ForMechanism` group, so
  one belonging elsewhere aborts) and require the authority signed it.
- `admitSubmittables` — the completeness-free path: admit each submission and
  require the presented set is *distinct* (no cid twice). Distinctness is the
  no-double-count guard where there is no cover to give it — which is why
  resolution never has to consume a submission to stop a recount.
- `admitSubmittablesWithCompleteness` / `checkSlotCover` — the completeness path:
  admit each submission and prove the gathered slots are exactly `0..n-1`, each
  once. Exact cover subsumes distinctness, so no separate distinctness check
  runs here.

The gate *proves*; it never *consumes*. When and how the cover is spent is the
implementation's, inside its resolution or afterwards through expiry.

*Realizes:* resolution re-proves everything inside the fixed body — binding,
authority signature, and either distinctness or exact cover — on-ledger.

### 2.5 `Cap.Core.CheckedFetchV1` — contract-group admission vocabulary

This is the admission discipline that makes the checks above sound. The core
fact it addresses: a `ContractId` argument guarantees the *template type*, not
the *instance*. A cid supplied by the exercising actor must be admitted under an
instance-level group or compared against a stored cid, or an actor could present
a contract of the right type but the wrong identity.

The `HasCheckedFetch` class, the `fetchChecked*` family, and `require` come from
the Token Standard's util and are re-exported here so callers keep one import
surface. Only `authoritySigned` — "all parties in the authority signed this
contract" — is defined here. Each view declares its own `HasCheckedFetch`
instance beside its `viewtype`, and each group id lives with the interface it
groups: `ForMechanism` with `Mechanism`, `ForTarget` with `Target`.

*Realizes:* every actor-supplied cid is admitted through a group at the group-id
level.

### 2.6 `Cap.Core.TimeV1` — the one timing convention

The single home for the floor-inclusive/ceiling-exclusive convention, so no site
re-hand-rolls the comparison or the abort. `isOpen now from until` decides
whether `now` falls in the half-open window `[from, …)` — floor included,
ceiling excluded, ceiling optional. `checkWindow` and `checkFloor` are the abort
forms every temporal door is phrased in.

*Realizes:* one clock, one convention — submission, resolution, and execution
all pass through `isOpen`.

### 2.7 `Cap.Core.Policies` — named timing profiles

Ready-made bodies for the `Mechanism` timing hooks, so an implementation wires a
profile instead of writing the comparison. `resolveAfterClose` (resolve only
once the submission window has closed; aborts on a mechanism with no declared
close), `withdrawBeforeOpen` (the initiator can cancel only before submissions
open), and `noWithdraw`. The fixed bodies already enforce the submission window
and the resolution floor, so a profile only tightens further.

### 2.8 `Cap.Core.Patchable` — field-wise state merge for drift

The machinery behind the reconciling drift policy. `SetState` is a generic delta
("move the value from `base` to `new`"); `applySetState` enacts it as
`patch new base current` against the *fresh* state rather than overwriting it, so
two approved changes to different fields of the same target compose in either
order instead of the second reverting the first. `patch` and its instances come
from the Token Standard's util; only `SetState` is defined here.

*Realizes:* the reconciling half of concurrent-action handling — detection alone
is not enough, so reconciliation merges field-wise.

---

## 3. cap-governance — the voting domain

### 3.1 `Cap.Governance.GovernorV1` — proposal under vote

`Governor requires Mechanism`. It is a proposal put before an electorate and
resolved with `Ballot`s. `GovernorView` carries only what is governance's alone
— `proposer`, `proposal` (a url and body), `executeAfter` (the timelock voters
approve), `meta` — while the authority, window, expiry, and completeness switch
live on the required `MechanismView`.

Result types are three-way and upgradable. `Verdict` (method-level) is
`VApproved (Optional Text)` / `VRejected` / `VLapsed`, so an undecided vote
resolves to `VLapsed` rather than defaulting to a decision. `ResolutionOutcome`
(serialized) mirrors it with `RO_Approved` / `RO_Rejected` / `RO_Lapsed` plus an
`ExtResolutionOutcome` extension constructor.

The interface declares the implementation's freedoms as methods: `tally` (the
pure social-choice function — side effects belong in `onResolved`), `isResolver`
(who may resolve), `governor_issueOutcome` (builds the approved outcomes),
`onResolved` (side effects atomic with resolution), and `governor_withdrawImpl`.

`governor_issueOutcome` carries the tightest obligations, spelled out in its
comment: return at least one outcome, each signed by this governor's authority,
each with `source` set to this governor, `executeAfter` set to the governor's
`executeAfter`, and `committedTargets` set to the targets the approved option
authorized.

The fixed choice bodies are where the guarantees live:

- `Governor_Resolve` (consuming — a governor resolves exactly once). It asserts
  the resolver is authorized, re-checks the authority signature, checks the
  resolution floor and the `resolutionAllowed` policy, then admits the ballots
  through `admitBallots` and scores each with `admitTally`, runs the
  implementation's `tally`, and on approval calls `governor_issueOutcome`
  atomically. Ballots are read, not consumed — they lose their anchor only when
  the governor dies. Issuing the outcome inside resolution is what makes approval
  and outcome creation one transaction, preventing frontrunning.
- `Governor_Withdraw` (consuming) — timing delegated to the `withdrawAllowed`
  policy, so with the standard profile a proposer cannot cancel a losing vote.
- `Governor_Expire` (consuming, any visible actor) — archives a governor that
  can no longer resolve, never before its `expiresAt`.

Two module-level helpers back resolution. `admitBallots` routes to
`admitSubmittablesWithCompleteness` under a declared `size` (proving binding,
authority signature, and exact cover) or to `admitSubmittables` without one
(proving distinctness), then downcasts each admitted submission to `Ballot`.
`admitTally` scores one ballot: an `S_Full` ballot is tallied; an uncast or
withdrawn one only fills its slot and is legal only when the governor declares
completeness.

*Realizes:* resolution re-proves everything on-ledger; verdicts are three-way;
every result type upgrades in place; resolution issues while execution enacts.

### 3.2 `Cap.Governance.BallotV1` — a vote

`Ballot requires Submittable`. It is a re-castable submission carrying **opaque**
votes against a mechanism — one vote for a per-voter format, many for a
container format that accumulates every vote in one contract. It names the
mechanism only by its `Mechanism` face, which is why this package does not depend
on `Governor`.

`BallotView` exposes the *fact* of a cast (`governor` binding, and `state`
through the `Submittable` face) but hides the vote values and voters, so secret
ballots are implementable. It carries `voters : Set Party` (who may cast/withdraw
here; empty = open, eligibility then the implementation's) and `expiresAt`. It
declares `instance HasCheckedFetch BallotView ForMechanism` on the same anchor
the `Submittable` face uses, so both faces agree on "this mechanism."

The implementation supplies `ballot_castImpl`, `ballot_withdrawImpl`, and
`ballot_consumeImpl`; each method comment states the invariants the successor
must preserve (binding, voters, authority, slot; `S_Full` on a cast; an
`expiresAt` no earlier than the close of voting).

The fixed choices:

- `Ballot_Cast` (consuming, controlled by the casting voter) — empty→full,
  withdrawn→full, or full→full re-cast. It checks the ballot has not expired and
  the voter is eligible, fetches the governor for its window and policy (the
  fetch aborts if the vote is already resolved or withdrawn), checks the
  submission window and `submittableAllowed`, then calls `ballot_castImpl`. The
  vote is an `AnyValue` the interface never reads. Consuming the predecessor is
  the no-double-vote guarantee for a per-voter format.
- `Ballot_Withdraw` (consuming, controlled by the withdrawing voter) — retracts
  only the caller's own vote; the successor keeps the slot covered so
  completeness stays provable.
- `Ballot_Consume` (controlled by the authority) — teardown and escrow-release
  path, since resolution reads ballots but never consumes them.
- `Ballot_Expire` (any visible actor) — archives an expired ballot, effect-free.

*Realizes:* one `Ballot` interface admits both a per-voter and a container
format; consuming per-voter choices give at-most-once by construction; the vote
type is the implementation's, and the view hides content so commit-reveal
formats are possible.

### 3.3 `Cap.Governance.TargetV1` — a contract a passed proposal acts on

A **target** is a standing contract an approved outcome acts upon — config,
rules, a treasury. The interface is identity-only. Its job is to turn "which
contract, in which state" into a stable, trusted claim.

`ForTarget` is the identifying key: `authorities` plus an `id` distinguishing
several targets of one authority, so an approved outcome cannot be redirected to
a different target of the same authority. `TargetView` carries `authorities`,
`id`, and `stateToken : Optional Text` — an opaque token that changes whenever
action-relevant state changes, and only then. The `HasCheckedFetch` instance
groups a target by its key, so a look-alike under the same authority but a
different `id` aborts.

Two data types and one function carry the concurrency handling:

- `TargetCommitment` — what an approval commits an outcome to act upon: the
  target key, plus optionally the state the approvers saw. `stateToken = None` is
  an identity-only pin, itself part of what was approved.
- `CheckedTarget` — one commitment's result: the target as the fixed body
  fetched and verified it, plus a `drifted` flag.
- `checkTargetBinding` — verifies the fresh targets presented at execution are
  the ones committed at approval (aborts on a wrong key or a forged signature),
  and for a state-pinned commitment compares the token and reports drift. It
  routes drift to the caller's policy rather than deciding it.

*Realizes:* a committed action binds by stable key and state, not by cid, and
drift on a moved target is detected and routed to a declared policy.

### 3.4 `Cap.Governance.OutcomeV1` — the approved action

`GovernanceOutcome requires Outcome`. It is an approved action bound to the
targets its approvers saw, and it owns governance's one **execute door**.
Identity, window, and expiry come from the required cap-core `Outcome` face;
`GovernanceOutcomeView` carries only `committedTargets : [TargetCommitment]` (the
targets the outcome may act on, in the order the executor must present them;
empty for a targetless outcome such as a mint).

The implementation supplies `onTargetDrift` (the drift policy, invoked per
committed target whose pinned state no longer matches the fresh one) and
`governanceOutcome_executeImpl` (the enacted effect, handed the checked targets
in commitment order).

`GovernanceOutcome_Execute` is the fixed door (consuming — at most one
execution). It asserts the outcome is inside its `[executeAfter, executeUntil)`
window and the actors are an executor set, calls `checkTargetBinding` to verify
the presented targets against the commitments, invokes `onTargetDrift` for every
target reported as drifted, and only then runs `governanceOutcome_executeImpl`.

*Realizes:* execution enacts against exactly the committed targets, inside the
committed window, at most once, with drift routed to the declared policy before
any effect runs.

### 3.5 `cap-governance/util` — reference bodies an implementation wires

None of these are called by a fixed body; they are supplied bodies an
implementation chooses.

- `Cap.Governance.Policies` — governor timing profiles that also pin the
  timelock. `windowedResolution` (resolve only after the close, and never with a
  timelock that lifts before it) and `confirmationResolution` (resolve any time
  after open — sound only when a verdict once reached is final, i.e. quorums
  above half the electorate).
- `Cap.Governance.Tallies` — reference `tally` functions: `quorateMajority` and
  `quorateMajorityAll` (yes/no by an absolute quorum in the electorate),
  `weightedQuorate` (quorum in total weight), `medianValue` (value selection by
  median, the amulet-price rule — which the comment notes is not monotone under
  further casts, so it must be paired with a close-gated resolution profile), and
  `turnoutThreshold` (a share of turnout, sound only under completeness). Each
  takes a decoder because the vote is opaque; a decoder returns `None` for a
  ballot it does not recognize, so a foreign authority-signed ballot can neither
  skew nor block the count.
- `Cap.Governance.TargetPolicies` — the two `onTargetDrift` bodies:
  `driftAborts` (any drift refuses execution) and `driftReconciles` (drift
  allowed, paired with a `Patchable` merge in the execute body).
- `Cap.Governance.TargetUtil` — helpers for `governanceOutcome_executeImpl`
  bodies: `mutateTarget` (downcast a checked target, archive it, create the
  successor) and `fetchTargetFromContext` (admit a live target delivered in a
  choice context under its key).

---

## 4. The lifecycle end to end

A governance action runs through the two layers in one sequence:

0. **Mint Slots (Optional)** Someone mints the slots for voters to cast to ensure completness.
1. **Propose.** A proposer creates a template implementing `Governor` (and thus
   `Mechanism`). Its `MechanismView` fixes the authority, the voting window, the
   timelock, and — for a fixed electorate — `size`.
2. **Vote.** Each voter exercises `Ballot_Cast`, which checks the window and
   eligibility and records an opaque vote. The format (one ballot per voter, or
   one accumulating all votes) is the implementation's; the interface is the same.
3. **Resolve.** A resolver exercises `Governor_Resolve`. The fixed body
   re-proves the authority signature, admits every ballot through its contract
   group (distinctness, or exact cover under `size`), runs the implementation's
   `tally`, and on approval calls `governor_issueOutcome` — all in one
   transaction. The result is one or more `GovernanceOutcome` contracts, each
   pinned to a `source`, a timelock, and its committed targets.
4. **Execute.** After the timelock lifts, an executor exercises
   `GovernanceOutcome_Execute`. The fixed body checks the window and the executor
   set, verifies the presented targets against the commitments with
   `checkTargetBinding`, routes any drift to `onTargetDrift`, and runs the
   implementation's effect against the fresh, verified targets.
5. **Clean up.** Anything left unresolved or spent is archived through the
   effect-free, anyone-callable `*_Expire` choices, decoupled from resolution and
   execution.

Every step's guarantee lives in a fixed body; every step's freedom is an
interface method. That division is the whole design.

---

## 5. Where each design decision lives

| Decision | Realized in |
| --- | --- |
| Authority is a set | `authorities : Set Party` on every view (`MechanismV1`, `SubmittableV1`, `OutcomeV1`, `TargetV1`) |
| Membership is a predicate the fixed checks consult | `isResolver` (`GovernorV1`), `isExecutor` (`OutcomeV1`) |
| Every cid is admitted through a group | `ForMechanism` (`MechanismV1`), `ForTarget` (`TargetV1`), the `HasCheckedFetch` instances, and `CheckedFetchV1` |
| Resolution re-proves everything in the fixed body | `Governor_Resolve` calling `ChecksV1` |
| One clock, one convention | `isOpen` / `checkWindow` / `checkFloor` in `TimeV1`, used by every temporal door |
| Consuming per-voter ballots | `Ballot_Cast` / `Ballot_Withdraw` in `BallotV1` |
| Verdicts are three-way | `Verdict` and `ResolutionOutcome` in `GovernorV1` |
| Resolution issues; execution enacts | `governor_issueOutcome` in `Governor_Resolve` vs the separate `GovernanceOutcome_Execute` |
| Bind by key, reconcile by patch | `TargetCommitment` / `checkTargetBinding` (`TargetV1`), `onTargetDrift` (`OutcomeV1`), `Patchable` |
| Gate proves; expiry is effect-free and anyone-callable | `Outcome_Expire`, `Governor_Expire`, `Ballot_Expire` |
| One `Ballot` interface, two formats | `Ballot requires Submittable` naming only the `Mechanism` face (`BallotV1`); completeness via `size` and `admitSubmittablesWithCompleteness` |
| The vote type is the implementation's | `AnyValue` vote in `Ballot_Cast`; view hides content |
| `meta` is never checked | No fixed body reads `meta` on any view |
| Every result upgrades in place | `ExtResolutionOutcome`, `ExtSubmittableState`, and the record result types |
