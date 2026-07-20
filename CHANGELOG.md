# Changelog

All notable changes to Concordia are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project is
pre-release, so interfaces may change without notice until the first tag.

## [Unreleased]

Initial open-source release of CAP (Canton Allocation Primitives).

### Changed
- **Shared value types come from the Token Standard directly** — the mirrored
  `cap-core-metadata-v1` package is removed; `Metadata`, `AnyValue`,
  `ChoiceContext`, `ExtraArgs`, and `AnyContract` now come from the Token
  Standard's own `splice-api-token-metadata-v1` (module
  `Splice.Api.Token.MetadataV1`). Digital Asset's official released DAR is
  vendored as a pre-built binary at `lib/` (Splice `v0.5.18`,
  package-id `4ded6b6…`) and depended on directly, so cap types carry the
  canonical on-ledger package-id rather than a local recompile's. This drops the
  mechanical bridging at every registry-supplied context boundary; the
  standard's metadata package is stable (it did not major to V2). The
  `emptyExtraArgs` convenience the mirror carried is supplied locally where used.
- **Outcomes carry their provenance** — `OutcomeView` gains a mandatory
  `source : AnyContractId` (the anchor of the contract whose choice issued the
  outcome — compared, never fetched), with a `HasCheckedFetch OutcomeView
  ForMechanism` instance so relying contracts admit "the outcome of *this*
  mechanism"; `ForAuthority` stays as the coarse trust-domain group.
  `governor_issueOutcome` now receives the governor's own anchor and MUST pin it as
  each issued outcome's `source`.
- **Checked-fetch admission discipline** — `Cap.Core.CheckedFetchV1` now states
  when each group suffices: a cid supplied by the exercising actor MUST be
  admitted under an instance-level group (`ForMechanism`, `ForTarget`);
  `ForAuthority` alone MAY admit only a cid read from a signed field of a
  contract already admitted.
- **The authority is a set** — `authority : Party` became
  `authorities : Set Party` on `MechanismView`, `SubmittableView`,
  `TargetView`, and `ExecutableView`, and in the `ForAuthority` / `ForTarget`
  group keys; the fixed checks require every member's signature (non-empty,
  via `authoritySigned`), and `Submittable_Consume` / `Ballot_Consume` are
  controlled by the whole set. A single operator is the singleton case; a
  consortium with no privileged member is a real set. Combination rules stay
  out of the frozen layer
  (`docs/design-decisions/authority-set-anchor.md`).

### Added
- **Median voting in BabyDso** — Splice's `AmuletPriceVote` mechanism in both
  variants. In the **original** oracle, reproduced verbatim: the standing
  per-SV `AmuletPriceVote` (minted `None` at onboarding, backdated past the
  cooldown), `DsoRules_UpdateAmuletPriceVote` under the recast cooldown with the
  `ForOwner` checked fetch, and `DsoRules_AdvanceOpenMiningRounds` running
  `fetchMedianAmuletPrice` ("every sv voted exactly once", `None` = abstain)
  onto a minimal `AmuletRules`. In **GovernedDP**, as a cap-governance format:
  a new `medianValue` reference tally in
  `Cap.Governance.Tallies` (the median rides the `Verdict` option channel into
  `governor_issueOutcome`), a slotted per-SV `AmuletPriceVote` ballot under a
  completeness-declaring `AmuletPriceVoteRequest` governor (exact slot cover is
  Splice's "every sv voted exactly once"; an uncast slot its `None` price), and
  `AmuletRules` — the standing price target — replacing the placeholder
  `DummyRules` (`GA_SetDummyValue` becomes `GA_SetAmuletPrice`).
- **`Outcome_Expire`** — anyone with visibility can archive an outcome past its
  `executeUntil` (time-guarded, effect-free), closing the one lifecycle face
  that had no cleanup door; an undated outcome remains inexpirable.
- **`cap-core`** — domain-agnostic interfaces: `Metadata`, `Submittable` /
  `SubmittableState`, `Mechanism`, `Target` (identity plus optional
  state-token pinning, with `TargetCommitment` / `checkTargetBinding`),
  `Outcome`, the shared `Cap.Core.ChecksV1` functions, and
  `cap-core-util` (`Cap.Core.Patchable`, `Cap.Core.TargetPolicies` — the
  named drift profiles).
- **Target state binding in `cap-governance`** — `ExecutableView` commits to
  `committedTargets` (identity, and state where pinned), and
  `Executable_Execute` checks the presented targets against them and routes
  state drift to the format-wired `onTargetDrift` policy. That the committed
  targets match the vote is a `governor_issueOutcome` obligation the authority
  signature vouches for.
- **`cap-governance`** — the governance standard: `Governor`, `Ballot` via the
  `executable` and `governor` interfaces, plus `util`.
- **`cap-auctions`** — the auctions standard: `Auction`, `Bid`,
  `AuctionOutcome`, and shared `types`, composing with the Canton Token
  Standard V2 (CIP-0112).
- **`cap-governance/examples/BabyDso`** — a plain-Daml Splice governance
  reproduction (the `baby-dso` package), with golden-vector scripts in a
  separate `baby-dso-test` package.
- **`docs/`** — architecture docs for each tier (each domain doc ending in a
  Threat model section), a walkthrough, and a glossary.
