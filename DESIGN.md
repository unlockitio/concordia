<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# First delivery: cap-core and cap-governance

This document describes the two packages of the first delivery, file by file.
For each Daml module it states what the file is, the types and choices it
defines, and which design decision it realizes. Reading it top to bottom shows
how a governance workflow — propose, [vote](GLOSSARY.md#vote), [resolve](GLOSSARY.md#resolution), execute — is assembled from
generic parts.

The first delivery includes two layers:

- **cap-core** — the generic [mechanism](GLOSSARY.md#mechanism) layer. It knows nothing about [voting](GLOSSARY.md#vote). It
  defines what a *[mechanism](GLOSSARY.md#mechanism) awaiting [resolution](GLOSSARY.md#resolution)* is, what a *[submission](GLOSSARY.md#submission)* into it
  is, and what an *executable [outcome](GLOSSARY.md#outcome)* out of it is. Any domain (governance,
  auctions) builds on it.
- **cap-governance** — the [voting](GLOSSARY.md#vote) domain. It instantiates cap-core's interfaces
  as the [governor](GLOSSARY.md#governor) of a proposal, a [vote](GLOSSARY.md#vote), the approved action (`GovernanceOutcome`), 
  and the contract a passed proposal acts on (`Target`).

A design principle runs through both layers: **an interface choice body is fixed
and implementations cannot override it** — so a check forced into the interface
is a check *every* legitimate implementation must satisfy forever. 
The design balances: **under-forcing** (claiming a guarantee but leaving
it to implementations) is recoverable — the guarantee becomes a documented
obligation. **Over-forcing** (a fixed check some legitimate
format cannot meet) is not recoverable, that format is locked out of the standard until an
interface change. So the boundary is deliberately conservative: only what all
implementations must satisfy goes in a fixed choice body or a function it calls;
everything an implementation is free to vary stays an interface method it
supplies; and reusable-but-optional [tools](GLOSSARY.md#tool) live in the opt-in utilities. 

---

## 1. Package layout and dependency direction

```
cap-core/
  Interfaces/
    mechanism/    Cap.Core.MechanismV1      the mechanism interface + ForMechanism group
    submittable/  Cap.Core.SubmittableV1    one live submission slot
    outcome/      Cap.Core.OutcomeV1        an executable outcome 
  internal/
    checks/       Cap.Core.ChecksV1         the fixed admission checks
  util/
    checked-fetch/ Cap.Core.CheckedFetchV1  contract-group admission vocabulary
    time/          Cap.Core.TimeV1          the timing convention
    policies/      Cap.Core.Policies        named timing policies
    patchable/     Cap.Core.Patchable       field-wise state merge for drift

cap-governance/
  Interfaces/
    governor/     Cap.Governance.GovernorV1  proposal under vote (requires Mechanism)
    ballot/       Cap.Governance.BallotV1    a vote (requires Submittable)
    target/       Cap.Governance.TargetV1    a contract a passed proposal acts on
    outcome/      Cap.Governance.OutcomeV1   the approved action (requires Outcome)
  util/           Cap.Governance.{Policies,Tallies,TargetPolicies,TargetUtil}
```

Cap-core packages depend only on the Canton Token
Standard's metadata and Splice util packages. cap-governance depends on cap-core.
Inside cap-governance the dependency is `Governor` → `Ballot`.
`Ballot` names the proposal it belongs to only by its generic
`Mechanism` interface, so the [vote](GLOSSARY.md#vote) package does not know the [governor](GLOSSARY.md#governor) exists. 

Each interface is its own package and carries a `-v1` name. A version is fixed
once published; a breaking change is a new package (`-v2`), and the extension
constructors described below let result types grow without one.

---

## 2. cap-core — the generic mechanism layer

Cap-core is the generic layer no domain concept touches: a [mechanism](GLOSSARY.md#mechanism)
awaiting [resolution](GLOSSARY.md#resolution), the [submittables](GLOSSARY.md#submittable)
collected into it, and the [outcomes](GLOSSARY.md#outcome). The three interfaces (2.1–2.3) fix that shape; 
the internal checks and utilities (2.4–2.8) are shared logic among interfaces.

### 2.1 `Cap.Core.MechanismV1` — the mechanism interface

A **[mechanism](GLOSSARY.md#mechanism)** is anything awaiting [resolution](GLOSSARY.md#resolution): a governance [vote](GLOSSARY.md#vote), an auction.
A domain interface `requires Mechanism`, so every domain [mechanism](GLOSSARY.md#mechanism) carries this interface.

`MechanismView` is the single source for the fields the shared checks read:

- `authorities : Set Party` — the [authority](GLOSSARY.md#authority): the identity whose 
  ledger [authority](GLOSSARY.md#authority) every action on that contract runs under.
- `size : Optional Int` — the [completeness](GLOSSARY.md#completeness) flag and the [participant](GLOSSARY.md#participant) count in
  one field. `Some n` means the [mechanism](GLOSSARY.md#mechanism) proves *exact [cover](GLOSSARY.md#cover)* of [slots](GLOSSARY.md#slot) `0..n-1`
  at [resolution](GLOSSARY.md#resolution) (no [slot](GLOSSARY.md#slot) omitted); `None` means no [completeness](GLOSSARY.md#completeness) is claimed. 

Why a count, not the party list ? The check proves no [slot](GLOSSARY.md#slot) was dropped, not that the
right party filled each one — [binding](GLOSSARY.md#binding) identity to [slot](GLOSSARY.md#slot) is enrollment's job, done 
when one [slots](GLOSSARY.md#slot) are minted. A bare n keeps the interface
identity-agnostic, so secret [ballots](GLOSSARY.md#ballot) and non-party [authorities](GLOSSARY.md#authority) are not locked 
out. By contrast, Splice needs a live svs list Map read at close so it must carry the real list. 




The interface declares **timing-policy [hooks](GLOSSARY.md#hook)**: To tight the fixed check of one
lifecycle action. The domain's choice bodies must invoke them, so a [policy](GLOSSARY.md#policy) can
be permissive but not absent. Standard [policies](GLOSSARY.md#policy) are supplied in
`Cap.Core.Policies` (see 2.7).

The module also defines `ForMechanism` — the contract group a [submission](GLOSSARY.md#submission) or
[outcome](GLOSSARY.md#outcome) belongs to. It is only ever compared, so the bound [mechanism](GLOSSARY.md#mechanism) may
already be archived when the comparison runs.

*Realizes:* authority-as-a-set; the [completeness](GLOSSARY.md#completeness) flag; one timing convention
consumed through mandatory `Time`s; metadata never security-relevant.

### 2.2 `Cap.Core.SubmittableV1` — one live submission slot

A **[submittable](GLOSSARY.md#submittable)** is the contract that occupies one [slot](GLOSSARY.md#slot) of a [mechanism](GLOSSARY.md#mechanism) — the [slot](GLOSSARY.md#slot)
is just an abstract position (an index, no contract of its own), and minting the [submittable](GLOSSARY.md#submittable)
is what fills it. Exactly one [submittable](GLOSSARY.md#submittable) is live per [slot](GLOSSARY.md#slot) from registration to [resolution](GLOSSARY.md#resolution), which is
what lets the [slot](GLOSSARY.md#slot) set be counted [complete](GLOSSARY.md#completeness). 

`SubmittableState` is `S_Empty` ([slot](GLOSSARY.md#slot) registered, nothing submitted), `S_Full`
(holds the [submission](GLOSSARY.md#submission) — the only state [resolution](GLOSSARY.md#resolution) scores), `S_Withdrawn`, plus
an `ExtSubmittableState` extension constructor.

`SubmittableView` carries `mechanism` (the [binding](GLOSSARY.md#binding), compared not fetched),
`authorities` (the same [authority](GLOSSARY.md#authority) every member signs), `slot : Optional Int`
(`Some` on [completeness](GLOSSARY.md#completeness), `None` otherwise), and
`state`. `state` is an implementation-written claim the interface cannot verify;
the implementation must mint `S_Full` exactly when the contract carries a
[submission](GLOSSARY.md#submission), because the fixed [resolution](GLOSSARY.md#resolution) bodies filter on it.


*Realizes:* the [submission](GLOSSARY.md#submission)'s binding-by-group; the extension-constructor rule
for result and state types.

### 2.3 `Cap.Core.OutcomeV1` — an executable outcome

An **[outcome](GLOSSARY.md#outcome)** carries pre-committed [authority](GLOSSARY.md#authority) and is executable at most once
inside a window. This interface is deliberately
domain-neutral — identity, window, [executor](GLOSSARY.md#executor) set, [expiry](GLOSSARY.md#expiry) — and the *execute*
choice is the domain's own, layered on via `requires`. Governance's target-bound
execute choice lives in `Cap.Governance.OutcomeV1`.

`OutcomeView` carries `authorities` (whose joint [authority](GLOSSARY.md#authority) the [outcome](GLOSSARY.md#outcome) carries),
`source : ContractId Mechanism` (the [mechanism](GLOSSARY.md#mechanism) whose [resolution](GLOSSARY.md#resolution) issued it — the
[outcome](GLOSSARY.md#outcome)'s contract group, so a relying contract [admits](GLOSSARY.md#admission) "the [outcome](GLOSSARY.md#outcome) of *this*
[mechanism](GLOSSARY.md#mechanism)" and a look-alike issued elsewhere aborts), `executeAfter : Time` (the
[timelock](GLOSSARY.md#timelock) — execution is blocked strictly before it), and
`executeUntil : Optional Time` (the deadline; `None` = executable forever, which
the field documents as a cost to avoid).

*Realizes:* [resolution](GLOSSARY.md#resolution) issues, execution applies (the two are separate choices);
the check proves while a separate authority-free [expiry](GLOSSARY.md#expiry) archives. 
They can executed in the same transaction with Outomce as a transient contract.

### 2.4 `Cap.Core.ChecksV1` — the fixed [admission](GLOSSARY.md#admission) checks

Checks the domain's choices use. 


*Realizes:* [resolution](GLOSSARY.md#resolution) re-proves everything inside the fixed body — [binding](GLOSSARY.md#binding),
[authority](GLOSSARY.md#authority) signature, and either distinctness or exact [cover](GLOSSARY.md#cover) — on-ledger.

### 2.5 `Cap.Core.CheckedFetchV1` — contract-group [admission](GLOSSARY.md#admission) body

CheckedFetch Logic mainly taken from Splice.

*Realizes:* every actor-supplied cid is [admitted](GLOSSARY.md#admission) through a group at the group-id
level.

### 2.6 `Cap.Core.TimeV1` 

Time helpers.

*Realizes:* one clock, one convention — [submission](GLOSSARY.md#submission), [resolution](GLOSSARY.md#resolution), and execution
all pass through `isOpen`.

### 2.7 `Cap.Core.Policies` 

Ready-made bodies for the `Mechanism` timing [hooks](GLOSSARY.md#hook).

### 2.8 `Cap.Core.Patchable` — field-wise state merge for drift

The logic behind the [merging](GLOSSARY.md#merge) [drift](GLOSSARY.md#drift) [policy](GLOSSARY.md#policy).

*Realizes:* the [merging](GLOSSARY.md#merge) half of concurrent-action handling — detection alone
is not enough, so a [merge](GLOSSARY.md#merge) applies the change as a field-wise patch.

### 2.9 Summary

| Module | Kind | Fixes / provides |
|---|---|---|
| `MechanismV1` (2.1) | interface | authority-as-set, [completeness](GLOSSARY.md#completeness) flag (`size`), timing [hooks](GLOSSARY.md#hook) |
| `SubmittableV1` (2.2) | interface | one live [slot](GLOSSARY.md#slot), binding-by-group, extensible state |
| `OutcomeV1` (2.3) | interface | pre-committed [authority](GLOSSARY.md#authority), execute-once window, [expiry](GLOSSARY.md#expiry) |
| `ChecksV1` (2.4) | [Tool](GLOSSARY.md#tool) | the fixed [admission](GLOSSARY.md#admission) checks [resolution](GLOSSARY.md#resolution) re-proves |
| `CheckedFetchV1` (2.5) | [Tool](GLOSSARY.md#tool) | group-level [admission](GLOSSARY.md#admission) of actor-supplied cids |
| `TimeV1` (2.6) | [Tool](GLOSSARY.md#tool) | one half-open window convention |
| `Policies` (2.7) | [Tool](GLOSSARY.md#tool) | named timing [policies](GLOSSARY.md#policy) for the [hooks](GLOSSARY.md#hook) |
| `Patchable` (2.8) | [Tool](GLOSSARY.md#tool) | field-wise [merge](GLOSSARY.md#merge) for [drift](GLOSSARY.md#drift) |

---

## 3. cap-governance — the voting domain

cap-governance fills the [skeleton](GLOSSARY.md#skeleton) for [voting](GLOSSARY.md#vote): the [governor](GLOSSARY.md#governor) is the
[mechanism](GLOSSARY.md#mechanism) (3.1), the [ballot](GLOSSARY.md#ballot) the
[submittable](GLOSSARY.md#submittable) (3.2), and `GovernanceOutcome` the
[outcome](GLOSSARY.md#outcome) (3.4). It adds a the [target](GLOSSARY.md#target)
(3.3) — the [standing](GLOSSARY.md#standing) contract a passed proposal acts on — with the reference [tallies](GLOSSARY.md#tally),
[policies](GLOSSARY.md#policy), and [target](GLOSSARY.md#target) bodies an implementation supplies (3.5).

### 3.1 `Cap.Governance.GovernorV1` — proposal under vote

`Governor requires Mechanism`. It is a proposal put before its [participants](GLOSSARY.md#participant) and
[resolved](GLOSSARY.md#resolution) with `Ballot`s. `GovernorView` carries only what is governance's alone
— `proposer`, `executeAfter` (the [timelock](GLOSSARY.md#timelock) voters approve), and `meta` (which
carries the proposal itself, e.g. a url and body) — while the [authority](GLOSSARY.md#authority), window,
[expiry](GLOSSARY.md#expiry), and [completeness](GLOSSARY.md#completeness) flag live on the required `MechanismView`.

Result types are three-way and upgradable. `Verdict` (method-level) is
`VApproved (Optional Text)` / `VRejected` / `VLapsed`, so an undecided [vote](GLOSSARY.md#vote)
[resolves](GLOSSARY.md#resolution) to `VLapsed` rather than defaulting to a decision. `ResolutionOutcome`
(serialized) mirrors it with `RO_Approved` / `RO_Rejected` / `RO_Lapsed` plus an
`ExtResolutionOutcome` extension constructor.

The interface declares the implementation's freedoms as methods: `tally` (the
pure social-choice function — side effects belong in `onResolved`), `isResolver`
(who may [resolve](GLOSSARY.md#resolution)), `governor_issueOutcome` (builds the approved [outcomes](GLOSSARY.md#outcome)),
`onResolved` (side effects atomic with [resolution](GLOSSARY.md#resolution)), and `governor_withdrawImpl`.

`governor_issueOutcome` carries the tightest obligations, spelled out in its
comment: return at least one [outcome](GLOSSARY.md#outcome), each signed by this [governor](GLOSSARY.md#governor)'s [authority](GLOSSARY.md#authority),
each with `source` set to this [governor](GLOSSARY.md#governor), `executeAfter` set to the [governor](GLOSSARY.md#governor)'s
`executeAfter`, and `committedTargets` set to the [targets](GLOSSARY.md#target) the approved option
authorized.


Two module-level helpers back [resolution](GLOSSARY.md#resolution). `admitBallots` routes to
`admitSubmittablesWithCompleteness` under a declared `size` (proving [binding](GLOSSARY.md#binding),
[authority](GLOSSARY.md#authority) signature, and exact [cover](GLOSSARY.md#cover)) or to `admitSubmittables` without one
(proving distinctness), then downcasts each [admitted](GLOSSARY.md#admission) [submission](GLOSSARY.md#submission) to `Ballot`.
`admitTally` scores one [ballot](GLOSSARY.md#ballot): an `S_Full` [ballot](GLOSSARY.md#ballot) is tallied; an uncast or
withdrawn one only fills its [slot](GLOSSARY.md#slot) and is legal only when the [governor](GLOSSARY.md#governor) declares
[completeness](GLOSSARY.md#completeness).

*Realizes:* [resolution](GLOSSARY.md#resolution) re-proves everything on-ledger; [verdicts](GLOSSARY.md#verdict) are three-way;
every result type upgrades in place; [resolution](GLOSSARY.md#resolution) issues while execution applies.

### 3.2 `Cap.Governance.BallotV1` — a vote

`Ballot requires Submittable`. It is a re-castable [submission](GLOSSARY.md#submission) carrying [opaque](GLOSSARY.md#opaque)
[votes](GLOSSARY.md#vote) against a [mechanism](GLOSSARY.md#mechanism) — one [vote](GLOSSARY.md#vote) for a per-voter format, many for a
container format that accumulates every [vote](GLOSSARY.md#vote) in one contract. It names the
[mechanism](GLOSSARY.md#mechanism) only by its `Mechanism` interface, which is why this package does not depend
on `Governor`.

`BallotView` exposes the *fact* of a [cast](GLOSSARY.md#cast) (`governor` [binding](GLOSSARY.md#binding), and `state`
through the `Submittable` interface) but hides the [vote](GLOSSARY.md#vote) values and voters, so secret
[ballots](GLOSSARY.md#ballot) are implementable. It carries `voters : Set Party` (who may [cast](GLOSSARY.md#cast)/withdraw
here; empty = open, eligibility then the implementation's) and `expiresAt`. It
declares `instance HasCheckedFetch BallotView ForMechanism` on the same [mechanism](GLOSSARY.md#mechanism)
the `Submittable` interface uses, so both interfaces agree on "this [mechanism](GLOSSARY.md#mechanism)."


*Realizes:* one `Ballot` interface admits both a per-voter and a container
format; consuming per-voter choices give at-most-once by construction; the [vote](GLOSSARY.md#vote)
type is the implementation's, and the view hides content so commit-reveal
formats are possible.

### 3.3 `Cap.Governance.TargetV1` — a contract a passed proposal acts on

A **[target](GLOSSARY.md#target)** is a [standing](GLOSSARY.md#standing) contract an approved [outcome](GLOSSARY.md#outcome) acts upon — config,
rules, a treasury. The interface is identity-only. Its job is to turn "which
contract, in which state" into a stable, trusted claim.

`ForTarget` is the identifying key: `authorities` plus an `id` distinguishing
several [targets](GLOSSARY.md#target) of one [authority](GLOSSARY.md#authority), so an approved [outcome](GLOSSARY.md#outcome) cannot be redirected to
a different [target](GLOSSARY.md#target) of the same [authority](GLOSSARY.md#authority). `TargetView` carries `authorities`,
`id`, and `stateToken : Optional Text` — an [opaque](GLOSSARY.md#opaque) token that changes whenever
action-relevant state changes, and only then. The `HasCheckedFetch` instance
groups a [target](GLOSSARY.md#target) by its key, so a look-alike under the same [authority](GLOSSARY.md#authority) but a
different `id` aborts.


*Realizes:* a committed action binds by stable key and state, not by cid, and
[drift](GLOSSARY.md#drift) on a moved [target](GLOSSARY.md#target) is detected and routed to a declared [policy](GLOSSARY.md#policy).

### 3.4 `Cap.Governance.OutcomeV1` — the approved action

`GovernanceOutcome requires Outcome`. It is an approved action bound to the
[targets](GLOSSARY.md#target) its approvers saw, and it owns governance's one **execute choice**.
Identity, window, and [expiry](GLOSSARY.md#expiry) come from the required cap-core `Outcome` interface;
`GovernanceOutcomeView` carries only `committedTargets : [TargetCommitment]` (the
[targets](GLOSSARY.md#target) the [outcome](GLOSSARY.md#outcome) may act on, in the order the [executor](GLOSSARY.md#executor) must present them;
empty for a targetless [outcome](GLOSSARY.md#outcome) such as a mint).

The implementation supplies `onTargetDrift` (the [drift](GLOSSARY.md#drift) [policy](GLOSSARY.md#policy), invoked per
committed [target](GLOSSARY.md#target) whose [pinned](GLOSSARY.md#pin) state no longer matches the fresh one) and
`governanceOutcome_executeImpl` (the applied effect, handed the checked [targets](GLOSSARY.md#target)
in [commitment](GLOSSARY.md#commitment) order).

*Realizes:* execution applies to exactly the committed [targets](GLOSSARY.md#target), inside the
committed window, at most once, with [drift](GLOSSARY.md#drift) routed to the declared [policy](GLOSSARY.md#policy) before
any effect runs.

### 3.5 `cap-governance/util` — reference bodies an implementation supplies

None of these are called by a fixed body; they are supplied bodies an
implementation chooses.

- `Cap.Governance.Policies` — [governor](GLOSSARY.md#governor) timing [policies](GLOSSARY.md#policy) that also [pin](GLOSSARY.md#pin) the
  [timelock](GLOSSARY.md#timelock). `windowedResolution` ([resolve](GLOSSARY.md#resolution) only after the close, and never with a
  [timelock](GLOSSARY.md#timelock) that lifts before it) and `confirmationResolution` ([resolve](GLOSSARY.md#resolution) any time
  after open — sound only when a [verdict](GLOSSARY.md#verdict) once reached is final, i.e. [quorums](GLOSSARY.md#quorum)
  above half the [participants](GLOSSARY.md#participant)).
- `Cap.Governance.Tallies` — reference `tally` functions. Each
  takes a decoder because the [vote](GLOSSARY.md#vote) is [opaque](GLOSSARY.md#opaque); a decoder returns `None` for a
  [ballot](GLOSSARY.md#ballot) it does not recognize, so a foreign authority-signed [ballot](GLOSSARY.md#ballot) can neither
  skew nor block the count.
- `Cap.Governance.TargetPolicies` — the two `onTargetDrift` bodies:
  `driftAborts` (any [drift](GLOSSARY.md#drift) refuses execution) and `driftMerges` ([drift](GLOSSARY.md#drift)
  allowed, paired with a `Patchable` [merge](GLOSSARY.md#merge) in the execute body).
- `Cap.Governance.TargetUtil` — helpers for `governanceOutcome_executeImpl`
  bodies: `mutateTarget` (downcast a checked [target](GLOSSARY.md#target), archive it, create the
  successor) and `fetchTargetFromContext` ([admit](GLOSSARY.md#admission) a live [target](GLOSSARY.md#target) delivered in a
  choice context under its key).

### 3.6 Summary

| cap-core (generic) | cap-governance (concrete) | What the domain adds |
|---|---|---|
| `Mechanism` | `Governor` (3.1) | proposal, `tally`, three-way `Verdict` |
| `Submittable` | `Ballot` (3.2) | [opaque](GLOSSARY.md#opaque) [votes](GLOSSARY.md#vote), per-voter *and* container formats |
| `Outcome` | `GovernanceOutcome` (3.4) | the one execute choice, [target](GLOSSARY.md#target) [binding](GLOSSARY.md#binding) |
| — | `Target` (3.3) | [standing](GLOSSARY.md#standing) contract + [drift](GLOSSARY.md#drift) detection (governance-only) |

---

## 4. cap-auctions — the auctions domain (in progress)

cap-auctions fills the same [skeleton](GLOSSARY.md#skeleton) for auctions, its one difference from governance is value: a bid is [escrowed](GLOSSARY.md#escrow), so the domain composes directly with the Canton Token
Standard, settling the sale through the standard's own [holdings](GLOSSARY.md#holding) and
[allocations](GLOSSARY.md#allocation). It is already in progress.

---

## 5. The lifecycle end to end

A governance action runs through the two layers in one sequence:

0. **Mint [Slots](GLOSSARY.md#slot) (Optional)** Someone mints the [slots](GLOSSARY.md#slot) for voters to [cast](GLOSSARY.md#cast) to ensure completness.
1. **Propose.** A [proposer](GLOSSARY.md#proposer) creates a template implementing `Governor` (and thus
   `Mechanism`). Its `MechanismView` fixes the [authority](GLOSSARY.md#authority), the [voting](GLOSSARY.md#vote) window, the
   [timelock](GLOSSARY.md#timelock), and — for a fixed [participant set](GLOSSARY.md#participant) — `size`.
2. **[Vote](GLOSSARY.md#vote).** Each voter exercises `Ballot_Cast`, which checks the window and
   eligibility and records an [opaque](GLOSSARY.md#opaque) [vote](GLOSSARY.md#vote). The format (one [ballot](GLOSSARY.md#ballot) per voter, or
   one accumulating all [votes](GLOSSARY.md#vote)) is the implementation's; the interface is the same.
3. **[Resolve](GLOSSARY.md#resolution).** A [resolver](GLOSSARY.md#resolver) exercises `Governor_Resolve`. The fixed body
   re-proves the [authority](GLOSSARY.md#authority) signature, [admits](GLOSSARY.md#admission) every [ballot](GLOSSARY.md#ballot) through its contract
   group (distinctness, or exact [cover](GLOSSARY.md#cover) under `size`), runs the implementation's
   `tally`, and on approval calls `governor_issueOutcome` — all in one
   transaction. The result is one or more `GovernanceOutcome` contracts, each
   [pinned](GLOSSARY.md#pin) to a `source`, a [timelock](GLOSSARY.md#timelock), and its committed [targets](GLOSSARY.md#target).
4. **Execute.** After the [timelock](GLOSSARY.md#timelock) lifts, an [executor](GLOSSARY.md#executor) exercises
   `GovernanceOutcome_Execute`. The fixed body checks the window and the [executor](GLOSSARY.md#executor)
   set, verifies the presented [targets](GLOSSARY.md#target) against the [commitments](GLOSSARY.md#commitment) with
   `checkTargetBinding`, routes any [drift](GLOSSARY.md#drift) to `onTargetDrift`, and runs the
   implementation's effect against the fresh, verified [targets](GLOSSARY.md#target).
5. **Clean up.** Anything left unresolved or spent is archived through the
   effect-free, authority-free `*_Expire` choices, decoupled from [resolution](GLOSSARY.md#resolution) and
   execution.

Every step's guarantee lives in a fixed body; every step's freedom is an
interface method. That division is the whole design.

---

## 6. Where each design decision lives

| Decision | Realized in |
| --- | --- |
| Every cid is [admitted](GLOSSARY.md#admission) through a group | `ForMechanism` (`MechanismV1`), `ForTarget` (`TargetV1`), the `HasCheckedFetch` instances, and `CheckedFetchV1` |
| [Resolution](GLOSSARY.md#resolution) re-proves everything in the fixed body | `Governor_Resolve` calling `ChecksV1` |
| [Verdicts](GLOSSARY.md#verdict) are three-way | `Verdict` and `ResolutionOutcome` in `GovernorV1` |
| [Resolution](GLOSSARY.md#resolution) issues; execution applies | `governor_issueOutcome` in `Governor_Resolve` vs the separate `GovernanceOutcome_Execute` |
| Bind by key, [merge](GLOSSARY.md#merge) by Patchable | `TargetCommitment` / `checkTargetBinding` (`TargetV1`), `onTargetDrift` (`OutcomeV1`), `Patchable` |
| Check proves; [expiry](GLOSSARY.md#expiry) is effect-free and imposes no [authority](GLOSSARY.md#authority) check | `Outcome_Expire`, `Governor_Expire`, `Ballot_Expire` |
| One `Ballot` interface, two formats | `Ballot requires Submittable` naming only the `Mechanism` interface (`BallotV1`); [completeness](GLOSSARY.md#completeness) via `size` and `admitSubmittablesWithCompleteness` |
| The [vote](GLOSSARY.md#vote) type is the implementation's | `AnyValue` [vote](GLOSSARY.md#vote) in `Ballot_Cast`; view hides content |

