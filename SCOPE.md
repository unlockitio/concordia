<!-- Copyright (c) 2026 Unlockit -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# CAP first-release scope

This document is the Milestone 1 deliverable *"documented first-release scope
and out-of-scope items"*: what the first CAP release (through M6) will and will
not contain, each in-scope capability mapped to the milestone that builds it and
the artifact that proves it.

## First Release
First release builds cap-core, cap-governance and cap-auctions interface layer, plus usability and value demonstrations. This scope is not fixed — it can be revised as the ecosystem's needs surface.

## In scope

Each package's deliverables below, as capability → proof → milestone: the
milestone that builds each capability and the artifact that proves it.

The reference flows are how the interfaces are tested for generality: each is
built on the same core, and a requirement a flow cannot express is grounds for
changing an interface. Together they show that the interfaces hold across a
broad set of use cases.

### cap-core

The interface layer (`Submittable`, `Resolver`) and the opt-in toolkit
(`cap-core-utils`: admission, execution, patch, time, value) — completeness,
privacy, expiry, submission windows, and atomic downstream execution — all
specified in [`DESIGN.md`](DESIGN.md).

| Capability | First release contains | Proven by | Milestone |
|---|---|---|---:|
| Interfaces | Interface layer | [interfaces](cap-core/Interfaces) | **M1** |
| Toolkit | Opt-in toolkit | [cap-core-utils](cap-core/cap-core-utils) | **M1** |
| Interfaces | The same core carrying both domains | [governance slice](examples/governance/private-majority-vote) · [auction slice](examples/auctions/sealed-bid-first-price) | **M2** |

**The scope for cap-core is deliberately narrow** — the interface layer is meant
to be stable. First-release work on the core is extending the toolkit (opt-in
helpers as the modules surface reusable patterns; never constrains a format,
only saves work) and adapting the interfaces only on a proven need, weighed
against the under-forcing/over-forcing asymmetry.

### cap-governance 

| Capability | First release contains | Proven by | Milestone |
|---|---|---|---:|
| Interfaces | Interface layer | [Interfaces](cap-governance/Interfaces/) | **M1** |
| Interfaces | Opt-in toolkit | [Toolkit](cap-governance/cap-governance-utils) | **M1** |
| Implementation | Splice-generalizable flow | [cap implementation](examples/governance/baby-dso/cap) | **M1** |
| Demo | Splice-generalizable flow demos | [DEMOS.md](examples/governance/baby-dso/DEMOS.md) | **M1** |
| Implementation | Private votes reference flow | [private-majority-vote/impl](examples/governance/private-majority-vote/impl) | **M2** |
| Demo | Private votes reference flow demo | [private-majority-vote/DEMOS.md](examples/governance/private-majority-vote/DEMOS.md) | **M2** |
| Tests | Daml Script and sandbox integration tests | [test package](examples/governance/private-majority-vote/test) · [sandbox-test.sh](scripts/sandbox-test.sh) | **M2** |
| Toolkit | Extend tallies with quorum/threshold rules | sandbox tests | **M3** |
| Toolkit | Default implementations for downstream execution hooks (tbd) | sandbox tests | **M3** |
| Interfaces | Generalized weighted ballots logic | code | **M3** |
| Implementation | Weighted voting flow | code | **M3** |
| Demo | Weighted voting flow demo | sandbox prototype | **M3** |
| Implementation | A prototype frontend + backend driving the governance flow | code | **M5** |
| Demo | A prototype frontend + backend driving the governance flow demo | end-to-end tests | **M5** |


The interface layer is `Ballot`, `AuthenticTarget`, `Action` and `Executable`;
it already drives a Splice-shaped flow end-to-end (the M1 BabyDso reference),
and M2 confirms the same interfaces hold when votes stay private through
resolution. Weighted voting *might* require new interfaces.

### cap-auctions 

| Capability | First release contains | Proven by | Milestone |
|---|---|---|---:|
| Interfaces | interface layer | [Interfaces](cap-auctions/Interfaces/) | **M1** |
| Toolkit | opt-in toolkit | [cap-auctions-utils](cap-auctions/cap-auctions-utils) | **M2** |
| Implementation | sealed-bid first-price | [sealed-bid-first-price/impl](examples/auctions/sealed-bid-first-price/impl) | **M2** |
| Demo | sealed-bid first-price demo | [DEMOS.md](examples/auctions/sealed-bid-first-price/DEMOS.md) | **M2** |
| Tests | Daml Script and sandbox integration tests | [test package](examples/auctions/sealed-bid-first-price/test) · [sandbox-test.sh](scripts/sandbox-test.sh) | **M2** |
| Toolkit | second-price payment rule | code | **M3** |
| Implementation | Dutch auction (multi-round) | code | **M4** |
| Demo | Dutch demo | sandbox prototype | **M4** |
| Implementation | multi-unit | code | **M4** |
| Demo | multi-unit demo | sandbox prototype | **M4** |
| Implementation | a prototype frontend + backend driving the auction flow | code | **M5** |
| Demo | a prototype frontend + backend driving the auction flow | end-to-end tests | **M5** |

The interface layer is `OneLotBid` and `Settlement`, reusing the same
skeleton. A bid names the payment its bidder funded before casting — a Token
Standard `Allocation`, the standard's own artifact — and records what it says,
so a pricing rule reads the funding off the bid. One close bars casting and
withdrawing alike, so the set of bids is fixed the moment bidding ends and each
one is irrevocable from then. A lot is a submittable too: the seller's offer,
admitted by the check that admits the bids, so a resolution mints the seller's
side and nothing before it does. A sale publishes its executors before bidding
opens, so a bidder reads who can move their funds before committing any; a
format that names none of the parties an award moves assets to or from makes
that award unwithholdable once a resolution has reached it. Settlement runs through
`SettlementFactory_SettleBatch` in the fixed execute body — cap-auctions
**composes with the Token Standard V2** rather than describing settlement of
its own.

### Cross-cutting

| Capability | First release contains | Proven by | Milestone |
|---|---|---|---:|
| Toolkit | helpers and default implementations that appear with new use cases | reuse | **M1–M6** |
| Open-source release | API docs, developer setup, extension guide, Apache 2.0 | public repo | **M6** |
| Public walkthrough | at least one walkthrough, tutorial, or technical session | published walkthrough | **M6** |


## Out of scope

<!-- Each line carries its "why". This list is the maturity signal; keep the
     rationale clause on every entry. -->

| Excluded | Why it is out | Where it lands |
|---|---|---|
| Other formats — combinatorial & continuous-double auctions, delegated voting, quadratic funding | The bounded set proves reuse, not completeness of either domain | Buildable on `cap-core` after release — these are what the extension points enable, not gaps ([POST-RELEASE.md](POST-RELEASE.md): new [governance](POST-RELEASE.md#new-governance-formats) and [auction](POST-RELEASE.md#new-auction-formats) formats) |
| Full Development Fund operational governance | CAP is the reusable primitive, not the DAO that runs the fund | Architectural boundary |
| Custody / matching engine / off-ledger settlement infra | cap-auctions deals only with the enforcement layer | Architectural boundary |
| KYC / identity / electorate membership | The participation-right pattern is the eligibility mechanism; institutions bring their own | Architectural boundary |
| Production UI / indexer / wallet | CAP is a Daml library; front-ends are downstream — the M5 reference flows ship demo front-ends only to exercise them, not production surface | Architectural boundary |
