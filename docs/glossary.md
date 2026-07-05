<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Glossary

Terms are grouped by area and alphabetical within each. Definitions describe the
concept no code and not related to daml. Cross-links jump between entries. Currently being updated.

## cap-core

Domain-agnostic primitives for [mechanisms](#mechanism).

### Mechanism
An interaction between participants that gathers [submissions](#submittable) and resolves
them into an [outcome](#outcome).

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Outcome
A pre-approved action to be enacted — the product of resolving a
[mechanism](#mechanism).

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Resolvable
The [mechanism](#mechanism) contract itself: it collects a set of
[Submittables](#submittable) and produces a set of [outcomes](#outcome).

<sub>Source: CAP `cap-core` design (no external source).</sub>

### Submittable
An input a party submits to a [mechanism](#mechanism) for resolution (e.g. a
[bid](#auctionbid), a [ballot](#ballot)).

<sub>Source: CAP `cap-core` design (no external source).</sub>

## Governance

Governance is the processes and rules by which a group of participants makes,
executes, and is held accountable for collective decisions — decisions that
bind the group because the rules were followed. A governance system must define:

1. **Electorate** — who holds [voting rights](#voting-right) on a proposal,
   and with what weight (e.g. one-per-head, stake-weighted, role-based).
   *Who.*
2. **Action space** — what a [proposal](#proposal) may decide and its
   [outcome](#outcome) may enact (e.g. parameter changes, upgrades, spending,
   admitting or removing members). *Over what.*
3. **Proposal rights** — who may be a proposer: put a [proposal](#proposal)
   to the [electorate](#electorate), and withdraw it before voting opens.
   *Who asks.*
4. **Resolver** — who may resolve an open proposal: submit the cast
   [ballots](#ballot) to the [tally](#tally) and close the vote. *Who
   closes.*
5. **Executor** — who may execute the approved [outcome](#outcome). *Who
   enacts.*
6. **Tally** — how the cast [ballots](#ballot) become a verdict — approved,
   rejected, or lapsed: [quorum](#quorum), [threshold](#threshold),
   weighting, tie-breaking. *How decided.*
7. **Execution** — turning an approved verdict into a binding effect, often
   only after a [timelock](#timelock) so affected parties can react to the known
   outcome. *How it becomes real.*
8. **Delegation** — acting in another's name: a [vote](#vote) cast by a
   delegate, or voting-right issuance delegated to a registrar. *In whose
   name.*
9. **Privacy model** — who can see what: ballots, votes, voters, results.
   *Who sees.*
10. **Lifecycle** — the temporal frame: the voting window, the
    [timelock](#timelock), expiries. *When.*
11. **Accountability** — what verifiable record the process leaves: that a
    vote happened, who could participate, what was decided, and that
    execution matched the verdict. *How it is proven.*

What does cap-core model ? TODO: fill it here but then move it for the correct file

<sub>Sources: [Governance (Wikipedia)](https://en.wikipedia.org/wiki/Governance);
[G20/OECD Principles of Corporate Governance 2023](https://www.oecd.org/content/dam/oecd/en/publications/reports/2023/09/g20-oecd-principles-of-corporate-governance-2023_60836fcb/ed750b30-en.pdf);
[ENS Governance Process](https://docs.ens.domains/dao/governance/process/);
[Open Problems in DAOs (arXiv)](https://arxiv.org/pdf/2310.19201).</sub>

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Ballot
The container a voter [casts](#cast) into exactly one [proposal](#proposal):
it records who voted and when, and carries the [vote](#vote) as its content.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Delegation
TODO:

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Electorate
TODO:

<sub>Source: CAP `cap-governance` design (no external source).</sub>


### Cast
The act of submitting a [ballot](#ballot) to an open [proposal](#proposal), spending the voter's [voting right](#voting-right).

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Proposal
TODO:

<sub>Source: CAP `cap-governance` design (no external source).</sub>


### Quorate tally
A [tally](#tally) whose decided [verdicts](#verdict) are each triggered only
by a [quorum](#quorum): a count of [ballots](#ballot) actually present,
denominated in the [electorate](#electorate), never a
[threshold](#threshold) over turnout.

<sub>Source: CAP `cap-governance` design; "quorate" is the standard English
adjective for meeting quorum.</sub>

### Quorum
A support floor a [proposal](#proposal) must reach to pass, measured against the
*eligible electorate* rather than turnout — so low participation cannot lower the
bar. It may be **absolute** (e.g. at least 8 yes-votes) or **relative** (e.g. 40%
of eligible voters). Contrast a [threshold](#threshold), which is
measured against the votes actually *cast*, not the electorate.

<sub>Source: CAP `cap-governance` design; standard voting-theory term.</sub>

### Threshold
The share of the votes actually *cast* that the winning option must exceed — a
**majority** at 50%, or a **super-majority** above it (e.g. two-thirds). Measured
against turnout, unlike a [quorum](#quorum), which is measured against the whole
electorate.  

<sub>Source: CAP `cap-governance` design; standard voting-theory term.</sub>

### Tally
TODO:

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Timelock
A delay between a [proposal](#proposal)'s approval and the enacting of its
[outcome](#outcome), during which the parties it affects can react to the
now-known result before it becomes binding.

<sub>Source: CAP `cap-governance` design; "timelock" in the sense of OpenZeppelin's
[`TimelockController`](https://docs.openzeppelin.com/contracts/5.x/api/governance)
— a mandatory delay before a governance action takes effect.</sub>

### Verdict
The result the [tally](#tally) produces for a [proposal](#proposal):
**approved**, **rejected**, or **lapsed** (the vote never became definitive).
Approved and rejected are the *decided* verdicts; a lapsed proposal decides
nothing.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

### Vote
The value a [ballot](#ballot) carries: the option the voter chose (or none, for a
blank ballot). The vote is content; the ballot is the container that holds it.

<sub>Source: CAP `cap-governance` design (no external source).</sub>

## Auctions

An auction is a [mechanism](#mechanism) where one or more sellers allocate one
or more items among **bidders** by a fixed set of rules that determine,
from their bids, **who wins and what they pay** — settled by exchanging the item
for payment. An auction must define:

1. **Seller** — Owns the item being auctioned.
2. **Operator roles** — who is authorised for each step, held by one
   party or split across several for trust minimisation: *collect* the bids,
   *resolve* them into a winner and price, *settle* the exchange, and *abort*
   before close (e.g. a registry collects, an operator resolves, the winner
   triggers settlement). *Who runs each step.*
2. **Item** — The thing being auctioned (e.g. a single unit, a bundle, multiple units).
3. **Bidders** — Who can submit bids in the mechanism.
4. **[Bid](#auctionbid)** — a buyer's offered price; on-chain, a *funded*
   commitment rather than a mere signal.
6. **Reserve** — the minimum the sellers accepts for some items.
7. **Assignment rule** — How the item is allocated (e.g. highest bid, or a multi-unit /
   combinatorial split).
8. **Pricing rule** — How much does the bidder pay for a certain item (e.g. own bid,
   second price, uniform price).
9. **[Escrow](#escrow)** — How are bids locked so the sale happens according to the allocation rule.
   cannot renege and the seller cannot pull the item.
11. **[Settlement](#settlement)** — the atomic exchange: item → winner, payment →
    seller, fees → agent, refunds → losers.
12. **Lifecycle** — The timings for each actions (e.g the bidding window, the close)
13. **Privacy Model** — Who can see what (e.g. sealed-bid, open-bid).

What does cap-auction model ? TODO: fill it here but then move it for the correct file


<sub>Sources: [Auction theory (Wikipedia)](https://en.wikipedia.org/wiki/Auction_theory);
[Ausubel, *Auction Theory* (CMU/Palgrave)](https://www.cs.cmu.edu/~sandholm/cs15-892F15/Ausubel_Auction_Theory_Palgrave.pdf);
[a16z — How Auction Theory Informs On-Chain Implementations](https://a16zcrypto.com/posts/article/how-auction-theory-informs-implementations/);
[UCC § 2-328 Sale by Auction (LII)](https://www.law.cornell.edu/ucc/2/2-328).</sub>

### Assignment rule
TODO:

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

### AuctionBid
The offer a buyer submits in an [auction](#auction): the price they are willing to
pay for the item, backed by an [escrow](#escrow).

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

### Clearing
TODO:

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

### Escrow
Money set aside and locked when a [bid](#auctionbid) is placed, held until the
[auction](#auction) resolves — paid to the seller if the bid wins, or returned to
the bidder if it loses.

<sub>Source: CAP `cap-auctions` design (no external source).</sub>

### Lot
TODO:

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
