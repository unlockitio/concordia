<!-- SPDX-License-Identifier: Apache-2.0 -->

# Post-release extension points

This document is the Milestone 1 deliverable *documented extension points for
downstream modules*: what can be built on CAP after the final milestone. 

## Extension model

The library splits every
behaviour across three surfaces:

- **Fixed choice bodies** — admission of every contract id, the completeness
  cover, time window and fetch checks. An
  extension inherits these guarantees and cannot weaken them.
- **Interface methods** — what an implementation can define (e.g the tally, the
  pricing rule, the drift policy). An extension is
  the method bodies it supplies.
- **The opt-in toolkit** — default method bodies and helpers. 
	They are opt-in so they do not lock formats out.

Extension is deployment: new templates implementing the released interfaces,
uploaded beside the released DARs. Nothing below re-opens a released interface
package.

## Extending a deployed system

The cheapest tier: a system already running on CAP grows by pure deploys,
often one new template or one new case in an application datatype. The BabyDso
reference (`examples/BabyDso/cap-version`) demonstrates each of these.

- **A new governable target.** Any standing contract becomes governable with
  an `interface instance Target`.
- **Eligibility.** The participation right is the eligibility mechanism: an
  empty `Submittable` minted only to eligible parties. Institutions bring
  their own identity or KYC system and compose with this.
- **A drift policy per action.** Whether an approved outcome executes over a
  target that changed since approval is declared per action.
- **A timing profile.** Submission, resolution, and withdrawal windows are
  three `Mechanism` hooks; `Cap.Core.Policies` and `Cap.Governance.Policies`
  ship named policies, but an application may write its own.

## New governance formats

A new a Governance format can be created by adding a new decision rule to Tally. 
Interfaces supporting vote delegation can also be implemented in the future. 

## New auction formats

The released interfaces sell **one lot from one seller**: `OneLotAuctionTerms`
names a single `lot : Lot` and a single pair of seller accounts, and a bid is a
set of alternative quote schedules over that one lot. Formats that keep that
shape — a second-price payment rule, a Dutch clock, multiple units of that
lot's quantity — are new templates implementing `OneLotBid` and `Settlement`,
deployed beside the released DARs. cap-auctions extends to them without
changing.

**Several lots and several sellers** carries on the released interfaces only as
bids that are won independently — one `OneLotBid` contract per lot, all naming
the same `Mechanism`. Anything that binds the lots together needs a new bid
interface. Nothing ties a resolution to a single set of terms, so each bid
carries its own `lot`, `reserve`, seller accounts and `RegistryCalls`, and the
procedure groups the presented bids by lot. A shared `saleId` settles them
together. This is a deployment pattern on the released interfaces, not an
addition to them; `cap-auctions/RATIONALE.md` sets out how it works and what it
leaves out.

Two things require a new bid interface, and only one of them is about bundles.

**Bids that span lots** — a bundle priced all-or-nothing, a substitute
constraint across lots, or a budget across lots — cannot be said, because
separate bids are won independently. `[[Quote]]` says "at most one of these"
within a lot; there is no form of it that spans lots. The obstacle is the escrow
rather than the lot count: a bundle bidder's exposure is the largest bundle it
might win, not the sum of its bids, while `OneLotBidView.allocations` covers one
bid.

**One contract covering several lots** is also outside `OneLotBid`, even for
separable bids: `OneLotBidView` carries a single `terms.lot` and `Quote` has no
lot reference. A format may want it for fewer contracts, one allocation instead
of one per lot, or a single unit for withdrawal and expiry. This is a packaging
change, and every check survives in a per-lot form.

Either interface ships beside `OneLotBid` rather than re-opening it, and the core
underneath is unchanged — one submittable per bid, funds allocated before
casting, one close fixing the set of bids, and settlement through the Token
Standard all carry over.

Continuous double auctions are the format that may reach further, into cap-core
itself, because a submittable declares its mechanism when it is submitted.

## New domains on cap-core

The deepest tier, and the proposal's stated direction: `cap-core` is the
foundation for further allocation-oriented modules. A new domain is a package
beside `cap-governance` and `cap-auctions` where its interfaces require the core
(`Mechanism`, `Submittable`, `Outcome`), its fixed bodies may reuse the
shared checks. Each direction below is a further instantiation.

| Direction | Submittables are | Resolution is | Outcomes are |
| --- | --- | --- | --- |
| Order matching | orders | the matching rule | matched trades |
| Collateral allocation | pledges | the allocation rule | collateral commitments |
| Resource distribution | claims | the distribution rule | delivery obligations |


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
