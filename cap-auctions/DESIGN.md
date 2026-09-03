# Design — `cap-auctions`

## Layout

```
cap-auctions/
├── cap-auctions-registry/   RegistryCalls, AllocationFactoryCall,
│                         SettlementFactoryCall
├── Interfaces/
│   ├── bid/                 OneLotBid, OneLotAuctionTerms, Quote, LotSpec
│   └── settlement/          Settlement, SettlementView
└── cap-auctions-utils/      allocateOneStep, reallocate, settlementOf,
                             paymentLeg, lotLeg, sellerEscrow

examples/auctions/
└── sealed-bid-first-price/  a sealed-bid first-price auction settled through
    {impl,fixtures,test}     Token Standard allocations, with the operator as
                             the sole executor: resolving the auction settles
                             it, so no `Settlement` is needed

lib/                         vendored Token Standard and Splice DARs
```


TODO