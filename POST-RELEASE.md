<!-- SPDX-License-Identifier: Apache-2.0 -->

# Post-release extension points

This document is the Milestone 1 deliverable *documented extension points for
downstream modules*: what can be built on CAP after the final milestone. 

## Extension model

The library splits every behaviour across three surfaces:

- **Interfaces with fixed choice bodies** — `Action`, `Executable`, `OneLotBid`
  and `Settlement`. The fixed bodies check admission, windows and expiry. An
  extension implements the interface methods and inherits these checks; it
  cannot weaken them.
- **Templates the app writes** — the governor, its procedures, the ballots and
  the vote kinds in governance, and the auction contract in auctions. Each app
  owns these, so each app can shape them.
- **The opt-in toolkit** — type classes (`IsBallot`, `HasWeight`, `HasState`)
  and helpers for fetching, pinning, weighing and counting. An app uses the
  helpers it needs and writes its own code where one does not fit.

Extension is deployment: new templates implementing the released interfaces,
uploaded beside the released DARs. Nothing below re-opens a released interface
package.

## Extending a deployed system

A system already running on CAP grows by deploying new templates or new app
packages. The BabyDso example (`examples/governance/babydso`) shows each of
these.

- **A new governable target.** The owner of a contract writes an action that
  names it by an `AuthenticKey`, and a `HasCheckedFetch` instance that computes
  that key from the contract, usually with `signedKey`. The owner signs the
  action, and a governor can then authorize changes to the contract without
  depending on its package.
- **Eligibility.** A voter's right to vote is a contract the app registers, read
  through `HasWeight` and `readWeights`. Institutions bring their own identity
  or KYC system and mint these contracts only to eligible parties.
- **A drift policy per action.** Whether an approved outcome executes over a
  target that changed since it was pinned is decided by the `DriftPolicy` the
  action passes to `checkTarget`.
- **A timing profile.** Voting windows are fields of the app's ballots.
  Execution windows are the `opensAt`, `cancelAt` and `expiresAt` fields of the
  `ExecutionCore` the governor sets when it authorizes an execution.

## New governance formats

A new governance format is a new procedure: a choice on the app's governor that
counts its ballots with a quorum and a tally. `Quorum` and `Tally` are plain
functions, so a format that needs a rule the toolkit does not have writes it and
combines it with `rule`. A procedure that counts a new kind of vote adds a
constructor to the app's vote type. Vote delegation can be built the same way in
the future.

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
carries its own `lot`, `reserve`, seller accounts and settlement factories, and the
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
underneath is unchanged — one contract per bid, funds allocated before bidding,
one close fixing the set of bids, and settlement through the Token Standard all
carry over.

Continuous double auctions are the format that may reach further, into
cap-core itself, because a bid names its `Mechanism` when it is created.

## New domains on cap-core

`cap-core` is the foundation for further allocation-oriented modules. A new
domain is a package beside `cap-governance` and `cap-auctions` that uses the
core's stored types (`AuthenticKey`, `Mechanism`, `ExecutionCore`) and its
checks, and defines interfaces only where code has to work with contracts from
other packages. Each direction below is a further instantiation.

| Direction | Inputs are | Resolution is | Outcomes are |
| --- | --- | --- | --- |
| Order matching | orders | the matching rule | matched trades |
| Collateral allocation | pledges | the allocation rule | collateral commitments |
| Resource distribution | claims | the distribution rule | delivery obligations |


## Library growth after release

The released interface packages are frozen: each is its own `-v1` package, and
an adopter depends only on the interfaces they implement. The library grows
around them:

- **Toolkit growth.** New opt-in helpers as adopters surface reusable patterns.
  Toolkit growth never constrains a format; it only saves work.
- **Open result fields.** Outcomes travel as `AnyValue`, and the choice results
  and `ExecutionCore` carry a `meta` field, so they can grow without a new
  interface version.
- **A resolve-and-execute path.** A dedicated atomic path for the common case
  where an outcome is authorized and executed in the resolving transaction.
- **New interface packages.** New domains and new interface versions ship as
  additive packages.
