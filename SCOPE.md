<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# CAP first-release scope

This document is the Milestone 1 deliverable *"documented first-release scope
and out-of-scope items"*: what the first CAP release (through M6) will and will
not contain, each in-scope capability mapped to the milestone that builds it and
the artifact that proves it.

## What the first release is
First release = the cap-core spine + one bounded governance set plus
one bounded auction set, each proving the same core end-to-end. The
goal is to demonstrate reuse, not to exhaust either domain.

## In scope — capability → milestone → proof
                                        
| Capability | First release contains | Milestone | Proven by |
|---|---|---|---|
| cap-core | interface layer + opt-in toolkit | M1 | sandbox prototype |
| Toolkit growth | helpers and default implementations as the modules surface reusable patterns | M1-M6 | reuse across both modules |
| Governance interfaces | Splice-generalizable | M1 | BabyDso reference flow + sandbox prototype |
| Governance interfaces | Privacy test | M2 | BabyDso with privacy flow + sandbox test |
| Decision rules | majority with configurable quorum and approval threshold | M3 | sandbox tests |
| Weighted voting | weights by stake, token holdings, or external input | M3 | sandbox tests |
| Auction formats | sealed-bid first-price, sealed-bid second-price, Dutch, multi-unit | M4 | sandbox tests |
| Settlement | Token Standard V2 composability | M4–M5 | sandbox test |
| Reference flows | one governance and one auction flow, both reusing the same `cap-core` | M5 | end-to-end tests |
| Reference-flow demos | a prototype frontend + backend driving each reference flow | M5 | running governance and auction demos |
| Open-source release | API docs, developer setup, extension guide, Apache 2.0 | M6 | public repo |
| Public walkthrough | at least one walkthrough, tutorial, or technical session | M6 | published walkthrough |

### cap-core 

The spine is largely in place: the **interface layer**
(`Submittable`, `Mechanism`, `Outcome`), and the
opt-in **toolkit** (`time`, `policies`, `checked-fetch`, `patchable`).
Completeness, privacy, expiry handling, submission windows, and atomic
downstream execution are part of this surface and are specified in
[`DESIGN.md`](DESIGN.md).


**The scope for cap-core is deliberately narrow.** The interface layer is meant
to be stable, so first-release work on the core is:

- **Extending the toolkit** — adding opt-in helpers and default implementations
  as the two modules surface reusable patterns. Toolkit growth never constrains
  a format; it only saves work.
- **Adapting the interfaces only on a proven need** — if building governance or
  auctions reveals a genuine addition the frozen surface should carry. A change
  here is the exception, weighed against the under-forcing/over-forcing
  asymmetry, not routine.

### cap-governance (bounded set)

The **interface layer** (`Governor`, `Ballot`, `Outcome`, `Target`) already
proves generalizable: it drives a Splice-shaped governance flow end-to-end
(the BabyDso reference). The next step in scope is to **test it against
privacy** — confirming the same interfaces hold when submissions stay private
through resolution.

First-release work on cap-governance is:

- **Proving the interfaces** — the Splice generalization and the pending
  privacy test. Interfaces change only if that surfaces a genuine need.
- **Extending the toolkit** — growing the opt-in helpers and default
  implementations: `Policies`, `Tallies`, `TargetPolicies`, `TargetUtil`. The
  `Tallies` set covers, for example, majority with configurable quorum and
  approval threshold.
- **Adding weighted governace mechanisms** — attribute weights to participants,
  for example by stake, token holdings, or an external weight input. This
  *might* require new interfaces.
- **A prototype frontend + backend** for the governance reference flow (M5), to
  drive it end-to-end.

### cap-auctions (bounded set)

Work on cap-auctions is starting. The **interface layer** (`Auction`, `Bid`,
`Outcome`) reuses the same spine, and settlement **composes with the Token
Standard V2** — an auction clears and the asset moves in one atomic DvP, rather
than cap-auctions moving assets itself.

First-release work on cap-auctions is:

- **Building the toolkit** — the opt-in helpers and default implementations for
  the auction formats (still to be written).
- **Implementing and testing the formats** — sealed-bid first-price,
  sealed-bid second-price, Dutch, and multi-unit, each with sandbox tests.
- **Wiring settlement** — issuing Token Standard V2 allocations from an
  approved outcome.
- **A prototype frontend + backend** for the auction reference flow (M5), to
  drive it end-to-end.

### Cross-cutting

Spanning all three packages:

- **Versioning** — Daml 3.x / LF 2.1, versioned in lockstep with Token
  Standard V2.
- **Tests** — Daml Script and sandbox integration tests for every supported
  governance rule and auction format.
- **Reference flows** — one end-to-end governance flow and one end-to-end
  auction flow, both reusing the same `cap-core`, proving the reuse claim.
- **Documentation** — API docs, developer setup instructions, and an extension
  guide for building new modules on `cap-core`.
- **Public walkthrough** — at least one walkthrough, tutorial, or technical
  session for external evaluation.
- **Licence** — public open-source release under Apache 2.0.

## Out of scope

<!-- Each line carries its "why". This list is the maturity signal; keep the
     rationale clause on every entry. -->

| Excluded | Why it is out |
|---|---|
| Full Development Fund operational governance | CAP is the reusable primitive, not the DAO that runs the fund | 
| Other formats — combinatorial & continuous-double auctions, delegated voting, quadratic funding | The bounded set proves reuse, not completeness of either domain |
| Custody / matching engine / off-ledger settlement infra | cap-auctions issues settlement obligations only |
| KYC / identity / electorate membership | The participation-right pattern is the eligibility mechanism; institutions bring their own |
| Production UI / indexer / wallet | CAP is a Daml library; front-ends are downstream — the M5 reference flows ship demo front-ends only to exercise them, not production surface |
