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

## Layout

```
cap-auctions/
├── cap-auctions-registry/   RegistryCalls, AllocationFactoryCall,
│                            SettlementFactoryCall — the factory cids + ExtraArgs.
│                            Its own package: the interfaces import it, so folding it
│                            into utils would close a cycle.
├── Interfaces/
│   ├── bid/                 OneLotBid (requires Submittable), OneLotAuctionTerms,
│   │                        Direction, LotSpec, Quote
│   └── settlement/          Settlement, SettlementBatch, SettlementView
└── cap-auctions-utils/      settlementOf, paymentLeg, lotLeg, paymentLegId, lotLegId

examples/auctions/
├── sealed-bid-first-price/            the operator holds the assets and the
│   {impl,fixtures,test}               presentation: seller and bidders escrow up front,
│                                      the procedure picks the high quote and mints the
│                                      Settlement; no bidder authority at award.
└── sealed-bid-first-price-high-trust/ the stronger one: the winner co-signs the
    {impl,fixtures,test}               settlement, and OneLotBid_Award re-allocates the
                                       escrow onto the real legs before minting it.

lib/                         vendored Token Standard and Splice DARs
```

