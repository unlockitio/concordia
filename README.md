<!-- SPDX-License-Identifier: Apache-2.0 -->

# Concordia — Canton Allocation Primitives (CAP)

> **Status — active development (pre-release).** Interfaces and design docs are
> in place and evolving; APIs and package layout may change without notice
> until the first tagged release.

Concordia is the reference implementation of **Canton Allocation Primitives
(CAP)** — an open-source [Daml](https://docs.daml.com) interface library for
privacy-preserving, multi-party allocation and decision workflows on the
[Canton Network](https://www.canton.network).

CAP targets the class of coordination problems where multiple parties submit
private inputs, a rule resolves those inputs into an outcome, and the outcome
triggers a downstream executable action — atomically, with on-ledger authority.
Auctions and governance are the two worked instances.

## Milestone 3 — Governance Module Expansion

M3 adds weighted voting to `cap-governance`: helpers for weighing ballots and
counting them, helpers for checking targets at execution, and a weighted voting
flow built on them.

| M3 deliverable | Where to look |
| --- | --- |
| Weighted-vote implementation | [`examples/governance/babydso/impl`](examples/governance/babydso/impl) — weights read by [`Weights.daml`](cap-governance/utils/daml/Cap/Governance/Utils/Weights.daml) and attached to ballots by [`Ballots.daml`](cap-governance/utils/daml/Cap/Governance/Utils/Ballots.daml) · [sequence diagrams](examples/governance/DEMOS-happy.md#babydso) |
| Quorum, threshold, and approval logic for supported governance formats | [`Count.daml`](cap-governance/utils/daml/Cap/Governance/Utils/Count.daml) — quorums (`atLeastVotes`, `atLeastWeight`, `shareOfTotal`), thresholds and tallies (`shareOfVotes`, `plurality`, `unanimous`, `weightedMedian`) combined by `rule` |
| Downstream execution hooks for approved proposals | [`ActionV1`](cap-governance/interfaces/action/daml/Cap/Governance/ActionV1.daml) and [`ExecutableV1`](cap-governance/interfaces/executable/daml/Cap/Governance/ExecutableV1.daml) · [`Targets.daml`](cap-governance/utils/daml/Cap/Governance/Utils/Targets.daml) |
| Daml Script and sandbox integration tests for supported governance formats | [`babydso/demo`](examples/governance/babydso/demo) and [`private-majority-vote/demo`](examples/governance/private-majority-vote/demo) — [how to run](#running-the-demos) · [`scripts/sandbox-test.sh`](scripts/sandbox-test.sh) — [how to run](#on-a-canton-sandbox) · library tests in [`cap-governance/tests`](cap-governance/tests) |

M3 tracks as [issue #540](https://github.com/canton-foundation/canton-dev-fund/issues/540).

## Milestone 2 — first executable slices in both proving domains

M2 adds one reference format per domain,
built on the same `cap-core`, each running as Daml Script and against
a Canton sandbox.


| M2 deliverable | Where to look |
| --- | --- |
| Majority-vote reference slice on `cap-core` | [`examples/governance/private-majority-vote`](examples/governance/private-majority-vote) — [sequence diagrams](examples/governance/DEMOS-happy.md#private-majority-vote) |
| Sealed-bid auction reference slice on `cap-core` | [`examples/auctions/sealed-bid-first-price`](examples/auctions/sealed-bid-first-price) — [demos](examples/auctions/sealed-bid-first-price/DEMOS.md) |
| Private ballot handling demonstrated | `whoSeesWhat` in [`MajorityVote/Demo.daml`](examples/governance/private-majority-vote/demo/daml/Cap/Examples/MajorityVote/Demo.daml) |
| Private bid handling demonstrated | `whoSeesWhat` — [auction demos](examples/auctions/sealed-bid-first-price/DEMOS.md) |
| Daml Script demos for both slices | `.../private-majority-vote/demo`, `.../sealed-bid-first-price/demo` — [how to run](#running-the-demos) |
| Sandbox integration tests for both slices | [`scripts/sandbox-test.sh`](scripts/sandbox-test.sh) — [how to run](#on-a-canton-sandbox) |



M2 tracks as [issue #539](https://github.com/canton-foundation/canton-dev-fund/issues/539).

## Milestone 1

| M1 deliverable | Where to look |
| --- | --- |
| Design document for `cap-core` | [`DESIGN.md`](DESIGN.md) |
| First-release scope and out-of-scope items | [`SCOPE.md`](SCOPE.md) |
| Extension points for downstream modules | [`POST-RELEASE.md`](POST-RELEASE.md) |
| Prototype of a typical workflow on Canton sandbox | [`examples/governance/babydso`](examples/governance/babydso) |

M1 tracks as [issue #538](https://github.com/canton-foundation/canton-dev-fund/issues/538).

## Architecture at a glance

Concordia has three tiers: a domain-agnostic core and two domains built on it.
CAP defines an interface only where code works with a contract whose template it
does not know at compile time. Everything else is stored types and helpers.

- **`cap-core`** — stored types (`AuthenticKey`, `Mechanism`, `ExecutionCore`)
  and `cap-core-utils` (checked fetches, mechanism checks, admission, windows,
  value conversion). It defines no interfaces.
- **`cap-governance`** — the `Action` and `Executable` interfaces, the stored
  type `Bind`, and `cap-governance-utils` with the modules `Targets` (pinning
  targets, and checking them at execution under a `DriftPolicy` the action
  supplies), `Ballots`, `Weights` and `Count` (quorums and tallies).
- **`cap-auctions`** — the `OneLotBid` and `Settlement` interfaces, utils and
  funding. Settlement composes with the Token Standard V2 rather than restating
  it.

A format author writes its own templates (rules contract, ballots, bids) and uses
the helpers it needs. CAP sits above Canton's asset and settlement layer.

## Repository layout

```
concordia/
├── cap-core/                          # Tier 1: domain-agnostic
│   ├── types/                         #   AuthenticKey, Mechanism, ExecutionCore
│   ├── utils/                         #   checked fetches, mechanisms, admission, windows, values
│   └── tests/{unit,ledger}
├── cap-governance/                    # Tier 2: governance
│   ├── types/                         #   Bind, ReadTarget, Verdict
│   ├── interfaces/{action,executable}
│   ├── utils/                         #   Targets, Ballots, Weights, Count
│   └── tests/{unit,ledger}
├── cap-auctions/                      # Tier 2: auctions (Token Standard V2)
│   ├── interfaces/{bid,settlement}
│   ├── utils/                         #   settlement legs and allocations for a one-lot bid
│   ├── funding/                       #   funding a bid through a Token Standard allocation request
│   ├── DESIGN.md
│   └── RATIONALE.md
├── examples/governance/
│   ├── babydso/                       # Splice DSO governance with weighted votes
│   │                                  #   impl/{ans,config,rights,governance,action}, demo
│   └── private-majority-vote/         # M2: private ballots, {impl,demo}
├── examples/auctions/
│   └── sealed-bid-first-price/        # M2: private bids, {impl,impostors,demo}
├── examples/lib/                      # vendored DARs only the examples need
├── lib/                               # vendored Token Standard DARs (prebuilt)
├── scripts/sandbox-test.sh            # sandbox integration run
├── .github/workflows/ci.yml           # build and test on push and pull request to dev
├── multi-package.yaml                 # dpm workspace (build order)
├── DESIGN.md                          # map of the design docs
├── cap-governance-rationale.md        # cap-governance design decisions
├── SCOPE.md                           # first-release scope, capability → milestone
├── POST-RELEASE.md                    # extension points for downstream modules
├── GLOSSARY.md                        # terms and where each is defined
├── CHANGELOG.md
├── LICENSE                            # Apache-2.0
└── README.md                          # this file
```

[`cap-governance-rationale.md`](cap-governance-rationale.md) and
[`cap-auctions/DESIGN.md`](cap-auctions/DESIGN.md) carry the tier-2 designs.
[`cap-auctions/RATIONALE.md`](cap-auctions/RATIONALE.md) records the auction
decisions.

## Building

Concordia builds with [`dpm`](https://docs.daml.com) (the Daml Project Manager),
**SDK 3.4.11**. From the repository root:

```bash
dpm build --all      # every package in dependency order, per multi-package.yaml
```

## Running the demos

**Quick check** — the in-memory script runner, no sandbox. Each package is run
from the repo root with `--package-root`:

```bash
# M2 — majority vote, all scripts ok
dpm test --package-root examples/governance/private-majority-vote/demo

# M2 — sealed-bid first price, all scripts ok
dpm test --package-root examples/auctions/sealed-bid-first-price/demo

# BabyDso with weighted votes, all scripts ok
dpm test --package-root examples/governance/babydso/demo
```

The library tests run the same way:

```bash
dpm test --package-root cap-core/tests/unit
dpm test --package-root cap-core/tests/ledger
dpm test --package-root cap-governance/tests/unit
dpm test --package-root cap-governance/tests/ledger
```

[`sealed-bid-first-price/DEMOS.md`](examples/auctions/sealed-bid-first-price/DEMOS.md)
says what the auction scripts assert, and what they deliberately do not.

### On a Canton sandbox

The same scripts run against a real ledger. `scripts/sandbox-test.sh` boots a
static-time sandbox, uploads the demo DARs and runs every script in each:

```bash
dpm build --all
./scripts/sandbox-test.sh
```

To drive one DAR by hand instead, start the sandbox yourself and point
`dpm script` at it — the scripts drive time, so `--static-time` is required on
both sides:

```bash
# terminal 1
dpm sandbox --static-time

# terminal 2
dpm script --all --ledger-host localhost --ledger-port 6865 \
  --static-time --upload-dar true \
  --dar examples/governance/private-majority-vote/demo/.daml/dist/cap-example-majority-vote-demo-0.1.0.dar
```

The other demo DARs, same shape:

```
examples/auctions/sealed-bid-first-price/demo/.daml/dist/cap-example-sealed-first-price-demo-0.1.0.dar
examples/governance/babydso/demo/.daml/dist/cap-example-babydso-demo-0.1.0.dar
```

Expected: every script reports `SUCCESS`, in the same counts `dpm test` reports
as `ok`.

## Contributing

External adopters and contributors are welcome to read the design, open issues,
and comment. The library is pre-release and its interfaces are still moving, so
please open an issue to discuss before substantial changes.

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).
