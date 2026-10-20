# Milestone 3 delivery: Concordia (CAP) - Governance Module Expansion

Hello everyone — the Milestone 3 deliverables are complete and public in the project repository: **https://github.com/unlockitio/concordia** (Apache-2.0, branch `dev`).

M3 expands `cap-governance` with helpers that governance apps need: reading weights and weighing ballots, quorums and tallies, and pinning the targets of a decision and checking them again at execution. Two governance formats are built on these helpers: Baby-dso, with a weighted vote flow, and the private majority vote from M2, which now uses the same library.

**What changed since M2.** 
- `cap-governance` now defines only two interfaces, `Action` and `Executable`. The `Ballot`, `Resolver`, `Governor` and `Submittable` interfaces were removed and replaced by helpers and type classes. The reasons are recorded in [`cap-governance-rationale.md`](https://github.com/unlockitio/concordia/blob/dev/cap-governance-rationale.md#when-cap-uses-an-interface) (we would greatly appreciate feedback on these decisions).
- `cap-core` and `cap-governance` have their own unit and ledger tests, and CI builds every package and runs every test and demo on each push to `dev`.

## Deliverables

| M3 deliverable | Where to look |
| --- | --- |
| Weighted-vote implementation | [`babydso/impl`](https://github.com/unlockitio/concordia/tree/dev/examples/governance/babydso/impl): SVs vote with weights read from weight contracts by [`Weights.daml`](https://github.com/unlockitio/concordia/blob/dev/cap-governance/utils/daml/Cap/Governance/Utils/Weights.daml) and attached to ballots by `weighBallots` in [`Ballots.daml`](https://github.com/unlockitio/concordia/blob/dev/cap-governance/utils/daml/Cap/Governance/Utils/Ballots.daml). A parameter vote resolves to the `weightedMedian`, with a quorum of two thirds of the total weight. [Sequence diagrams](https://github.com/unlockitio/concordia/blob/dev/examples/governance/DEMOS-happy.md#babydso) |
| Quorum, threshold, and approval logic for supported governance formats | [`Count.daml`](https://github.com/unlockitio/concordia/blob/dev/cap-governance/utils/daml/Cap/Governance/Utils/Count.daml): quorums (`atLeastBallots`, `atLeastVotes`, `atLeastWeight`, `shareOfTotal`, `allOf`, `anyOf`) and tallies (`unanimous`, `plurality`, `shareOfVotes`, `median`, `weightedMedian`, `firstOf`), combined by `rule` into a `Verdict` (`Decided`, `Undecided` or `Lapsed`). |
| Downstream execution hooks for approved proposals | The [`Action`](https://github.com/unlockitio/concordia/blob/dev/cap-governance/interfaces/action/daml/Cap/Governance/ActionV1.daml) and [`Executable`](https://github.com/unlockitio/concordia/blob/dev/cap-governance/interfaces/executable/daml/Cap/Governance/ExecutableV1.daml) interfaces, and [`Targets.daml`](https://github.com/unlockitio/concordia/blob/dev/cap-governance/utils/daml/Cap/Governance/Utils/Targets.daml) for pinning targets and checking them at execution under a `DriftPolicy`. [Diagram and rationale](https://github.com/unlockitio/concordia/blob/dev/cap-governance-rationale.md#action-and-executable) |
| Daml script and sandbox integration tests for supported governance formats | [`babydso/demo`](https://github.com/unlockitio/concordia/tree/dev/examples/governance/babydso/demo) and [`private-majority-vote/demo`](https://github.com/unlockitio/concordia/tree/dev/examples/governance/private-majority-vote/demo), run in memory and on a sandbox by [`scripts/sandbox-test.sh`](https://github.com/unlockitio/concordia/blob/dev/scripts/sandbox-test.sh). Library tests in [`cap-governance/tests`](https://github.com/unlockitio/concordia/tree/dev/cap-governance/tests) |

## Milestone verification

Daml SDK 3.5.11 via `dpm`, from the repository root. Run instructions, in memory and on a sandbox, are in [`README.md#running-the-demos`](https://github.com/unlockitio/concordia/blob/dev/README.md#running-the-demos).

| Package | Check | Expected output | What it covers |
| --- | --- | --- | --- |
| all packages | `dpm build --all` | every package in `multi-package.yaml` compiles | — |
| [Baby-dso](https://github.com/unlockitio/concordia/tree/dev/examples/governance/babydso) | `dpm test --package-root examples/governance/babydso/demo` | 14 scripts report **`ok`** | weighted votes and weight sources (`WeightDemo`), drift checks at execution (`ExecutionDemo`), the full flow (`Demo`) |
| [private majority vote](https://github.com/unlockitio/concordia/tree/dev/examples/governance/private-majority-vote) | `dpm test --package-root examples/governance/private-majority-vote/demo` | 3 scripts report **`ok`** | one voter one vote, the count covers the electorate, ballot privacy (`whoSeesWhat`) |
| `cap-governance` unit tests | `dpm test --package-root cap-governance/tests/unit` | 21 scripts report **`ok`** | quorums, tallies and drift policies |
| `cap-governance` ledger tests | `dpm test --package-root cap-governance/tests/ledger` | 36 scripts report **`ok`** | admitting votes, reading weights, pinning and checking targets |
| all demos, on a ledger | `./scripts/sandbox-test.sh` | 22 scripts report **`SUCCESS`** | the Baby-dso, majority vote and M2 auction demos |

## Notes for reviewers

We're happy to answer questions here or walk through any part of the governance module.
We would greatly value your feedback.
