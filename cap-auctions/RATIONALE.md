<!-- SPDX-License-Identifier: Apache-2.0 -->

# Rationale — cap-auctions

[`DESIGN.md`](DESIGN.md) describes what cap-auctions is. This file records why the
interfaces have the shape they do. Decisions inherited from the core are in the
root [`RATIONALE.md`](../RATIONALE.md).


## OneLotBid

### Why OneLotBid and not Bid

`OneLotBid` covers one lot from one seller. `OneLotAuctionTerms` names a single
`lot : Lot`, a single `payment : InstrumentId` and one pair of seller
accounts, and a bid is `[[Quote]]` — alternatives, each a demand schedule of
prices and quantities for that lot.

A general `Bid` that prices bundles across lots was considered and not built. The
checks in the fixed bodies only work when a quote is a price for one instrument:

- `requireQuotes` requires an alternative to be non-empty, distinct in price,
  positive, and no more units in total than `terms.lot.amount`. This assumes a
  quote is a price for one instrument.
- `meetsReserve` compares a price against `terms.reserve`, which is a single
  `Decimal`.
- `awardWithinQuotes` requires that the award fit inside one of the bid's
  alternatives: the quantity awarded at a price does not exceed the quantity that
  alternative offered at that price or better, computed by `quantityAt`. This is
  what stops an auctioneer awarding a bidder more than the bidder offered.

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

A bid that binds several lots together needs a new interface.

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
  both run in the fixed bodies of `OneLotBid_RequestAllocations` and
  `OneLotBid_Finalize`.
- `paymentLeg` and `lotLeg` in `Cap.Auctions.Utils`, which decide which side of
  each leg the seller is on.

Reverse auctions use the same interface because nothing else differs between the
two.

Leaving the direction to implementations was considered. 
Dropping it moves `meetsReserve` into `oneLotBid_finalizeImpl` and
`oneLotBid_requestAllocationsImpl`, because the check cannot order prices without
it. The enum stays because two constructors are the complete set of orderings on a
scalar price, and because a bidder reading the view learns which one applies
without reading the format's code. It doesnt cost much to implementations that
supports only one direction. 

The limit is that a format wanting a different notion of "better price" (e.g.
multi-attribute score) cannot express it. Such a format needs more than a
price in `Quote`, so it is outside this interface anyway.

### Identity is carried by Mechanism

`OneLotAuctionTerms` names what is being sold and on what schedule. It does not
name the sale or the authority set, because `Mechanism` already does and every
`OneLotBid` carries one — the interface requires `Submittable`. The sale is
`mechanism.id` and the authority set is `mechanism.resolver.authorities`.

### Quotes are a disjunction of conjunctions

A bid is `[[Quote]]`. The outer list is a set of alternatives the bidder will
accept, exactly one of which can be awarded. The inner list is a demand schedule
whose quotes compose: `[{price = 100, quantity = 5}, {price = 90, quantity = 3}]`
means 5 units at 100 and 3 at 90, and `quantityAt` sums the quotes at
or better than a given price to get the cumulative demand there.

`[[Party]]` already carries this reading in `availableActions` and in
`Procedure.resolvers`, where `admitActors` admits a group if the actors cover
every party in it and admits the whole list if they cover some group. `[[Quote]]`
reads the same way, so the interface has one convention for "any of these, all of
that" rather than two.

The alternative was a flat `[Quote]`, with a bidder wanting mutually exclusive
terms taking one seat per alternative on the same `Mechanism`. That works and
needs no interface change, but it prices the bidder wrongly: each seat funds its
own allocation, so a bidder offering "five units at 100 or three at 90" escrows
both and locks 770 to bid for at most 500. Under `[[Quote]]` the exposure is
`maxQuantity`, the largest an alternative can come to, and the bidder funds one
allocation for that.

What `[[Quote]]` still cannot express is a price for a bundle across lots. A bid
of 100 for `{A, B}` gives no price for `A` alone, so the checks below have no
bundle form regardless of how the list nests. That remains a separate interface
beside `OneLotBid`.



### Allocations on the bid

A bid names allocations, it does not create them. `OneLotBidView.allocations` is
`[ContractId Allocation]` and `OneLotBid_Finalize` takes the same list, funded by
the bidder before the bid is finalized.

The list is not a fixed pair because how many allocations a bid carries is a
property of the format. The sealed-bid format locks the payment as sender
and pre-authorizes the lot as receiver, so it carries two; a format funded by a
transfer pre-approval carries none.

Funding is a separate transaction because the allocation's contract id has to
exist before it can be an argument, and the Ledger API cannot pass one command's
output into another command's input.

### Requesting the allocations

`OneLotBid_RequestAllocations` takes the same `[[Quote]]` as `OneLotBid_Finalize`
and returns `requests : [AnyContractId]`. A format mints whatever carries its
funding ask; `cap-auctions-funding` supplies `OneLotBidAllocationRequest`, which
implements the Token Standard `AllocationRequest` interface.

The choice exists on the interface because minting the request needs the auction
authorities' signature, and the seat is the only contract that carries it. A
generic client can read `OneLotBidView` and compute the specifications itself,
but it cannot sign for the auction.

The result is `[AnyContractId]` rather than `ContractId AllocationRequest` so the
frozen bid package does not depend on
`splice-api-token-allocation-request-v2`, and so a format may mint one request
per registry.

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

### Withdrawal and expiry

`OneLotBid_Withdraw` returns `OneLotBid_WithdrawResult`, which carries the seat
that survives the withdrawal and the allocations the format released. Withdrawal
is the one action that retires a funded bid, so without the `released` field the
allocations it cancelled could not be reported. The seat is
`Optional (ContractId OneLotBid)` because a format can either empty it for a
later bid or retire it, and a mandatory contract id would force the second case
to create a contract it does not want.

`OneLotBid_ExpireResult` carries `meta` alone. Expiry runs from `expiresAt`,
which is the allocations' settlement deadline in both examples, so the registry's
own deadline returns the funds and there is nothing left for the bid to release.
A format that sets a later settlement deadline releases in
`oneLotBid_expireImpl`, or exercises `OneLotBid_Release` before the bid expires.

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

## Settlement factories

`OneLotAuctionTerms` carries two settlement factory contract ids,
`paymentSettleFactory` and `lotSettleFactory`, one per registry the sale settles
against. `Settlement_Settle` exercises `SettlementFactory_SettleBatch` on each.
Carrying them as data on the terms lets a sale span two registries without either
interface knowing what a registry is.

The factory ids are fixed when the terms are written, so a bidder sees which
factory will settle each leg before funding a bid. A registry that replaces its
factories invalidates open sales; the interfaces do not supply an alternative
path for that case.

The `ExtraArgs` each registry call needs are not pinned. The caller supplies them
at call time: the executor passes a settlement context to `Settlement_Settle`,
and the resolver passes a cancel context to the release of losing bids. A call
spanning two registries carries the union of both contexts, and each registry
reads only the keys it owns. The contexts might hold contract ids a registry rotates,
so pinning them in the terms would let them go stale between the award and
settlement.

`Settlement_Settle` requires the instrument admin to be a signatory of each
allocation it settles. The Token Standard settle path authenticates an allocation
by an admin field on its view, which an impostor allocation can set; the
signatory check rejects an allocation the admin did not sign.

## Package dependencies

`OneLotBid` does not mention a settlement and `Settlement` does not mention a bid.
They share the Token Standard types and the format that implements both.

An auction can therefore be priced by one package and settled by another, and a
settlement can be written for a bid shape this release does not define.
