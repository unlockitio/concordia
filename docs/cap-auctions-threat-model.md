<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# cap-auctions threat model

For implementers and auditors: where each attack on a conforming auction dies,
and what stays open. "By construction" marks a defense enforced by Daml
authorization or a fixed interface declaration — a controller derivation, a
consuming choice — with no participant's honesty assumed. "Implementation
obligation" marks a check the standard mandates but a method body carries, so
a non-conforming implementation could omit it. A check stays an obligation
wherever some legitimate format could not satisfy it as a fixed body, because
a wrongly forced check locks that format out until an interface major ([the
boundary rule](design-decisions/interface-boundary.md)). Every obligation row
names its entry under [Residual risks](#residual-risks).

## Killed threats

| # | Attack | Killed where | Label |
|---|---|---|---|
| 1 | Counterfeit bid: an instance of the venue's bid template minted outside `Auction_SubmitBid` | A sealed bid is signed by the auction's signatories and the bidder; no subset can mint one alone, and `Auction_SubmitBid` on the target auction contract is the one place all of those authorities combine | by construction |
| 2 | Foreign or sibling-auction bid passed to `Auction_Resolve` | The resolve method downcasts every bid to its own bid template and asserts the bid's recorded auction contract id equals the contract being resolved | obligation — bid provenance |
| 3 | [Allocation](glossary.md#allocation) counted for the wrong auction (open admission) | The allocation names its [settlement](glossary.md#settlement): `SettlementInfo` `cid` is the auction contract id, the executors keep `(id, cid, meta)` unique across their settlements, and admission validates the match | obligation — admission validation |
| 4 | Ineligible bidder; bidding after close | Gates in the admission path, on ledger time, before the bid or book entry exists | obligation — admission gates |
| 5 | Reading a sealed bid value through the interface | No view field, method result, or choice result carries a bid's economic content — there is no field to leak | by construction |
| 6 | Revealing a value other than the committed one | The reveal method verifies the value against the commitment — hash recomputation, or the ledger's cid authentication for cid commitments — and aborts on mismatch; row 5 leaves no interface path around it | obligation — reveal integrity |
| 7 | [Escrow](glossary.md#escrow) that cannot deliver: uncommitted (withdrawable at will), wrong [leg](glossary.md#leg) or [account](glossary.md#account) — `owner = None` is a burn destination — or `settlementDeadline` short of the auction's settle-by, or [registry](glossary.md#registry) `expiresAt` earlier still | Admission validates the whole allocation against the format's expectation, at bid time and again for post-award allocations | obligation — admission validation |
| 8 | Double resolution | `Auction_Resolve` and `Auction_Cancel` are consuming choices on a contract that exists exactly once | by construction |
| 9 | Priced bid replayed or left live | `Bid_Withdraw` and `Bid_Reveal` are consuming; resolution consumes every bid it prices through pre-agreed choices | obligation — bid consumption |
| 10 | Losers' funds retained after resolution | Executor `Allocation_Cancel` of every loser's escrow inside the resolution transaction | obligation — loser release |
| 11 | Double settlement of an outcome | `AuctionOutcome_Settle` and `AuctionOutcome_Fail` are consuming | by construction |
| 12 | Token-side double settlement; replay across settlement iterations | V2 allocation choices are nonconsuming with a MUST-consume body; each iteration mints a fresh allocation, and the funds at risk are bounded by what the authorizer funded | obligation — iterated settlement, registry conformance |
| 13 | Unauthorized settle or fail: a party names itself in `actors` | The implementation validates `actors` against the outcome's executors; the registry independently validates actors on every allocation choice | obligation — actor validation |
| 14 | Legs from another auction's settlement smuggled into one `SettleBatch` | The registry MUST check every allocation was created for the settlement being settled and that the legs are covered exactly, both sides | obligation — registry conformance |
| 15 | Partial settlement: payment taken, delivery withheld; or one outcome's legs settled under another | The settle method settles every leg of the outcome, and only its legs, in one Daml transaction | obligation — settlement atomicity |
| 16 | Resolving before reveals close (front-running); settling or failing outside the window | Ledger-time guards in the resolve, settle, and fail methods; the registry independently refuses settlement past `settlementDeadline` | obligation — windows |
| 17 | Forged operator or executor identity on an existing contract | Controllers derive only from view fields and choice arguments, and views are pinned by the implementing template's signatories | by construction |
| 18 | A sealed bid value read off its escrow amount | Sealed formats use format-constant deposits; value-sized allocations appear only after award | obligation — sealed-value leakage |

## Contained threats

These attacks have no on-ledger kill — no contract can force a party to act,
to include an input it lists, or to stay off the ledger. Containment bounds
the loss and makes the abuse detectable; the entries say who must do what.

| # | Attack | Contained how | Label |
|---|---|---|---|
| 19 | Winner reneges on payment | Bounded at the escrow placement: impossible under P1's full committed cover, the committed fixed deposit seized on `AuctionOutcome_Fail` under P2, the whole leg at risk when escrow is deferred | obligation — escrow timing |
| 20 | A fresh auction or a pricing rule naming the attacker | Not forgery — anyone may create auctions and publish packages; clients pin the package ids of implementations they trust | obligation — implementation trust |
| 21 | Censorship: bids omitted from resolution, or the auction abandoned | Funds return through bidder-controlled withdrawal, so the cost is time, never funds; the surviving bid is proof of exclusion | obligation — censorship and stalling |
| 22 | Committed funds allocated to a settlement that never admits them | Stranding is bounded to the settlement deadline: the executor cancels unmatched allocations at close; `Allocation_Withdraw` is the floor after the deadline | obligation — unmatched allocations |
| 23 | A look-alike settlement request luring bidders into allocating | No implementation can prevent third-party contracts; wallets display each request's legs and executors, and the signatories give a fake away | obligation — spoofed settlement requests |
| 24 | Shill bidding, sniping, bidder collusion | Mechanism-design attacks the interface cannot reach; formats mitigate them in their pricing rules and windows (Vickrey pricing, soft close) | out of scope |

## Residual risks

The attack the gap opens, who can mount it, what a conforming implementation does, 
and how an auditor detects one that didn't.

- **Bid provenance** ([row 2](#killed-threats)). The operator, who controls
  `Auction_Resolve`, passes a foreign template's contract or the venue's own
  bid bound to a sibling auction, and it gets priced. A conforming resolve
  method downcasts every bid to its own template and asserts the recorded
  auction cid equals the contract being resolved. Detect: a sibling
  auction's bid fails.
- **Admission gates** ([row 4](#killed-threats)). Any party submits while
  ineligible or after close — entry is open by design, so the gate is the
  method. A conforming implementation gates eligibility and the bidding
  window on ledger time before the bid exists (sealed) or the allocation is
  admitted (open). Detect: a late submission fails.
- **Admission validation** ([rows 3 and 7](#killed-threats)). A bidder crafts
  an allocation with any defect in row 7's list — one that reads as escrow
  but cannot deliver. A conforming implementation validates the whole match
  at every allocation intake: executors, id, and cid of the settlement;
  `committed = True` where the format claims escrow; each leg side and
  account owner, with the wrong [instrument](glossary.md#instrument) or
  amount rejected; `settlementDeadline` at or beyond the settle-by;
  `expiresAt` covering it. Detect: one mutated allocation per field, each
  rejected.
- **Reveal integrity** ([row 6](#killed-threats)). A bidder reveals a value
  that differs from the commitment. A conforming reveal method verifies the
  value against the commitment — recomputing the hash, or fetching the
  disclosed contract by the committed cid so the ledger authenticates the
  payload — and aborts on mismatch. Detect: a mismatched reveal fails.
- **Bid consumption** ([row 9](#killed-threats)). A priced bid left live
  lets its escrow's fate be decided twice — awarded, then later "withdrawn
  as stranded". Conforming resolution consumes every bid it prices (award,
  release, forfeit). Detect: no bid contract survives resolution.
- **Loser release** ([row 10](#killed-threats)). The operator releases
  losers late or never, leaving committed escrows locked until the deadline.
  Conforming release is executor `Allocation_Cancel` inside the resolution
  transaction. Detect: the cancels are subtransactions of the resolve; a
  follow-up job is non-conforming.
- **Iterated settlement** ([row 12](#killed-threats)). An iteration-enabled
  allocation settles repeatedly; a format that never meant to iterate is
  drained up to its funding. Conforming formats set
  `nextIterationFunding = None` (settle once, exact legs) except where the
  format uses it (P1), validating extra legs per iteration. Detect: a second
  settle fails.
- **Actor validation** ([row 13](#killed-threats)). Outcome settle and fail
  take `actors : [Party]` as controllers. A conforming implementation
  validates `actors` against the outcome's executors; the registry does the
  same on allocation choices (admin plus executors settle, executors cancel,
  the authorizer's account parties withdraw). Detect: a non-executor actor
  fails on every choice.
- **Settlement atomicity** ([row 15](#killed-threats)). The operator settles
  the payment leg and withholds delivery. A conforming settle method settles
  every leg of the outcome in one Daml transaction, batching per instrument
  admin — cross-admin atomicity rests on the executors alone (CIP-0112
  §4.3.1). Detect: a payment-only settle fails.
- **Windows** ([row 16](#killed-threats)). The operator resolves before
  reveals close, or settles or fails outside the window. Conforming methods
  guard on ledger time; the registry refuses settlement past
  `settlementDeadline`. Detect: boundary probes fail.
- **Sealed-value leakage** ([row 18](#killed-threats)). A value-sized
  allocation discloses the sealed bid to the allocation's observers — the
  registry and the account provider — whatever the escrow timing. Conforming
  sealed formats pair deferral with a format-constant deposit. Detect: the
  deposit amount is a template constant, not derived from the bid.
- **Escrow timing** ([row 19](#contained-threats)). Deferred escrow trades
  certainty for liquidity: an unbacked bidder reneges at no on-ledger cost; a
  deposit-backed one (P2) loses only the deposit. A conforming implementation
  declares its placement on
  [the ladder](cap-auctions-architecture.md#the-settlement-surface) — where
  P0, P1, and P2 are defined — and enforces what it declares. Detect: the
  admission checks match the declaration; a defaulting winner loses exactly
  what it says.
- **Implementation trust** ([row 20](#contained-threats)). Creating a fresh
  auction naming yourself operator is not forgery, and a pricing rule is
  deployed code like any other: the guarantees hold within an implementation,
  not across all. A conforming consumer pins trusted package ids and audits
  the pricing rule as the pure function slot it is. Detect: the package-id
  allowlist.
- **Censorship and stalling** ([row 21](#contained-threats)). The operator
  omits bids or abandons the auction. An omitted sealed bid stays live with
  bidder-controlled `Bid_Withdraw`; the escrow returns via
  `Allocation_Withdraw`, for committed allocations only after the settlement
  deadline. Detect: a bid outliving its auction contract is non-repudiable
  evidence of exclusion. Venues needing inclusion guarantees run a
  decentralized operator party.
- **Unmatched allocations** ([row 22](#contained-threats)). Open admission
  lets anyone commit funds against a visible settlement; an allocation never
  admitted strands its creator's money until the deadline. The conforming
  executor cancels allocations it will not settle at close, inside the
  resolve transaction where possible. Detect: an unadmitted allocation is
  cancelled at resolution.
- **Spoofed settlement requests** ([row 23](#contained-threats)). Admission
  by validation leaves the request surface open: anyone can publish a
  look-alike of the venue's allocation request and lure bidders into
  allocating — stranding funds, or worse if the fake names the attacker as
  executor. No implementation prevents third-party contracts; wallets display
  each request's legs and executors. Detect: the signatories give a fake
  away.
- **Registry conformance.** [Rows 12, 13, 14, and 16](#killed-threats) lean
  on the asset implementation's own MUSTs: consuming every nonconsuming
  allocation choice in its body, `SettleBatch`'s created-for-this-settlement
  and legs-covered-exactly checks, refusing settlement past the deadline, and
  actor validation. A registry can also pause settlement (CIP-0112 §4.3.7
  standardizes pause reporting, not the pause power) and may expire
  allocations at `expiresAt`; it is trusted for asset integrity regardless,
  since it administers the instrument. Conforming venues admit only
  registries passing the standard's validation suite and size windows
  against pause and expiry. Detect: registry package audit; the `paused`
  metadata.
