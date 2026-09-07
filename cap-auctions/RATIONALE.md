<!-- SPDX-License-Identifier: Apache-2.0 -->

# Rationale — cap-auctions

[`DESIGN.md`](DESIGN.md) describes what cap-auctions is. This file records why the
interfaces have the shape they do. Decisions inherited from the core are in the
root [`RATIONALE.md`](../RATIONALE.md).


## OneLotBid

### Why OneLotBid and not Bid

`OneLotBid` covers one lot from one seller. `OneLotAuctionTerms` names a single
`lot : LotSpec`, a single `payment : InstrumentId` and one pair of seller
accounts, and a bid is a list of `Quote`, each a price and a quantity for that
lot.

A general `Bid` that prices bundles across lots was considered and not built. The
checks in the fixed bodies only work when a quote is a price for one instrument:

- `wellFormedQuotes` requires the quotes to be non-empty, distinct in price and
  positive. This assumes a quote is a price for one instrument.
- `meetsReserve` compares a price against `terms.reserve`, which is a single
  `Decimal`.
- `awardWithinQuotes` requires that the quantity awarded at a price does not
  exceed the quantity the bid offered at that price or better, computed by
  `quantityAt`. This is what stops an auctioneer awarding a bidder more than the
  bidder offered.

None of the three carries over. In a combinatorial auction a bid of 100 for the
bundle `{A, B}` gives no price for `A` on its own, so whether an award is within a
bid is a question about bundles and belongs to the format. A reserve per bundle
does not compose into a reserve per lot either.

The cost of the narrow interface is limited to the bid. `Submittable`, `Resolver`
and `Settlement` do not mention a lot, so a bundle-bid interface added later
reuses them and duplicates only `OneLotBid`. The name carries the restriction so
it appears when someone writes `interface instance OneLotBid`, not when a check
fails at runtime.

#### Several lots and several sellers

A sale of several lots, from several sellers, needs no new interface if the format
models a bid as one `OneLotBid` contract per lot, all naming the same
`Mechanism`. Whether that is the right model is the format's choice, and the
alternative is not expressible — see below.

Currently, nothing ties a resolution to a single set of terms. `fetchSubmittables` requires
every presented submittable to fetch against the same `Mechanism` — resolver key,
resolver cid, procedure and id — and `Mechanism` names no lot. `OneLotBid_Award`
checks each bid against its own `bv.terms`. So:

- Each bid carries its own `lot`, `reserve`, seller accounts and `RegistryCalls`,
  which is what makes several sellers and several registries work.
- The procedure groups the presented bids by `terms.lot` and prices each group.
- A shared `terms.saleId` puts them under one `SettlementInfo`, because
  `saleSettlement` builds it from `terms.saleId`. One `Settlement` then carries a
  batch per registry.
- The award is atomic, because all the bids are resolved in one
  `Resolver_Resolve`.

What this does not express is anything that spans lots, because separate bids are
won independently:

- Bundles. "A and B together or neither" cannot be said, so a bidder can win a
  subset it did not want.
- Substitutes. "At most one of A or B" cannot be said either.
- A budget across lots. Each bid funds its own allocation, so bidding on ten lots
  locks funds for ten.
- All-or-nothing withdrawal. A bidder withdraws per lot.

Some of that can be recovered without a new interface. A constraint across lots
can be enforced by the procedure rather than by the bid: if a bidder's bids carry
a shared marker in `meta`, the procedure can refuse to award more than one of
them. The rule is readable, because the procedure is named in the `Mechanism` and
its code is in the resolver's package. What cannot be recovered is the funding —
each bid still locks its own allocation.

#### One contract per bid

The section above assumes one contract per lot. A format may instead want one
contract per bidder covering the whole sale, and that is not expressible:
`OneLotBidView` carries a single `terms.lot`, `Quote` has no lot reference, and a
template implements `OneLotBid` once.

Reasons a format may want it, none of which involve bundles:

- Fewer contracts to create, disclose and archive.
- One allocation for the whole bid instead of one per lot.
- One unit for withdrawal, expiry and release.

So there are two separate reasons to add a bid interface beside `OneLotBid`: bids
that span lots, and bids that cover several lots in one contract. The first
changes what can be checked — `awardWithinQuotes` has no bundle form, and a
bundle bidder's exposure is the largest bundle it might win rather than the sum
of its bids. The second is a packaging change and keeps every check, in a
per-lot form.

### Direction

`Direction` is `Forward` or `Reverse`. It is read in two places:

- `acceptablePrice`, which orders prices: higher is better forward, lower is
  better reverse. `meetsReserve` and `awardWithinQuotes` are built on it, and
  both run in the fixed bodies of `OneLotBid_Submit` and `OneLotBid_Award`.
- `paymentLeg` and `lotLeg` in `Cap.Auctions.Utils`, which decide which side of
  each leg the seller is on.

Reverse auctions use the same interface because nothing else differs between the
two.

Leaving the direction to implementations was considered. 
Dropping it moves `meetsReserve` and `awardWithinQuotes` into
`oneLotBid_submitImpl` and `oneLotBid_awardImpl`, because neither check can order
prices without it. The enum stays because two constructors are the complete set of orderings on a
scalar price, and because a bidder reading the view learns which one applies
without reading the format's code. It doesnt cost much to implementations that
supports only one direction. 

The limit is that a format wanting a different notion of "better price" (e.g.
multi-attribute score) cannot express it. Such a format needs more than a
price in `Quote`, so it is outside this interface anyway.

### Allocations on the bid

A bid names an allocation, it does not create one. `paymentAllocation` and
`lotAllocation` are `Optional (ContractId Allocation)`, funded by the bidder in a
transaction of its own before the bid is submitted.

Funding is a separate transaction by the bidder and the bid holds a reference to the allocation. 

### When escrow amount differs from transfered amount

The interface does not require the award to equal what the bid funded.

However, `SettlementFactory_SettleBatch`
requires implementations to ensure that "the `transferLegs` cover exactly the
transfers authorized by the allocations", and `TransferLegSide` carries an
`amount`. In some formats the amount allocated and the amount effectively transfered differ (e.g. uniform price, second price)
Three ways to ensure this exist, and the choice is up to the implementation:

- A refund leg in the same batch. The bidder's allocation sends exactly what it
  bid, and a seller-to-bidder leg returns the difference, backed by the seller's
  own allocation. Both cover exactly. The seller has to allocate a refund whose
  amount is not known until the price is.
- Iterated settlement. `Allocation_Settle` carries `extraTransferLegSides` and
  `nextIterationFunding`, which the standard permits only when the allocation's
  authorizer enabled iterated settlement. This is the standard's ceiling
  mechanism, and it gives the executors discretion over the extra legs.
- Funding after the price is known, which makes the award refusable by the
  winner.


### Releasing a losing bid

`OneLotBid_Release` returns a losing bidder's allocation. It admits actors against
`availableActions` under `BA_Release` and hands over to `oneLotBid_releaseImpl`.
Both examples drive it from their award flow to cancel the losers' allocations.

But a losing bid's funds stay locked until someone exercises it, so the format can chose which authority releases the funds.   

### What cannot be hidden

The DAML ledger model allows `quotes` and the allocation contract ids to be hidden from other bidders, 
but to hide them from the auction's authority it might be necessary extra cryptography.

## Settlement

### Why Settlement is still an interface

Each format writes its own settlement template. The settle body is already fixed,
so what a format supplies is the signatories and observers from its own fields, and
`settlement_cancelImpl`.

### Token Standard settlement

`Settlement_Settle` exercises `SettlementFactory_SettleBatch` on the registry's
factory, once per batch on the view. cap-auctions defines no asset type:
`InstrumentId`, `Account`, `Allocation`, `TransferLeg`, `FinalizedAllocation` and
`SettlementInfo` are Token Standard types.


### One settlement contract per SettlementInfo

`SettlementView` carries one `settlement : SettlementInfo` and a list of batches,
and the fixed body passes that same `v.settlement` to every batch, with
`actors = v.settlement.executors`. cap-auctions therefore forces one `Settlement`
contract per `SettlementInfo`: one settlement id and one executor set per
contract.

### The settle body has no hook

`Settlement_Settle` checks the window, admits the actors against `EA_Execute` in
`core.availableActions`,
requires every batch to move at least one leg, and then makes the batch calls
itself. There is no `*Impl` method in that path. `settlement_cancelImpl` is
delegated, because releasing allocations is registry-specific and cancelling
cannot move assets to a counterparty.

The settle call has no degrees of freedom a format needs. `SettlementInfo`, the
legs, the allocations, the factory cid and its `ExtraArgs` are all data on the
view, chosen by the format when it creates the contract. A hook would not let a
format do anything new. Either way the Bids interfaces do not depend on the settlement interface.

Three things stay fixed as a result:

- What settles is what the view published. `v.settlement` and `v.batches` reach
  the factory unchanged.
- Every batch settles in one transaction, because the body iterates `v.batches`.
  An implementation could present a subset of the allocations and settle only
  some legs, since the exact-cover rule constrains the legs against the
  allocations presented rather than against the view.

The bidder's and seller's funds are protected by the Token Standard.


### Executors

`core.availableActions` is on the view before bidding opens, so a bidder can see
which parties will be able to move a funded allocation: `EA_Execute` names who
may settle it, `EA_Cancel` who may release it. `Settlement_Settle` passes
`v.settlement.executors` to the factory rather than the caller's `actors`.

A format that names a party to the trade as an executor lets that party withhold
settlement after the award. That is the format's choice; the interface publishes
who may settle so it can be seen.

## RegistryCalls

`RegistryCalls` holds four factory calls — `paymentAllocate`, `paymentSettle`,
`lotAllocate` and `lotSettle` — each a factory contract id plus the `ExtraArgs`
that registry needs. Carrying them as data on `OneLotAuctionTerms` lets a sale
span two registries without either interface knowing what a registry is.

`cap-auctions-registry` is its own package because `Interfaces/bid` and
`Interfaces/settlement` both import it.

The factory ids are fixed when the terms are written. A registry that replaces its
factories invalidates open sales, currently the interfaces do not supply an alternative path for when that is the case.

## Package dependencies

`OneLotBid` does not mention a settlement and `Settlement` does not mention a bid.
They share the Token Standard types and the format that implements both.

An auction can therefore be priced by one package and settled by another, and a
settlement can be written for a bid shape this release does not define.
