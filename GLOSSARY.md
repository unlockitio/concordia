<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Glossary

Terms are grouped by area and alphabetical within each section. 

## Properties

Cross-cutting properties a [mechanism](#mechanism) may or may not have.

### Completeness
The property that a [mechanism](#mechanism) resolves against a fixed, known set of
[participants](#participant) such that every [submission](#submission) that was made is
provably included in the [outcome](#outcome), and every [participant](#participant) that
did not submit is provably accounted for.

<sub>Source: CAP design (no external source).</sub>

### Contention
The condition where participants attempt to act on the same shared state at the
same time, forcing their actions to be ordered one after another so that some must
wait or retry — a cost that grows as more participants act concurrently.

<sub>Source: CAP design; standard concurrency-control term.</sub>


## CS sense

Terms used in a specific computer-science sense within the design.

### Binding
An artifact bound to a specific referent's group.

<sub>Source: CAP design (no external source).</sub>

### Hook
Extension point the fixed body must call; body supplied by the implementation.

<sub>Source: CAP design (no external source).</sub>

### Opaque
Content the interface can't read; only a decoder interprets it.

<sub>Source: CAP design (no external source).</sub>

### Policy
A behavior body supplied to a hook; named ready-made ones live in `*.Policies`.

<sub>Source: CAP design (no external source).</sub>

### Referent
A contract (mechanism/target) owning an identity group others bind to.

<sub>Source: CAP design (no external source).</sub>

### Skeleton
Interface + fixed bodies with holes a domain fills.

<sub>Source: CAP design (no external source).</sub>

### Tool
Opt-in reusable utility body (a `util/` helper, not a forced check).

<sub>Source: CAP design (no external source).</sub>


## cap-core

Domain-agnostic primitives for [mechanisms](#mechanism).

### Admission
The discipline by which a [mechanism](#mechanism) accepts a contract an actor
presents: the contract is proved to be the *specific instance* intended — bound
to this mechanism by group or by stable key — not merely one of the right type.
Without it an actor could present a look-alike of the right shape but the wrong
identity.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Authority
The set of parties whose joint signature authorizes a [mechanism](#mechanism):
every member signs the mechanism and every [submittable](#submittable) in it,
and nothing carrying fewer than all members' signatures counts as part of the
mechanism. A single party is the singleton case.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Cover
The proof, at resolution, that the [submittables](#submittable) gathered fill
every [slot](#slot) of a [complete](#completeness) [mechanism](#mechanism) exactly
once — none missing, none counted twice. A slot may be covered by an empty
submittable, which accounts for a non-participant without contributing to the
result. Exact cover is what turns a claim of [completeness](#completeness) into an
on-ledger fact.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Execute
Carrying out the action a resolved [outcome](#outcome) holds — the point at
which its pre-committed [authority](#authority) takes effect. Execution follows
[resolution](#resolution) and may occur at most once, within the
[outcome](#outcome)'s window.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Executor
The party permitted to [execute](#execute) an approved [outcome](#outcome).


<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Expiry
The archival of a [mechanism](#mechanism) artifact — a [ballot](#ballot), a
[governor](#governor), an [outcome](#outcome) — once it can no longer play its
part, past a stated deadline. Expiry moves no value and produces no
[outcome](#outcome), so anyone may trigger it; it is deliberately separate from
[resolution](#resolution) and [execution](#execute).

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Mechanism
An interaction between participants that collects a set of
[submittables](#submittable) and produces a set of [outcomes](#outcome).

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Outcome
A pre-approved action to be [executed](#execute) — the product of resolving a
[mechanism](#mechanism).

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Participant
A party that takes part in a [mechanism](#mechanism), holding one
[submittable](#submittable) as its place in the [resolution](#resolution) —
whether or not that submittable ends up carrying a [submission](#submission). The
participant set is the base [completeness](#completeness) and [quorum](#quorum)
are measured against.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Resolver
The party permitted to compute the outcome of a [mechanism](#mechanism).

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Resolution
The act by which a [mechanism](#mechanism) reads its full [submissions](#submission),
applies its rule and produces its [outcomes](#outcome). 

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Slot
A position in the fixed set a [mechanism](#mechanism) resolves against when it
claims [completeness](#completeness), numbered from zero. Each slot carries
exactly one live [submittable](#submittable) from registration to resolution,
which is what lets the set be proved complete.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Standing
Said of a contract that outlives an action on itself.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Submission
The content a [submittable](#submittable) carries — what a party actually put
forward: a [vote](#vote) on a [ballot](#ballot), an offered price on a
[bid](#auctionbid). A submittable carries a submission only in its full state; an
empty or withdrawn one carries none. The submission is what a
[mechanism](#mechanism) reads to resolve — what a [tally](#tally) or pricing rule
scores.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Submittable
The single live artifact a [slot](#slot) holds while a [mechanism](#mechanism)
runs — one per participant, standing for that participant's place in the
resolution rather than for any content in particular. A submittable is in one of
three states: empty (the slot is registered but nothing has been submitted), full
(it holds the participant's [submission](#submission)), or withdrawn (a submission
that was retracted). Exactly one submittable is live per slot from registration to
resolution, which is what lets the set be proved [complete](#completeness). A
submittable records which mechanism and which slot, never who filled it, so slots
stay pseudonymous; only the full ones carry content for resolution.

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Withdrawal
The retraction by a participant of their own [submission](#submission) before
[resolution](#resolution). Under [completeness](#completeness) the withdrawn
[slot](#slot) stays covered but uncast, so the set can still be proved complete.
The counterpart to [cast](#cast); only the submitter may withdraw.

<sub>Source: CAP `cap-core` design (no external source).</sub>


## Governance

Governance is how a group of participants makes and executes collective decisions
that bind the group because agreed rules were followed. Each participant's
influence is a *say* — a pure record of preference — and the mechanism turns the
collected says into a [verdict](#verdict) that authorises an [outcome](#outcome).
What is scarce is the say itself: it is spent when [cast](#cast), so no one
decides twice. (Contrast an [auction](#auctions), where the say is backed by value.)

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Ballot
The container voters [cast](#cast) into exactly one [governor](#governor):
it records who voted and when, and carries the [votes](#vote) as its content.
A format may give each voter their own ballot or accumulate many voters'
votes in one.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Cast
The act of a voter recording their [vote](#vote) in a [ballot](#ballot) of an
open [governor](#governor); where the voter holds a pre-minted
[voting right](#voting-right), casting spends it.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Commitment
What an approval binds an [outcome](#outcome) to act upon: the [target](#target)
it may touch, identified by stable key, and optionally the [state token](#state-token)
the approvers saw. Execution must present exactly what was committed; anything
else is refused.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Drift
The divergence between a [target](#target)'s state when an [outcome](#outcome) was
approved and its state when the outcome comes to execute, arising when the target
moves in between. Whether drift refuses execution or is absorbed by a
[merge](#merge) is a declared policy, not a fixed rule.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Governor
The governance [mechanism](#mechanism) that puts an
action before the [participants](#participant) for a collective decision, collects
their [ballots](#ballot), and resolves to the [outcome](#outcome) executed if the
decision approves it.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Merge
The absorbing response to [drift](#drift), opposite to refusing it: rather than
comparing execution against the state seen at approval, the approved change is
re-applied as a field-wise patch onto the [target](#target)'s *current* state.
Because each change patches only the fields it touches instead of replacing the
whole state, two approvals touching different fields compose in either order
instead of the later reverting the earlier.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Pin
The binding of a [commitment](#commitment) to what the approvers actually saw: an
*identity pin* fixes only which [target](#target); a *state pin* also fixes the
target's [state token](#state-token). A pin is what makes [drift](#drift)
detectable — execution can tell whether the target still matches what was approved.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Proposer
The party who puts a [governor](#governor) before the [participants](#participant)
and may withdraw it before voting opens. The proposer opens the decision but does
not decide it.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Quorum
A support floor a [governor](#governor) must reach to pass, measured against the
*[participants](#participant)* rather than turnout — so low participation
cannot lower the bar. It may be **absolute** (e.g. at least 8 yes-votes) or
**relative** (e.g. 40% of the [participants](#participant)). Contrast a [threshold](#threshold),
which is measured against the votes actually *cast*, not the participant set.

<sub>Source: CAP `cap-governance` design; standard voting-theory term.</sub>

### State token
An opaque marker on a [target](#target) that changes whenever — and only when —
its action-relevant state changes. A [commitment](#commitment) records the token
the approvers saw, and a mismatch at execution is what signals [drift](#drift).

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Tally
The rule that turns the cast [ballots](#ballot) of a [governor](#governor) into a
[verdict](#verdict) — applying [quorum](#quorum), [threshold](#threshold),
weighting, and tie-breaking. It reads the [votes](#vote) and decides; it executes
nothing.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Target
A standing contract an approved [outcome](#outcome) acts upon — a config, a rule
set, a treasury. A target is identified by a stable key (its [authority](#authority)
plus an id).

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Threshold
The share of the votes actually *cast* that the winning option must exceed — a
**majority** at 50%, or a **super-majority** above it (e.g. two-thirds). Measured
against turnout, unlike a [quorum](#quorum), which is measured against all
[participants](#participant).  

<sub>Source: CAP `cap-governance` design; standard voting-theory term.</sub>

### Timelock
A delay between a [governor](#governor)'s approval and the execution of its
[outcome](#outcome), during which the parties it affects can react to the
now-known result before it becomes binding.

<sub>Source: CAP `cap-governance` design; "timelock" in the sense of OpenZeppelin's
[`TimelockController`](https://docs.openzeppelin.com/contracts/5.x/api/governance)
— a mandatory delay before a governance action takes effect.</sub>

### Verdict
The result the [tally](#tally) produces for a [governor](#governor):
**approved**, **rejected**, or **lapsed** (the vote never became definitive).


<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Vote
The value a [ballot](#ballot) carries: the option the voter chose (or none, for a
blank ballot). The vote is content; the ballot is the container that holds it.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

## Auctions

An auction is how one or more sellers allocate scarce items among competing
bidders by fixed rules that determine, from the bids, who wins and what they pay.
Each [bid](#auctionbid) is a say *backed by value* — an [escrowed](#escrow)
commitment of capital, not a mere signal — and the mechanism resolves the bids
into an allocation [settled](#settlement) by exchanging item for payment. What is
scarce is capital. (Contrast [governance](#governance), where the say moves no value.)

<sub>Source: CAP `cap-governance` design (no external source).</sub>


### AuctionBid
The offer a buyer submits in an [auction](#auctions): the price they are willing to
pay for the item, backed by an [escrow](#escrow).

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

### Clearing
The step in which an [auction](#auctions) determines, from the
[bids](#auctionbid) and by its fixed rules, who wins and what each winner pays —
the auction's [resolution](#resolution). In an on-ledger flow, clearing and the
asset movement can [settle](#settlement) atomically.

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

### Escrow
Money set aside and locked when a [bid](#auctionbid) is placed, held until the
[auction](#auctions) resolves — paid to the seller if the bid wins, or returned to
the bidder if it loses.

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

## Canton Token Standard

The Canton asset / settlement layer CAP composes with — reused, not part of CAP
itself.

### Account
An on-ledger managed account (e.g. a traditional accounting structure, or simply a
delegation to a [provider](#provider) to perform services). In Token Standard V2 a
[Holding](#holding) is located at an account.

<sub>Source: CIP-0112 (Canton Token Standard V2), `Account`.</sub>

### Allocation
An amount of asset [holdings](#holding) reserved for a specific [leg](#leg) of a
[settlement](#settlement).

<sub>Source: Canton Token Standard, `Splice.Api.Token.AllocationV1` interface doc comment.</sub>

### DvP
Delivery versus payment: an exchange in which the asset moves to the buyer and
payment moves to the seller together, or neither moves — so no party is left
having delivered without being paid.
l
<sub>Source: Canton Token Standard (delivery-versus-payment settlement).</sub>

### Factory
A contract a [registry](#registry) supplies for a party to initiate an asset action
(e.g. a [transfer](#transfer), an [allocation](#allocation)). Wallets fetch the
current factory from the registry rather than referencing one directly.

<sub>Source: Canton Token Standard (transfer / allocation factory APIs).</sub>

### Holding
A discrete unit of an [instrument](#instrument)'s balance held at an
[Account](#account); an account's total balance is the sum of its holdings.

<sub>Source: Canton Token Standard, `Splice.Api.Token.HoldingV1` / CIP-0056 (V2 account model: CIP-0112).</sub>

### Instrument
An asset type on the Token Standard (e.g. a currency, a token, another fungible),
identified by a globally unique id and administered by a [registry](#registry).
[Holdings](#holding) are denominated in an instrument.

<sub>Source: Canton Token Standard, `HoldingV1` `InstrumentId`.</sub>

### Leg
One transfer of [holdings](#holding) between two parties for the purpose of a
[settlement](#settlement); a settlement often requires the atomic execution of
multiple legs.

<sub>Source: Canton Token Standard, `AllocationV1` `TransferLeg` doc comment.</sub>

### Provider
A party that operates account or transfer services on another party's behalf.

<sub>Source: Canton Token Standard (CIP-0112 `Account.provider`; `TransferPreapproval` provider role).</sub>

### Registry
The app that administers an [instrument](#instrument): it issues
[holdings](#holding) and supplies the [factory](#factory) contracts and off-ledger
context data that wallets use to build [transfers](#transfer) and
[allocations](#allocation).

<sub>Source: Canton Token Standard / CIP-0056 (registry APIs).</sub>

### Settlement
The atomic exchange of assets across parties, composed of one or more [legs](#leg)
that all execute together or not at all.

<sub>Source: Canton Token Standard (`AllocationV1` `SettlementInfo` / `TransferLeg`).</sub>

### Transfer
A direct movement of [holdings](#holding) from one party to another
(free-of-payment), initiated by the sender through a [factory](#factory) and —
unless the receiver has pre-approved incoming transfers — completed when the
receiver accepts it. Distinct from a [settlement](#settlement), which moves
multiple [legs](#leg) atomically.

<sub>Source: Canton Token Standard, `TransferInstructionV1`.</sub>
