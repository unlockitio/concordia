<!-- Copyright (c) 2026 Unlockit -->
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

## Milestone 2 — first executable slices in both proving domains

M2 adds one reference format per domain,
built on the same `cap-core`, each running as Daml Script and against
a Canton sandbox.


| M2 deliverable | Where to look |
| --- | --- |
| Majority-vote reference slice on `cap-core` | [`examples/governance/private-majority-vote`](examples/governance/private-majority-vote) — [demos](examples/governance/private-majority-vote/DEMOS.md) |
| Sealed-bid auction reference slice on `cap-core` | [`examples/auctions/sealed-bid-first-price`](examples/auctions/sealed-bid-first-price) — [demos](examples/auctions/sealed-bid-first-price/DEMOS.md) |
| Private ballot handling demonstrated | `whoSeesWhat` — [governance demos](examples/governance/private-majority-vote/DEMOS.md) |
| Private bid handling demonstrated | `whoSeesWhat` — [auction demos](examples/auctions/sealed-bid-first-price/DEMOS.md) |
| Daml Script tests for both slices | `.../private-majority-vote/test`, `.../sealed-bid-first-price/test` — [how to run](#running-the-demos) |
| Sandbox integration tests for both slices | [`scripts/sandbox-test.sh`](scripts/sandbox-test.sh) — [how to run](#on-a-canton-sandbox) |



M2 tracks as [issue #539](https://github.com/canton-foundation/canton-dev-fund/issues/539).

## Milestone 1

| M1 deliverable | Where to look |
| --- | --- |
| Design document for `cap-core` | [`DESIGN.md`](DESIGN.md) |
| First-release scope and out-of-scope items | [`SCOPE.md`](SCOPE.md) |
| Extension points for downstream modules | [`POST-RELEASE.md`](POST-RELEASE.md) |
| Prototype of a typical workflow on Canton sandbox | [`examples/governance/baby-dso`](examples/governance/baby-dso/DEMOS.md) |

M1 tracks as [issue #538](https://github.com/canton-foundation/canton-dev-fund/issues/538).

## Architecture at a glance

Concordia is a **three-tier library**: domain-agnostic interfaces in `cap-core`,
and per-domain standards that require them and add their own.

- **`cap-core`** — `Submittable` (a private input, admitted by a declared check)
  and `Resolver` (the mechanism: window, authority set, resolution), plus
  `cap-core-utils`, the implementer's toolkit.
- **`cap-governance`** — `Ballot`, `AuthenticTarget` (the state an execution acts
  on, and what drift means for it), `Action` (an effect arriving from a package
  deployed after the core), `Executable`.
- **`cap-auctions`** — `OneLotBid`, `Settlement`, and a registry binding;
  settlement composes with the Token Standard V2 rather than restating it.

A format author writes templates implementing the tiers they need. CAP sits
**above** Canton's asset and settlement layer.

## Repository layout

```
concordia/
├── cap-core/                          # Tier 1: domain-agnostic
│   ├── Interfaces/{submittable,resolver}
│   └── cap-core-utils/                #   admission, execution, patch, time, value
├── cap-governance/                    # Tier 2: governance
│   ├── Interfaces/{binding,executable,action,ballot}
│   └── cap-governance-utils/          #   drift
├── cap-auctions/                      # Tier 2: auctions (Token Standard V2)
│   ├── Interfaces/{bid,settlement}
│   ├── cap-auctions-registry/
│   └── cap-auctions-utils/
├── examples/governance/
│   ├── baby-dso/                      # M1: Splice DSO governance
│   │   ├── plain/                     #   the shape being argued against
│   │   └── cap/{ans,config,governance,action,test}
│   └── private-majority-vote/         # M2: private ballots, {impl,test}
├── examples/auctions/
│   └── sealed-bid-first-price/        # M2: private bids, {impl,fixtures,test}
├── lib/                               # vendored Token Standard DARs (prebuilt)
├── scripts/sandbox-test.sh            # sandbox integration run
├── multi-package.yaml                 # dpm workspace (build order)
├── DESIGN.md                          # cap-core design (threat models inline)
├── SCOPE.md                           # first-release scope, capability → milestone
├── POST-RELEASE.md                    # extension points for downstream modules
├── CHANGELOG.md
├── LICENSE                            # Apache-2.0
└── README.md                          # this file
```

`cap-governance/DESIGN.md` and `cap-auctions/DESIGN.md` carry the tier-2 designs.

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
dpm test --package-root examples/governance/private-majority-vote/test

# M2 — sealed-bid first price, all scripts ok
dpm test --package-root examples/auctions/sealed-bid-first-price/test

# M1 — BabyDso on cap, all scripts ok
dpm test --package-root examples/governance/baby-dso/cap/test
```

Each `DEMOS.md` says what its scripts assert, and what they deliberately do not.

### On a Canton sandbox

The same scripts run against a real ledger. `scripts/sandbox-test.sh` boots a
static-time sandbox, uploads the test DARs and runs every script in each:

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
  --dar examples/governance/private-majority-vote/test/.daml/dist/cap-example-majority-vote-test-0.1.0.dar
```

The other test DARs, same shape:

```
examples/auctions/sealed-bid-first-price/test/.daml/dist/cap-example-sealed-first-price-test-0.1.0.dar
examples/governance/baby-dso/cap/test/.daml/dist/cap-example-babydso-test-0.1.0.dar
```

Expected: every script reports `SUCCESS`, in the same counts `dpm test` reports
as `ok`.

## Contributing

External adopters and contributors are welcome to read the design, open issues,
and comment. The library is pre-release and its interfaces are still moving, so
please open an issue to discuss before substantial changes.

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).
