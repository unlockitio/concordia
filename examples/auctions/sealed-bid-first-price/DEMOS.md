# Demos — `examples/auctions/sealed-bid-first-price`

Demos showing what a sealed-bid first-price auction with ex-post bid privacy
when the operator is trusted with correctly spending the allocation. 
In this example the operator is trusted to correctly resolve the auction and to correctly spend the allocations. 

The auction runs over a fixed set of invited bidders, named on the lot before it
locks. Those bidders observe the lot, so each is an informee of
`Resolver_Resolve` and reads the bid presentation. Bid privacy *among bidders*
is guaranteed after settelment and the winner and higher bid are also kept private.


## Method

The demos are Daml Script tests exercised against two independent token
registries: Canton Coin (Amulet) settles the payment leg, and a simulated
registry built on `TestTokenV2` issues the lot. A single registry administering
both instruments would leave the cross-registry atomicity of `Settlement_Settle`
untested — the payment and lot batches move through different registries in one
transaction.

Visibility claims take two forms. Single claims are `sees` / `cannotSee`
predicates over a party and one contract, so each note in the diagrams below is
one line of test code. These checks cannot catch is divulgence. 
That can be checked in Daml Studio. The other two claims are proved with assertions.

The test harness is taken from the Splice repository
(`github.com/canton-network/splice`, under `token-standard/`). Since Daml Script
cannot be distributed across SDK versions in a DAR, the harness is vendored
as source under `test/daml/Splice/`. `Testing.Utils`,
`Registries.AmuletRegistry.Parameters` and `TokenStandard.RegistryApiV2` are
copied verbatim; `Registries.AmuletRegistryV2` and
`Registries.TestTokenV2_RegistryV2` are reduced to their V2 surface, removing
a dependency on the wallet client and on the V1 API.

## Fixture

`setupAs` allocates the six parties, stands up both registries, and builds one
`OneLotAuctionTerms`: the operator as sole authority, `basicAccount seller` for
both seller legs, a single Widget as the lot, no reserve, the four registry calls
pinned, and a three-day timeline — `entryClosesAt` at day 1, `biddingClosesAt`
at day 2, `expiresAt` at day 3. Party names are namespaced per demo, so no two
demos share a ledger contract.

`openAuction` then opens one auction on those terms. The seller mints and
allocates its lot and payment allocations, the operator proposes
`AuctionLotProposal`, and the seller accepts it into an `AuctionLot` — which is
the resolver, so the mechanism is pinned to that contract id. The operator
then creates one empty `AuctionBid` seat per invited bidder. Nothing has been
bid yet; the demos take it from there, opening bidding with
`setTime f.terms.entryClosesAt` and resolving after
`setTime f.terms.biddingClosesAt`. Resolving mints an `AuctionSettlement`; the
assets move in a **second** transaction, so the demos use `resolveAndSettle`,
which runs the resolve and then `Settlement_Settle` on what it minted.
`openAuctionWith` is the same as `openAuction`, with a hook to rewrite the terms
first — `theOperatorCannotSwapTheRegistry` uses it to name a hostile factory.


## Security claims

Each demo carries one claim. Dispatched here:

| Test | Security claim |
| --- | --- |
| [`whoSeesWhat`](#1-whoseeswhat) | A losing bidder reads the bid presentation but cannot read the quote in it — bid privacy survives the outcome |
| [`lotGoesToTheHighestPresentedBid`](#2-lotgoestothehighestpresentedbid) | The lot goes to the highest presented bid at that bidder's own quoted price, and every loser is made whole in the same transaction |
| [`theOperatorCannotSwapTheRegistry`](#3-theoperatorcannotswaptheregistry) | Settlement cannot be redirected: the registry calls are pinned in the terms when the auction is constituted, not chosen at settlement |
| [`theBidderCannotBlockTheSale`](#4-thebiddercannotblockthesale) | Neither the winner nor a loser holds a veto at settlement time |

A version whose contracts carry higher guarantees: the operator can
neither drop a bidder nor incorrectly spend allocations the settlement can be found in
[`../sealed-bid-first-price-high-trust`](../sealed-bid-first-price-high-trust/DEMOS.md).


## 1. `whoSeesWhat`

One happy-path auction over three invited bidders, two of whom bid. After every
phase the demo asserts each party's **complete** visible set. This demo covers
the whole privacy guarantee at once, and follows the sale through to settlement.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol

    Note over S,C: Phase 1 — the invited bidders, then the lot
    O->>O: AuctionLot — invited = A, B, C
    Note over A,C: each observes the resolver, the seller does not
    O->>S: AuctionLotProposal
    S->>S: Accept — locks the lot in an allocation, executors = [O]
    Note over S: sees its own lot allocation, and nothing else

    Note over S,C: Phase 2 — bidding, after entryClosesAt
    A->>O: locks 100 against the sale's settlement, submits 100
    B->>O: locks 60 against the same settlement, submits 60
    Note over S: unchanged — no quote, no bidder, no amount
    Note over A,B: neither can see the other's seal or quote
    Note over O: sees both quotes in full 
    Note over C: her empty seat stays live

    Note over S,C: Phase 3 — resolve, after biddingClosesAt
    O->>O: presents A's bid, B's bid, and C's empty seat
    Note over A,C: each is an informee — each finds its own entry
    O->>O: mints AuctionSettlement — the two batches, off the terms
    O-->>B: seal cancelled, 60 back to B
    Note over S,C: nothing has moved yet

    Note over S,C: Phase 4 — Settlement_Settle
    O->>S: payment batch — 100 from A to S
    O->>A: lot batch — the lot from S to A
    Note over S,C: ✓ both legs, one transaction, two registries
    Note over B: learns it did not win, and that all three were presented
    Note over B: learns no quote — the verdict carries no value
```


## 2. `lotGoesToTheHighestPresentedBid`

The lot goes to the highest bid presented, at that bidder's own quoted price,
and every loser is made whole. The winner is **computed** in the resolve as the
maximum of the presented quotes, not supplied by the operator.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice
    participant B as Bob

    A->>O: submits 100
    B->>O: submits 60
    Note over S,B: bidding is closed, the lot is locked

    O->>O: Resolver_Resolve — presents both bids and Carol's seat
    O->>O: award at 100, the highest presented — first price, not second
    O-->>B: seal cancelled, 60 back, B pays nothing

    O->>O: Settlement_Settle
    O->>S: 100 in, lot out
    Note over S,B: ✓ both legs together

    O->>O: resolve again
    Note over O: ✗ the lot and its allocation are both spent
```


## 3. `theOperatorCannotSwapTheRegistry`


A `SettlementFactory` view is self-asserted: any party can create a template
whose `view.admin` names someone else. The factory call contract id and choice context both are pinned in the auction terms,
and the award reads it from there.


```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice

    Note over S,A: an impostor factory claims the lot admin
    O->>O: create ImpostorSettlementFactory, view.admin = lot admin
    Note over O: ✓ SettlementFactory_PublicFetch returns the lot admin — the view lies
    Note over O: ✗ but it is not the cid the terms name

    Note over S,A: an honest auction on honest terms
    O->>O: Resolver_Resolve — reads both factories off the terms
    Note over S,A: ✓ 100 to S, lot to A

    Note over S,A: an auction whose TERMS name the impostor
    S->>S: signs AuctionLot over those terms
    A->>A: signs AuctionBid over those terms
    O->>O: Resolver_Resolve
    Note over S,A: ✗ payment moves, lot does not — A pays for nothing
```



## 4. `theBidderCannotBlockTheSale`

Alice tries to abort the sale trough all possible choices and it fails in all of them.

```mermaid
sequenceDiagram
    participant O as Operator
    participant A as Alice

    A->>A: bid submitted — A signs AuctionBid, 100 committed
    Note over A: bidding is closed

    A->>A: archive AuctionBid alone
    Note over A: ✗ Archive needs every signatory, and the operator is one
    A->>A: OneLotBid_Withdraw
    Note over A: ✗ the actors are entitled to withdraw — the seat offers no such action
    A->>A: Allocation_Cancel on its own payment allocation
    Note over A: ✗ actors does not have the same elements as one of allowed actors
    A->>A: Allocation_Withdraw on its own payment allocation
    Note over A: ✗ cannot withdraw a committed allocation before its settlement deadline

    O->>O: Resolver_Resolve — A is offline
    Note over O,A: ✓ 100 to the seller, the lot to A
```

