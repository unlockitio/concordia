# Demos — `examples/auctions/sealed-bid-first-price`

Demos showing the security and privacy guarantees of a sealed-bid first-price
auction with ex-post bid privacy using CAP, where the operator is trusted to
correctly spend the allocations.

The auction runs over a fixed set of invited bidders, named on the resolver
contract before the lot locks. Those bidders observe the resolver, so each is an informee
of `Resolver_Resolve` and reads the bid presentation. Bid privacy among bidders, after the outcome is
computed is kept: a loser reads the bid presentation but cannot read the quote in it.

## Method

The demos are Daml Script tests exercised against two independent token
registries: Canton Coin (Amulet) settles the payment leg, and a simulated
registry built on `TestTokenV2` issues the lot. A single registry administering
both instruments would leave the cross-registry atomicity of the resolve untested.

Assertions take two forms. Visibility claims are stated per contract, as
`sees` / `cannotSee` predicates over a party and a contract identifier, so that
each note in the diagrams below corresponds to one line of test code. The same
per-party views can be inspected interactively in Daml Studio. Stronger claims —
that a party learned *nothing* across a phase — are stated as an equality
between the complete set of contracts visible to that party before and after.
That catches any contract the party becomes a **stakeholder** of, anticipated
or not. It does not catch divulgence that can be checked by inspecting Daml studio.

The invited bidders are `alice`, `bob` and `carol`. Carol is invited and never
takes her seat; `dave` is never invited.

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
both seller legs, 10 Widgets as the lot, no reserve, the four registry calls
pinned, and a three-day timeline — `entryClosesAt` at day 1, `biddingClosesAt`
at day 2, `expiresAt` at day 3. Demos
do not ledger contracts.

`openAuction` then opens one auction on those terms. The seller mints and
allocates the lot escrow and its payment receipt, the operator proposes
`AuctionLotProposal`, and the seller accepts it into an `AuctionLot` — which is
the resolver, so the mechanism is pinned to that contract id. The operator
then creates one empty `AuctionBid` seat per invited bidder. Nothing has been
bid yet; the demos take it from there, opening bidding with
`setTime f.terms.entryClosesAt` and resolving after
`setTime f.terms.biddingClosesAt`. `openAuctionWith` is the same, with a hook to
rewrite the terms first — `theOperatorCannotSwapTheRegistry` uses it to name a
hostile factory.

## Security claims

Each demo carries one claim. Dispatched here:

| Test | Security claim |
| --- | --- |
| [`whoSeesWhat`](#1-whoseeswhat) | A losing bidder reads the bid presentation but cannot read the quote in it — bid privacy survives the outcome |
| [`theOperatorCannotDropABidder`](#2-theoperatorcannotdropabidder) | An invited bidder cannot be omitted from the award: one entry per invited bidder, and neither a bid nor an empty seat can be forged |
| [`lotGoesToTheHighestPresentedBid`](#3-lotgoestothehighestpresentedbid) | The lot goes to the highest presented bid at that bidder's own quoted price, and every loser is made whole in the same transaction |
| [`theOperatorCannotAddABidder`](#4-theoperatorcannotaddabidder) | A party who was never invited cannot be made to appear in the award |
| [`theOperatorCannotSwapTheRegistry`](#5-theoperatorcannotswaptheregistry) | Settlement cannot be redirected: the registry calls are pinned in the terms when the auction is constituted, not chosen at settlement |
| [`theBidderCannotBlockTheSale`](#6-thebiddercannotblockthesale) | Neither the winner nor a loser holds a veto at settlement time |
| [`aSeatLeavesNoAllocationBehind`](test/daml/Cap/Examples/SealedFirstPrice/Test/Invariants.daml) | A seat that never bids leaves no allocation locked  |



## 1. `whoSeesWhat`

One happy-path auction over three invited bidders, two of whom bid. After every
phase the demo asserts each party's **complete** visible set. This demo covers
the whole privacy guarantee at once, and shows the sale completing in the
resolve.

```mermaid
sequenceDiagram
    participant S as Seller
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol

    Note over S,C: Phase 1 — the invited bidders, then the lot
    O->>O: AuctionResolver — invited = A, B, C
    Note over A,C: each observes the resolver, the seller does not
    O->>S: AuctionLotProposal
    S->>S: Accept — locks the lot in escrow, executors = [O]
    Note over S: sees its own lot escrow, and nothing else

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
    O->>A: award at 100 — names each escrow's leg in the settle batch
    O->>S: payment batch — 100 from A to S
    O->>A: lot batch — the lot from S to A
    O-->>B: seal cancelled, 60 back to B
    Note over S,C: ✓ all of it, one transaction
    Note over B: learns it did not win, and that all three were presented
    Note over B: learns no quote — the verdict carries no value
```

The demo asserts, immediately after the resolve, that the seller holds 100 and
no lot, that A holds the lot and no locked funds, that B is whole again.
It also asserts that B's entire visible set is now one contract: his own refunded money.


## 2. `theOperatorCannotDropABidder`

Omitting a bid is what a first-price operator would otherwise be free to do, and
it is the cheapest attack to hide: an absence looks like a bidder who simply did
not turn up. The award closes it by requiring one entry per invited bidder — a
bid that bidder signed, or the empty seat it never used. Neither can be forged:
the bid carries the bidder's signature, and the seat is checked against these
terms and this mechanism.

The check is in the body of `AuctionLot_Award`, where the seller's delegation is spent, not in who may
call it.

```mermaid
sequenceDiagram
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol

    A->>O: submits 100
    B->>O: submits 60
    Note over C: invited, never seated

    O->>O: present A + C — drop the losing bid
    Note over O: ✗ not every invited bidder is presented
    O->>O: present A + B — drop the bidder who never seated
    Note over O: ✗ not every invited bidder is presented
    O->>O: present A alone
    Note over O: ✗ not every invited bidder is presented
    O->>O: present A twice, to pad the count
    Note over O: ✗ the presented submittables are not distinct

    Note over O: the same check off the resolver
    O->>O: AuctionLot_Award directly — award B, hiding A
    Note over O: ✗ not every invited bidder is presented

    Note over O,C: nothing moved through any of it
    O->>O: present A + B + C
    Note over O: ✓ A wins at 100, and the sale settles
```

The direct call is the point. `AuctionLot` is signed by the operator and the
seller and its award is controlled by the operator alone — the seller must not
be able to abort after price discovery, so that cannot change. The operator can
therefore always reach the award without going through `Resolver_Resolve`.
Because the check lives in the body of the choice rather than in who may call
it, that is not a bypass: it either produces the outcome the procedure would
have produced, or produces nothing.

The check is an equality against the invited set, not a subset test, so it
rejects an extra entry as well as a missing one. Demo 4 covers that direction.

What remains is that the operator may put a shill on the invited list *before*
anything locks — visible to the seller and to every bidder who signs those
terms. In a first-price auction it buys nothing: a shill that loses does
nothing, and a shill that wins has to pay its own quote.

## 3. `lotGoesToTheHighestPresentedBid`

The lot goes to the highest bid presented, at that bidder's own quoted price,
and every loser is made whole in the same transaction. Both halves are checked
in `AuctionLot_Award`, where the seller's delegation is spent — not in the
procedure that calls it — so they hold however the award is reached.

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
    O->>A: award at 100, the highest presented — first price, not second
    O->>S: 100 in, lot out
    O-->>B: seal cancelled, 60 back, B pays nothing
    Note over S,B: ✓ one transaction

    O->>O: resolve again
    Note over O: ✗ the resolver, the lot and the escrow are all spent
```

The demo asserts the winner paid exactly her own quote (100, not the runner-up's
60), that the runner-up holds nothing locked and cannot see his own cancelled
allocation any more, and that the resolver, the lot and the escrow are gone.

## 4. `theOperatorCannotAddABidder`

The mirror of demo 2. Adding a bidder is stopped twice over, and the first layer
means the second is never reached.

```mermaid
sequenceDiagram
    participant O as Operator
    participant D as Dave
    participant C as Carol

    Note over O,C: Dave was never invited, Carol was invited and never seated
    O->>O: create AuctionBid for Dave, with a quote
    Note over O: ✗ a bid carries its bidder's signature
    O->>O: create AuctionBid for Carol, with a quote
    Note over O: ✗ invited is not the same as seated

    O->>D: an empty seat for Dave — the operator can make one alone
    O->>O: present A + B + C + Dave's seat
    Note over O: ✗ the presentation is not the invited set

    O->>O: Resolver_Resolve with the three real entries
    Note over O: ✓ A wins at 100, and the sale settles
```

A bid is signed by the authorities **and** its bidder, so the operator — an
authority on every bid in the auction — still cannot bring one into existence
alone. That holds for Carol too: being invited is not being seated, and her
empty seat is the only thing the operator may present for her.

The invited set itself is fixed before the lot locks and pinned by contract id
in the mechanism every seat is signed against, so it cannot be widened after
bidding opens without invalidating every seat already taken.

## 5. `theOperatorCannotSwapTheRegistry`

Which registry contracts may touch the assets is fixed in
`OneLotAuctionTerms.registries` when the auction is constituted, not chosen by
the operator when it settles.

A `SettlementFactory` view is self-asserted: any party can create a template
whose `view.admin` names someone else. Checking that field proves nothing, so
the factory call — contract id and choice context both — is pinned in the terms,
and the award reads it from there.

Collapsing execution into the resolve makes this stronger than in the
lower-trust variant rather than weaker. There, the operator held a settle-time
call it could aim at a factory of its own; here there is no settle-time call to
aim. `oneLotBid_awardImpl` takes no registry argument: it reads
`terms.registries.paymentSettle` and `terms.registries.lotSettle` off the terms
the seller and the bidder both signed, and checks each one's admin before using
it.

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

The last branch is the residual trust, stated rather than hidden — and it is not
the operator's to exploit. Binding the calls to the terms does not make a
hostile registry harmless; it moves the decision to a document the seller and
every bidder sign before anything locks. The demo asserts that both
`AuctionLot` and `AuctionBid` carry the terms naming it, so nobody reached that
state without countersigning it.

It also marks the edge of what "one transaction" buys. Atomicity holds between
the two batch calls; it cannot make a call that only *claims* to move an asset
actually move one. The impostor's `SettleBatch` settles no allocation and
returns success, so the payment leg commits beside a lot leg that did nothing.
Atomicity is a property of the transaction, not a substitute for knowing whose
registry you are settling through.

## 6. `theBidderCannotBlockTheSale`

A bidder is a signatory of its own `AuctionBid`, and both its escrow and the
seller's are unwound at the award. It is worth showing that none of that gives
the bidder a veto, because a format where the loser — or the winner — could
refuse at settlement time would be worthless.

The authority a bidder contributes to the resolve is not its presence. It is
the signature it left on `AuctionBid` when it bid, and an exercise on that
contract delegates it. The bidder is offline when the operator resolves: the
demo's `runResolve` acts as the operator and nobody else.

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
    A->>A: Allocation_Cancel on its own escrow
    Note over A: ✗ actors does not have the same elements as one of allowed actors
    A->>A: Allocation_Withdraw on its own escrow
    Note over A: ✗ cannot withdraw a committed allocation before its settlement deadline

    O->>O: Resolver_Resolve — A is offline
    Note over O,A: ✓ 100 to the seller, the lot to A
```

The last branch is the one that carries it, and the guarantee is the Token
Standard's. The registry's own `availableActions` does list
`AA_Withdraw -> [[A]]`: the bidder is the party who may withdraw. But the
allocation is `committed = True` with `settlementDeadline = Some expiresAt`, and
a committed allocation cannot be withdrawn before its deadline. The advertised
action names *who*, not *when*.

`OneLotBid_Submit` requires exactly those two properties of every bid allocation
(`bidAllocationFunding`, in `cap-auctions`): committed, and expiring with the
bid. That check is what makes a sealed bid binding rather than an offer.

So the liveness picture is one-sided and stays that way until `expiresAt`. Up to
that point only the operator can stop the sale, by declining to resolve or by
cancelling an escrow. After it, the commitment lapses for everyone: the escrows
become withdrawable by the parties who funded them, `Resolver_Expire` and
`OneLotBid_Expire` open, and nobody is left holding anything.
