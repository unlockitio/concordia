# Demos — `examples/auctions/sealed-bid-first-price-high-trust`

6 demos showing the security and privacy guarantees of a sealed-bid first-price
auction with ex-post bid privacy using CAP.

This is the stronger of the two auction examples. The smaller one,
[`../sealed-bid-first-price`](../sealed-bid-first-price/DEMOS.md), trusts the
operator with the assets and with the presentation; this one does not.

The auction runs over a fixed set of invited bidders, named on the resolver
before the lot locks. Those bidders observe the resolver, so each is an informee
of `Resolver_Resolve` and reads the presentation — which is what lets the award
require that every invited bidder appears in it. Privacy after the outcome is
computed is kept: a loser reads the presentation and still reads no quote in it.

What is left of the operator's discretion is breaking exact ties, seeing every
quote while bidding is open, and declining to resolve at all. Its liveness role
is bounded by `expiresAt`; it cannot rig the outcome.

## Method

The demos are Daml Script tests exercised against two independent token
registries: Canton Coin (Amulet) settles the payment leg, and a simulated
registry built on `TestTokenV2` issues the lot. A single registry administering
both instruments would leave the cross-registry atomicity of demo 4 untested —
the payment and lot batches move through different registries in one
`Settlement_Settle`.

Visibility claims take two forms. Single claims are `sees` / `cannotSee`
predicates over a party and one contract, so each note in the diagrams below is
one line of test code. These checks cannot catch is divulgence. 
That can be checked in Daml Studio. 

The test harness is taken from the Splice repository
(`github.com/canton-network/splice`, under `token-standard/`). Since Daml Script
cannot be distributed across SDK versions in a DAR, the harness is vendored
as source under `test/daml/Splice/`. `Testing.Utils`,
`Registries.AmuletRegistry.Parameters` and `TokenStandard.RegistryApiV2` are
copied verbatim; `Registries.AmuletRegistryV2` and
`Registries.TestTokenV2_RegistryV2` are reduced to their V2 surface, removing
a dependency on the wallet client and on the V1 API.

## Security claims

Each demo carries one claim. Dispatched here:

| Test | Security claim |
| --- | --- |
| [`whoSeesWhat`](#1-whoseeswhat) | No party learns a quote it is not entitled to: the seller learns nothing while bidding is open, and a loser reads the presentation without reading a quote in it |
| [`lotCanOnlyBeReleasedBySellerAndOperatorJointly`](#2-lotcanonlybereleasedbysellerandoperatorjointly) | The locked lot is released by the seller and the operator together or not at all — neither holds it alone |
| [`lotGoesToTheHighestPresentedBid`](#3-lotgoestothehighestpresentedbid) | The winner is computed from the presented quotes, not supplied: the lot goes to the highest bid at that bidder's own price, and every loser is made whole |
| [`noPaymentWithoutTheLot`](#4-nopaymentwithoutthelot) | The winner pays only in the transaction that delivers the lot — the two legs cannot be prised apart, across two independent registries |
| [`theOperatorCannotSwapTheRegistry`](#5-theoperatorcannotswaptheregistry) | Settlement cannot be redirected or repriced: the registry calls are pinned in the countersigned terms, and `Settlement_Settle` settles from the settlement's own view, ignoring whatever the caller passes |
| [`theAuctionResolvesOnTheInvitedSetAlone`](#6-theauctionresolvesontheinvitedsetalone) | The auction resolves only on the set of bidders who took their seats — none dropped, none added — and no entry in that set can be forged, by the operator alone or with the seller |


## 1. `whoSeesWhat`

One happy-path auction over three invited bidders, two of whom bid. After every
phase the demo asserts each party's **complete** visible set. This demo covers
the whole privacy guarantee at once.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol

    Note over S,C: Phase 1 — the invited bidders, then the lot
    O->>O: AuctionLot — the resolver, invited set still empty
    Note over A,C: each observes the resolver — the seller does not
    O->>S: AuctionLotProposal
    S->>S: Accept — checks the pinned resolver, then locks the lot in an allocation
    O->>A: AuctionInvitation
    O->>B: AuctionInvitation
    O->>C: AuctionInvitation
    A->>A: Accept — a typed fetch pins the resolver, takes a seat
    B->>B: Accept — takes a seat
    Note over C: never accepts — her invitation stays live
    Note over S: sees its own lot allocation, and nothing else

    Note over S,C: Phase 2 — bidding, after entryClosesAt
    A->>O: locks a seal, submits 100
    B->>O: locks a seal, submits 60
    Note over S: unchanged — no quote, no bidder, no amount
    Note over A,B: neither can see the other's seal or quote
    Note over O: sees both quotes in full — the one party trusted for confidentiality

    Note over S,C: Phase 3 — resolve, after biddingClosesAt
    O->>O: presents A's bid, B's bid, and C's unused invitation
    Note over A,C: each is an informee — each finds its own entry
    O->>S: settlement — winner is A, price is 100
    O-->>B: seal released
    Note over B: learns it did not win, and that all three were presented
    Note over B: learns no quote — the verdict carries no value

    Note over S,C: Phase 4 — settlement
    O->>O: Settlement_Settle — both legs, one transaction
    Note over S: lot out, 100 in
    Note over A: lot in, 100 out
    Note over B: still nothing about A's quote
```

The one observer outside this picture is the registry admin, who sees each
locked amount. Privacy here is structural, not bought with uniform allocations.

Quote privacy survives observation because projection in Daml is **per node**,
not inherited: the prices are reached by `fetch` on contracts whose only
stakeholders are the operator and one bidder, so an informee of the parent
exercise sees no part of those nodes. It holds only while the verdict stays
value-free — `V_Accepted (Optional AnyValue)` sits in the exercise *result*,
which every informee reads. `resolveFirstPrice` returns `V_Accepted None`, and
in this format that is a rule, not an incidental.

## 2. `lotCanOnlyBeReleasedBySellerAndOperatorJointly`

The lot sits in a committed Token Standard V2 allocation whose executors are
the seller and the operator. V2 requires the **full** executor set to cancel,
so neither can release it alone and the seller cannot spend it while it is
locked.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator

    S->>S: lot locked — executors are the seller and the operator

    O->>O: cancel alone
    Note over O: ✗ rejected

    S->>S: cancel alone
    Note over S: ✗ rejected

    O->>S: cancel together
    Note over S,O: ✓ the lot returns to the seller, nobody pays
```

The guarantee is Token Standard V2's, not CAP's: a committed allocation is
only as unabortable as its executor set is hard to assemble.
Either the seller or the operator must want the resolution to happen.

## 3. `lotGoesToTheHighestPresentedBid`

Once the operator resolves, the lot goes to the highest bid presented, at that
bidder's own quoted price. The winner is **computed** in the resolve as the
maximum of the presented quotes rather than supplied by the operator, so there
is no proposed answer for the checks to disagree with.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice
    participant B as Bob

    A->>O: submits 100
    B->>O: submits 60
    Note over S,B: bidding is closed

    O->>O: Resolver_Resolve — presents both bids
    O->>A: award at 100, the highest presented
    O-->>B: seal released, B pays nothing
    Note over S,B: the lot allocation's executors are now the seller, the operator and A

    O->>S: cancel the lot allocation together
    Note over S,O: ✗ the winner is an executor now too
    O->>B: award to B instead
    Note over O: ✗ nothing is left to award
```

## 4. `noPaymentWithoutTheLot`

The winner pays only in the transaction that delivers the lot.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice

    Note over S,A: after the award — two legs, one settlement
    O->>O: Settlement_Settle
    O->>S: payment leg — 100 from A to S
    O->>A: lot leg — the lot from S to A
    Note over S,A: ✓ one transaction, both legs or neither

    O->>S: settle the payment leg alone
    Note over S,O: ✗ the winner is an executor of the settlement
    O->>O: settle with the lot leg failing
    Note over S,A: ✗ nothing moves, A does not pay
    O->>A: settle legs of the operator's choosing
    Note over O: ✗ every leg comes from the settlement's own fields
```

Atomicity is Daml's: both settle-batch calls sit in one transaction, so a
failure in either moves nothing. The operator holds only the liveness role.

## 5. `theOperatorCannotSwapTheRegistry`

Which registry contracts may touch the assets is fixed in
`OneLotAuctionTerms.registries` when the auction is constituted, not chosen by
the operator at settlement.

A `SettlementFactory` view is self-asserted: any party can create a template
whose `view.admin` names someone else. Checking that field proves nothing, so
the factory call — contract id and choice context both — is pinned in the terms
and `Settlement_Settle` takes no registry argument at all.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice

    Note over S,A: an impostor factory claims the lot admin
    O->>O: create ImpostorSettlementFactory, view.admin = lot admin
    Note over O: ✓ SettlementFactory_PublicFetch returns the lot admin — the view lies
    Note over O: ✗ but it is not the cid the terms name

    Note over S,A: after the award — the operator tries to substitute it
    O->>O: real payment batch + impostor lot batch, in one submission
    Note over O: ✗ rejected — the winner is an executor of the payment batch
    O->>S: the same, operator and seller together
    Note over S,O: ✗ rejected — still no winner
    O->>O: the impostor batch alone, operator as its own actor
    Note over O: ✗ it settles no allocation
    Note over S,A: nothing moved: A still owes 100, the lot is still locked

    Note over S,A: settlement takes no factory argument
    O->>O: Settlement_Settle — reads the calls off the terms
    Note over S,A: ✓ both legs move, 100 to S, lot to A

    Note over S,A: an auction whose TERMS name the impostor
    S->>S: signs AuctionLot over those terms
    A->>A: signs AuctionBid over those terms
    O->>O: Settlement_Settle
    Note over S,A: ✗ payment moves, lot does not — A pays for nothing
```

The substitution attempt is what closed. Reaching the payment factory outside
`Settlement_Settle` needs the winner's authority, and the winner only ever
delegates it to `AuctionSettlement`, whose body settles both legs off the terms.
The impostor is still callable — it is the operator's own contract — but on its
own it settles no allocation, so it moves nothing.

The last branch is the residual trust, stated rather than hidden. Binding the
calls does not make a hostile registry harmless; it moves the decision to a
document the seller and every bidder sign before anything locks, and off the
operator's settle-time call — `Settlement_Settle` reads the factories off the
settlement's own view, so a repriced or redirected batch handed to it is simply
ignored. The demo asserts that both `AuctionLot` and `AuctionBid` carry the
terms naming the impostor, so nobody reached that state without countersigning
it.

## 6. `theAuctionResolvesOnTheInvitedSetAlone`

The count is an equality against the set of bidders who seated, so one line
refuses a missing entry and an extra one alike. Dropping is the cheaper attack —
an absence looks like a bidder who did not turn up — but both are the same
check, and the demo walks them together before showing that on the right set the
lot goes to the highest bid.

Every entry is unforgeable. A quoted `AuctionBid` carries its bidder's
signature; so does the unquoted seat, because a seat is only reachable by the
bidder accepting an `AuctionInvitation`, which the operator cannot mint alone —
it is signed by the operator *and* the seller. And the invited set itself is
frozen by `AuctionLot_FinalizeInvitation` **before bidding opens**, derived from
the seats it restamps rather than supplied alongside them.

```mermaid
sequenceDiagram
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol
    participant D as Dave

    Note over O,D: A, B and C seated — Dave never was
    O->>O: create a seat for Alice
    Note over O: ✗ a seat carries its bidder's signature
    O->>O: create a seat for Alice, with the seller co-signing
    Note over O: ✗ still — the bidder signs, and neither of them is Alice
    O->>O: create an AuctionInvitation for Dave
    Note over O: ✗ an invitation carries the seller's signature
    O->>O: create a quoted bid for Alice
    Note over O: ✗ a bid carries its bidder's signature

    A->>O: submits 100
    B->>O: submits 60
    Note over C: seated, never bid

    O->>O: present A + C — drop the losing bid
    Note over O: ✗ not every seated bidder is presented
    O->>O: present A + B — drop the bidder who never bid
    Note over O: ✗ not every seated bidder is presented
    O->>O: present A alone
    Note over O: ✗ not every seated bidder is presented
    O->>O: present C alone — lapse the sale, hiding both bids
    Note over O: ✗ not every seated bidder is presented
    O->>D: a seat Dave really signs, added to the full slate
    Note over O: ✗ Dave is not in the frozen set
    O->>O: present A twice, to pad the count
    Note over O: ✗ the presented submittables are not distinct
    O->>O: present a seat opened on another mechanism
    Note over O: ✗ contract group identifier mismatch
    O->>O: resolve under a mechanism naming another resolver
    Note over O: ✗ the declared mechanism is binded to another resolver contract
    O->>O: restamp a seat onto another mechanism, after bidding closed
    Note over O: ✗ a seat is restamped before bidding opens

    Note over O,D: nothing moved through any of it
    O->>O: present A + B + C
    Note over O: ✓ A wins at 100 — the highest of the three
```

The Dave branch is the sharp one. His seat is genuine — the operator, the seller
and Dave himself all signed it — and it still cannot be added, because `invited`
was derived from the seats present at finalization and Dave's was not among
them. Adding a bidder after the fact is not a matter of forging a signature; the
set is simply closed.

What remains is that the operator chooses which seats to finalize, so it can
leave a seated bidder out. That happens before bidding opens — no quote exists
yet — so the exclusion is blind, and the excluded bidder can see their seat was
never restamped. It reduces to refusing to seat someone, which no on-ledger rule
can prevent. The operator may also put a shill on the invited list before
anything locks, visible to the seller and to every bidder who signs those terms;
in a first-price auction it buys nothing, since a shill that wins pays its own
quote.
