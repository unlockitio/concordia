# Changelog

All notable changes to Concordia are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project is
pre-release, so interfaces may change without notice until the first tag.

## [Unreleased]

Initial open-source release of CAP (Canton Allocation Primitives).

### Changed
- **The authority is a set** — `authority : Party` became
  `authorities : Set Party` on `ResolvableView`, `SubmittableView`,
  `TargetView`, and `ExecutableView`, and in the `ForAuthority` / `ForTarget`
  group keys; the fixed checks require every member's signature (non-empty,
  via `authoritySigned`), and `Submittable_Consume` / `Ballot_Consume` are
  controlled by the whole set. A single operator is the singleton case; a
  consortium with no privileged member is a real set. Combination rules stay
  out of the frozen layer
  (`docs/design-decisions/authority-set-anchor.md`).

### Added
- **`cap-core`** — domain-agnostic interfaces: `Metadata`, `Submittable` /
  `SubmittableState`, `Resolvable`, `Target` (identity plus optional
  state-token pinning, with `TargetCommitment` / `checkTargetBinding`),
  `Outcome`, the shared `Cap.Core.ChecksV1` functions, and
  `cap-core-util` (`Cap.Core.Patchable`, `Cap.Core.TargetPolicies` — the
  named drift profiles).
- **Target state binding in `cap-governance`** — `ExecutableView` commits to
  `committedTargets` (identity, and state where pinned), and
  `Executable_Execute` checks the presented targets against them and routes
  state drift to the format-wired `onTargetDrift` policy. That the committed
  targets match the vote is an `issueExecutables` obligation the authority
  signature vouches for.
- **`cap-governance`** — the governance standard: `Proposal`, `Ballot` via the
  `executable` and `proposal` interfaces, plus `util`.
- **`cap-auctions`** — the auctions standard: `Auction`, `Bid`,
  `AuctionOutcome`, and shared `types`, composing with the Canton Token
  Standard V2 (CIP-0112).
- **`cap-governance/examples/BabyDso`** — a plain-Daml Splice governance
  reproduction (the `baby-dso` package), with golden-vector scripts in a
  separate `baby-dso-test` package.
- **`docs/`** — architecture docs for each tier (each domain doc ending in a
  Threat model section), a walkthrough, and a glossary.
