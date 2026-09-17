# Design — `cap-auctions`

## What it is

The selling domain of CAP: two interfaces — `OneLotBid` and `Settlement` — that let an
auction format, a token registry and a bidder be written without knowing each other.

A bid is a `cap-core` `Submittable`, so an auction is a `Resolver` running a named
`Procedure` (first-price, second-price, …) over the bids it collected. A settlement is a
Token Standard Settlement batch.

Cap-auctions currently supports auctions with one lot from one seller. Formats that keep that shape( second price, a Dutch, multi-unit), need no new interface, only new templates implementing `OneLotBid`. Formats that outgrow it get a new interface **beside** `OneLotBid` (e.g. several lots and several sellers grows the terms and the award rule, while everything underneath carries over unchanged). 

Cap-auctions does not define an asset type of its own. Every asset-shaped thing in cap-auctions is a
Token Standard type. Allocation are created by the *format*, not by cap-auctions. 

## The bid surface

`OneLotBid` has five choices. `OneLotBid_RequestAllocations` publishes what the
bidder must lock and returns the requests it minted. `OneLotBid_Finalize` takes
the quotes and the allocations backing them. `OneLotBid_Withdraw`,
`OneLotBid_Release` and `OneLotBid_Expire` end a bid without awarding it.

Each choice checks entitlement against `OneLotBidView.availableActions`, the
window against the terms, and the quote shape against the lot, then calls the
format's implementation. An implementation may abort: the plain sealed-bid
format refuses `OneLotBid_Withdraw`, and the high-trust format refuses
`OneLotBid_RequestAllocations`.

## Layout

```
cap-auctions/
├── cap-auctions-registry/   RegistryCalls, AllocationFactoryCall,
│                            SettlementFactoryCall — the factory cids + ExtraArgs.
│                            Its own package: the interfaces import it, so folding it
│                            into utils would close a cycle.
├── Interfaces/
│   ├── bid/                 OneLotBid (requires Submittable), OneLotAuctionTerms,
│   │                        Direction, Lot, Quote
│   └── settlement/          Settlement, SettlementBatch, SettlementView
├── cap-auctions-utils/      saleSettlement, paymentLeg, lotLeg, paymentLegId, lotLegId
└── cap-auctions-funding/    OneLotBidAllocationRequest, an AllocationRequest carrying
                             the specifications a one-lot bid implies, plus
                             bidderPaymentAllocation and bidderLotAllocation

examples/auctions/
├── sealed-bid-first-price/            the operator holds the assets and the
│   {impl,fixtures,demo}               presentation: seller and bidders escrow up front,
│                                      the procedure picks the high quote and mints the
│                                      Settlement; no bidder authority at award.
└── sealed-bid-first-price-high-trust/ the stronger one: the winner co-signs the
    {impl,fixtures,demo}               settlement, and AuctionBid_Award re-allocates the
                                       escrow onto the real legs before minting it.

lib/                         vendored Token Standard DARs the interfaces bind to
```

Why these shapes and not the alternatives: [`RATIONALE.md`](RATIONALE.md).
