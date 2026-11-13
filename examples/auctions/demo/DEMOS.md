# Sequence diagrams — `examples/auctions`

These diagrams show the happy path of the two auction examples:
`sealed-single-lot` and `dutch-single-lot`. Each example has a short version
with the main steps and a long version that follows one auction choice by
choice. Failure paths and the checks that reject them are not shown.

Parties and contracts are both drawn as participants. An arrow from a party is a
command that party submits. An arrow from a contract is a choice exercised or a
contract created inside that command's transaction.

Both happy paths run as Daml Script in
`demo/daml/Cap/Examples/Auctions/Demo.daml`: `sealedSingleLotUniformPrice`
and `dutchSingleLotPayAsBid`. They use the registries and allocation helpers
of `cap-auctions/tests/ledger`, where LOT and USD are two test-token
registries and every party starts with 100 LOT and 1000 USD.

Both examples run a forward auction of one lot: the principal sells 10 LOT
at a reserve price of 5 USD per unit, in steps of 1. The operator is the sole
authority and the sole executor. LOT and USD have different admins, so
every resolution settles two batches, one per admin, in one transaction.

Every party funds the auction with one allocation under each admin. An
allocation reserves an amount and has no transfer legs. The legs are added at
settlement, and the registry returns whatever the legs do not use. A party
allocates 0 under the admin it only receives from.


## Sealed single lot

`sealed-single-lot` runs a sealed-bid auction under the uniform-price rule. Each
bidder funds and quotes once, and no bidder reads another bidder's bid. After
`biddingClosesAt`, the operator presents every bid to
`SingleLotAuction_Resolve`, which clears the lot and settles it in the same
transaction.

Alice quotes 6 at 8, Bob 6 at 7 and Carol 3 at 5.5. Alice is filled in full and
Bob for the 4 that remain. Carol is not filled. Both winners pay 7, the price of
the worst winning quote.

### Short version

```mermaid
sequenceDiagram
    participant P as Principal
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol
    participant AU as SingleLotAuction

    Note over P,AU: Setup
    P->>P: allocates 10 LOT and 0 USD, executors = [O]
    O->>P: SingleLotTradeProposal — uniform-price, supply 10, reserve 5
    P->>AU: TradeProposal_Accept — the two allocations, creates SingleLotAuction
    O->>O: SingleLotBidProposal — invited A, B, C

    Note over P,AU: Bidding, between biddingOpensAt and biddingClosesAt
    A->>A: BidProposal_Accept — empty SingleLotBid
    A->>A: allocates 48 USD and 0 LOT, Bid_Fund
    A->>A: SingleLotQuotesBid_Quote — 6 @ 8
    B->>B: the same — reserves 42, quotes 6 @ 7
    C->>C: the same — reserves 16.5, quotes 3 @ 5.5

    Note over P,AU: Resolve, after biddingClosesAt
    O->>AU: SingleLotAuction_Resolve — the three bids
    AU->>AU: clear — A 6, B 4, C 0, clearing price 7
    AU->>P: settle — 42 from A, 28 from B
    AU->>A: settle — 6 LOT, 6 USD back
    AU->>B: settle — 4 LOT, 14 USD back
    AU-->>C: allocations cancelled, 16.5 back
    AU->>AU: Bid_Close on every bid
```

### Long version

The long version adds the checks at each submission, how the resolution keeps
the bids, and how the outcome is grouped into batches and settled.

```mermaid
sequenceDiagram
    box Parties
        participant O as Operator
        participant P as Principal
        participant A as Alice
        participant B as Bob
        participant C as Carol
    end
    box Contracts
        participant TP as SingleLotTradeProposal
        participant AU as SingleLotAuction
        participant BP as SingleLotBidProposal
        participant BID as SingleLotBid
        participant AF as USD factory
        participant WF as LOT factory
    end

    Note over O,WF: Setup
    O->>TP: create — resolutionInfo uniform-price, terms, schedule, acceptBefore
    TP->>TP: ensure — one lot, a supported mechanism, valid terms and schedule
    P->>P: allocate 10 LOT and 0 USD — settlement of this resolution, executors [O]
    P->>TP: TradeProposal_Accept — fundings by admin
    TP->>TP: every account is the principal's
    TP->>TP: requireValidPrincipalFunding — the funding terms, empty legs, 10 LOT reserved
    TP->>AU: create SingleLotAuction — signed by O and P
    O->>BP: create — invited [A, B, C]

    Note over O,WF: Bidding, between biddingOpensAt and biddingClosesAt
    A->>BP: BidProposal_Accept — nonconsuming
    BP->>BP: A is invited, the bidding window is open
    BP->>BID: create SingleLotBid — bidder A, no fundings, no quotes
    A->>A: allocate 48 USD and 0 LOT — executors [O]
    A->>BID: Bid_Fund — fundings by admin
    BID->>BID: validSingleLotBid — the resolution, A signed, the funding terms, empty legs
    BID->>BID: archive and recreate with the fundings
    A->>BID: SingleLotQuotesBid_Quote — 6 @ 8
    BID->>BID: validSingleLotBid — the quotes are valid and 48 covers them
    BID->>BID: archive and recreate with the quotes
    B->>BID: the same — reserves 42 USD, quotes 6 @ 7
    C->>BID: the same — reserves 16.5 USD, quotes 3 @ 5.5

    Note over O,WF: Resolve, after biddingClosesAt
    O->>AU: SingleLotAuction_Resolve — bidCids, settleBatchContexts, cancelContexts
    AU->>AU: requireResolvedBy — this auction is the resolver of resolutionInfo
    AU->>AU: requireClosed — the bidding window is closed
    AU->>BID: fetchAndIgnoreConflictingBids — for this resolution, no principal account, no shared account
    AU->>AU: clearSingleLot uniformPrice — A 6, B 4, C 0, at 7
    AU->>AU: buildBatchesByAccount — one batch per admin
    AU->>AF: SettlementFactory_SettleBatch — 42 A→P, 28 B→P, lastIterationFinalizer
    AF-->>A: 6 USD unused, returned
    AF-->>B: 14 USD unused, returned
    AU->>WF: SettlementFactory_SettleBatch — 6 P→A, 4 P→B
    AU->>AU: cancelAllocations — C's two allocations, unsettled
    AU-->>C: 16.5 USD returned
    AU->>BID: Bid_Close on A, B and C — archive
    AU-->>O: ResolveResult — settled, cancelled, ignoredBids []
```


## Dutch single lot

`dutch-single-lot` runs a Dutch clock auction under the pay-as-bid rule. The
price starts at 10 and falls by 1 every hour until it reaches the reserve price.
A bidder quotes at the current price, so an earlier quote has a higher price
and is filled first. The operator can resolve as soon as the quotes claim the
whole supply, without waiting for `biddingClosesAt`.

Alice claims 6 at hour 2, at a price of 8. Bob claims 4 at hour 3, at a price
of 7. The claims then cover the supply, so the operator resolves. Each bidder
pays its own price.

### Short version

```mermaid
sequenceDiagram
    participant P as Principal
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant AU as DutchAuction

    Note over P,AU: Setup
    P->>P: allocates 10 LOT and 0 USD, executors = [O]
    O->>P: DutchTradeProposal — pay-as-bid, clock 10 → 5, −1 per hour
    P->>AU: TradeProposal_Accept — the two allocations, creates DutchAuction
    O->>O: DutchBidProposal — invited A, B

    Note over P,AU: Bidding — the clock falls
    A->>A: BidProposal_Accept, allocates 48 USD and 0 LOT, Bid_Fund
    Note over A: t+2h — price 8
    A->>A: SingleLotQuotesBid_Quote — 6 @ 8
    B->>B: BidProposal_Accept, allocates 28 USD and 0 LOT, Bid_Fund
    Note over B: t+3h — price 7
    B->>B: SingleLotQuotesBid_Quote — 4 @ 7
    Note over P,AU: claims 10 = supply

    Note over P,AU: Resolve early — the claims cover the supply
    O->>AU: DutchAuction_Resolve — both bids
    AU->>AU: clear — earlier claims first, each at its own price
    AU->>P: settle — 48 from A, 28 from B
    AU->>A: settle — 6 LOT
    AU->>B: settle — 4 LOT
    AU->>AU: Bid_Close on both bids
```

### Long version

The long version adds the checks on the clock, the check that each quote is at
the clock price, and the early-resolution condition.

```mermaid
sequenceDiagram
    box Parties
        participant O as Operator
        participant P as Principal
        participant A as Alice
        participant B as Bob
    end
    box Contracts
        participant TP as DutchTradeProposal
        participant AU as DutchAuction
        participant BP as DutchBidProposal
        participant BID as DutchBid
        participant AF as USD factory
        participant WF as LOT factory
    end

    Note over O,WF: Setup
    O->>TP: create — pay-as-bid, clock startPrice 10, priceStep 1, tickInterval 1h
    TP->>TP: ensure — a supported mechanism, valid terms and schedule
    TP->>TP: ensure — validDutchClock, the clock reaches 5 before biddingClosesAt
    P->>P: allocate 10 LOT and 0 USD — settlement of this resolution, executors [O]
    P->>TP: TradeProposal_Accept — fundings by admin
    TP->>TP: every account is the principal's
    TP->>TP: requireValidPrincipalFunding — the funding terms, empty legs, 10 LOT reserved
    TP->>AU: create DutchAuction — signed by O and P, carries the clock
    O->>BP: create — invited [A, B]

    Note over O,WF: Bidding — t+2h, the price is 8
    A->>BP: BidProposal_Accept — nonconsuming
    BP->>BP: A is invited, the bidding window is open
    BP->>BID: create DutchBid — bidder A, no fundings, no quotes
    A->>A: allocate 48 USD and 0 LOT — executors [O]
    A->>BID: Bid_Fund — fundings by admin
    BID->>BID: validSingleLotBid, archive and recreate with the fundings
    A->>BID: SingleLotQuotesBid_Quote — 6 @ 8
    BID->>BID: dutchClockPrice — 10 − 2 × 1 = 8
    BID->>BID: quotesAppended, quotesAtClockPrice — one new claim, at 8
    BID->>BID: validSingleLotBid — 48 covers it, archive and recreate with the quotes

    Note over O,WF: Bidding — t+3h, the price is 7
    B->>BID: the same — reserves 28 USD, quotes 4 @ 7
    Note over O,WF: claims 6 + 4 = 10, the supply

    Note over O,WF: Resolve — before biddingClosesAt
    O->>AU: DutchAuction_Resolve — bidCids, settleBatchContexts, cancelContexts
    AU->>AU: requireResolvedBy — this auction is the resolver of resolutionInfo
    AU->>BID: fetchAndIgnoreConflictingBids — for this resolution, no principal account, no shared account
    AU->>AU: the window is closed or the claims cover the supply — 10 ≥ 10
    AU->>AU: clearSingleLot payAsBid — A 6 at 8, B 4 at 7
    AU->>AU: buildBatchesByAccount — one batch per admin
    AU->>AF: SettlementFactory_SettleBatch — 48 A→P, 28 B→P, lastIterationFinalizer
    AU->>WF: SettlementFactory_SettleBatch — 6 P→A, 4 P→B
    AU->>AU: cancelAllocations — nothing left unsettled
    AU->>BID: Bid_Close on A and B — archive
    AU-->>O: ResolveResult — settled, cancelled [], ignoredBids []
```
