<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Post-release extension points

This document is the Milestone 1 deliverable *"documented extension points for
downstream modules"*: what can be built on CAP after the final milestone (M6)
without changing the released library. It describes the library as M6 releases
it; where a capability lands in an earlier milestone, the mapping is in
[`SCOPE.md`](SCOPE.md). Terms carry their [`GLOSSARY.md`](GLOSSARY.md)
meanings.

The adoption milestones (M7–M8) pay on external teams reusing CAP in pilot or
production applications. The sections below are the ways a team does that,
ordered from cheapest to deepest: extend a deployed system, add a format to a
shipped domain, build a new domain on `cap-core`. The last section is how the
library itself grows after release without breaking adopters. The M6 extension
guide walks the same seams with worked code; this document is the map.

## Extension model

Interface choice bodies are fixed in Daml, so the library splits every
behaviour across three surfaces:

- **Fixed choice bodies** — admission of every contract id, the completeness
  cover, window and authority checks (`cap-core/internal/checks`). An
  extension inherits these guarantees and cannot weaken them.
- **Interface methods** — what a legitimate format varies: the tally, the
  pricing rule, the drift policy, the settlement legs. An extension is chiefly
  the method bodies it supplies.
- **The opt-in toolkit** — default method bodies and helpers (`cap-core/util`,
  `cap-governance/util`). Using one saves work; skipping one never locks a
  format out.

Extension is deployment: new templates implementing the released interfaces,
uploaded beside the released DARs. Nothing below re-opens a released interface
package.

## Extending a deployed system

The cheapest tier: a system already running on CAP grows by pure deploys,
often one new template or one new case in an application datatype. The BabyDso
reference (`examples/BabyDso/cap-version`) demonstrates each of these.

- **A new governance action.** The action set is an application datatype, so a
  new action is one constructor and one dispatch case (`GovAction` /
  `ActionSpec` in the BabyDso `DsoMechanism`); the mechanism contract and the
  authority identity stay fixed.
- **A new governable target.** Any standing contract becomes governable with
  an `interface instance Target` (`Cap.Governance.TargetV1`); a `Patchable`
  instance on its config adds field-wise merge, so two approvals touching
  different fields compose in either order.
- **Eligibility.** The participation right is the eligibility mechanism: an
  empty `Submittable` minted only to eligible parties. Institutions bring
  their own identity or KYC system and compose it here, because the core
  forces none.
- **A drift policy per action.** Whether an approved outcome executes over a
  target that changed since approval is declared per action: `driftAborts`
  refuses, `driftMerges` merges (`Cap.Governance.TargetPolicies`), or the
  application writes its own `onTargetDrift`.
- **A timing profile.** Submission, resolution, and withdrawal windows are
  three `Mechanism` hooks; `Cap.Core.Policies` and `Cap.Governance.Policies`
  ship named profiles, and an application may write its own.

## New governance formats

A governance format is three interface instances — `Governor`, `Ballot`,
`GovernanceOutcome` — plus `Target` instances on its governed state. The fixed
bodies carry admission, the completeness proof, timing, target binding, and
drift detection; the format supplies the methods.

- **A new decision rule.** The tally is a pure function `[Ballot] -> Verdict`;
  the released rules (`Cap.Governance.Tallies`: quorate majority, weighted,
  median, turnout threshold) are reference bodies, not a closed set. A new
  rule is one function.
- **Delegated and quadratic formats.** Liquid democracy, delegated voting, and
  quadratic funding are later formats on the same spine: delegation state is
  the format's own templates, and the weights land in its tally.

## New auction formats

An auction format is three interface instances — `Auction`, `Bid`,
`AuctionOutcome`. The M4 formats (sealed-bid first- and second-price, Dutch,
multi-unit) fill this spine; later formats fill the same one.

- **Later formats.** Combinatorial auctions, continuous double auctions, and
  call/periodic markets are admitted without a core change: bid content is the
  format's own (opaque at the interface), the pricing rule is
  `auction_resolveImpl`, and one-outcome-per-obligation resolution already
  covers multi-winner clearing.
- **Settlement variants.** Settlement composes with the Token Standard V2 —
  the reference flow issues allocations, so clearing and asset movement are
  one atomic DvP, and `isExecutor` is plural for joint settlement. A format
  may vary escrow timing, legs, and instruments in
  `auctionOutcome_settleImpl`, under the fixed obligation that all legs of a
  settlement execute atomically.

## New domains on cap-core

The deepest tier, and the proposal's stated direction: `cap-core` is the
foundation for further allocation-oriented modules. A new domain is a package
beside `cap-governance` and `cap-auctions`: its interfaces `requires` the core
trio (`Mechanism`, `Submittable`, `Outcome`), its fixed bodies reuse the
shared checks (`Cap.Core.ChecksV1`) and declare their own admission group
beside `ForMechanism` and `ForTarget`, and formats implement the result. The
two shipped domains are the proof the spine is generic; each direction below
is a further instantiation.

| Direction | Submittables are | Resolution is | Outcomes are |
| --- | --- | --- | --- |
| Order matching | orders | the matching rule | matched trades, settling as Token Standard DvP |
| Collateral allocation | pledges | the allocation rule | collateral commitments |
| Resource distribution | claims | the distribution rule | delivery obligations |

Credential-weighted decision flows, the proposal's fourth direction, need no
new domain — see the governance section above.

## Library growth after release

The released interface packages are frozen: each is its own `-v1` package, and
an adopter depends only on the interfaces they implement. The library grows
around them:

- **Toolkit growth.** New opt-in helpers and default implementations as
  adopters surface reusable patterns. Toolkit growth never constrains a
  format; it only saves work.
- **Extension constructors.** `ExtSubmittableState`, `ExtResolutionOutcome`,
  and the metadata channels let result types grow without an interface major.
- **A resolve-and-execute path.** A dedicated atomic path for the common case
  where an outcome is created and executed in the resolving transaction.
- **New interface packages.** New domains and new interface versions ship as
  additive packages.
