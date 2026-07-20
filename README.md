<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
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

## Architecture at a glance

Concordia is a **two-tier interface library**. Interface choice bodies are
fixed, so the library forces only what is universal and leaves everything a
legitimate format might vary to the implementing templates.

- **Tier 1 — `cap-core`.** Domain-agnostic interfaces: `Submittable` and
  `Mechanism` (the submission and mechanism faces), `Outcome`, the
  shared `ChecksV1` functions the domain fixed bodies call, and a `util`
  helper package. This tier captures the shared structure: privacy-preserving
  submission, one-door resolution, an optional completeness proof, and
  outcomes that execute with pre-committed authority.
- **Tier 2 — domain standards.** Each `requires` the tier-1 faces and adds its
  own methods:
  - **`cap-governance`** — governors, ballots, tally-driven resolution, and
    timelocked executables.
  - **`cap-auctions`** — sealed/open, single- and multi-unit auction formats,
    settling through the **Canton Token Standard V2 (CIP-0112)**, which it
    imports and versions in lockstep with.

A workflow author writes templates that implement a tier-2 interface; tier 1
stays stable while tier 2 absorbs domain divergence. Concordia sits **above**
Canton's asset and settlement layer — outcomes compose with the Token Standard
a team already uses.

## Repository layout

```
concordia/
├── cap-core/                       # Tier 1: domain-agnostic interfaces
│   ├── Interfaces/                 #   mechanism, submittable, outcome
│   ├── internal/checks/            #   library-only fixed-body checks the interfaces call
│   └── util/                       #   public toolkit: checked-fetch, patchable, policies, time
├── cap-governance/                 # Tier 2: governance
│   ├── Interfaces/                 #   target, outcome, ballot, governor
│   ├── util/
│   └── examples/BabyDso/           #   Splice DSO reproduced twice — see below
│       ├── original/{Impl,Test}    #     plain-Daml oracle + its demo scripts
│       └── cap-version/{,Test}     #     same mechanisms on cap + its demo scripts
├── cap-auctions/                   # Tier 2: auctions (Token Standard V2, CIP-0112)
│   ├── Interfaces/                 #   types, bid, outcome, auction
│   └── examples/                   #   reference auction implementation
├── lib/                            # vendored Token Standard DARs (prebuilt binaries)
├── docs/                           # architecture docs (threat models inline) and glossary
├── multi-package.yaml              # dpm workspace (build order + data-dependencies)
├── CHANGELOG.md
├── LICENSE                         # Apache-2.0
└── README.md                       # this file
```

## Documentation

The core reads:

- [`docs/cap-core-architecture.md`](docs/cap-core-architecture.md) — the base
  model, the enforcement boundary, and the completeness proof.
- [`docs/cap-governance-architecture.md`](docs/cap-governance-architecture.md)
  and [`docs/cap-auctions-architecture.md`](docs/cap-auctions-architecture.md)
  — the two standards, each ending in a **Threat model** section.
- [`docs/glossary.md`](docs/glossary.md) — one entry per concept; every doc
  uses exactly these terms.

## Building

Concordia builds with [`dpm`](https://docs.daml.com) (the Daml Project
Manager, SDK 3.4+). From the repository root:

```bash
dpm build --all      # build every package in dependency order
dpm test             # run the Daml Script tests
```

`multi-package.yaml` lists the packages that currently build and their order;
some tier-2 tests and the auctions example are held back pending the tier-2
rework noted there.

## Running the demos

The **BabyDso** example reproduces Splice's DSO governance twice — once in
plain Daml (`original/`) and once on the cap interfaces (`cap-version/`) — and
runs the same demo scripts in both, so the diff is exactly what the cap
standard adds. The full catalogue (six demos, what each proves) is in
[`cap-governance/examples/BabyDso/DEMOS.md`](cap-governance/examples/BabyDso/DEMOS.md).

Prerequisite: **Daml SDK 3.4.11** via `dpm`. Build the DARs first:

```bash
dpm build --all        # from the repo root (uses multi-package.yaml)
```

**Quick check** — the in-memory script runner, no sandbox. Run each Test
package from the repo root with `--package-root`:

```bash
# cap version
dpm test --package-root cap-governance/examples/BabyDso/cap-version/Test

# plain-Daml oracle
dpm test --package-root cap-governance/examples/BabyDso/original/Test
```

**On a Canton sandbox** (the Milestone 1 deliverable) — two terminals. The
scripts drive time, so `--static-time` is required on both sides. DAR paths are
given in full from the repo root:

```bash
# terminal 1 — a FRESH sandbox (restart between runs; party allocations persist)
dpm sandbox --static-time

# terminal 2 — the cap version
dpm script --all --ledger-host localhost --ledger-port 6865 \
  --static-time --upload-dar true \
  --dar cap-governance/examples/BabyDso/cap-version/Test/.daml/dist/cap-final-test-0.1.0.dar

# the plain-Daml oracle, same shape
dpm script --all --ledger-host localhost --ledger-port 6865 \
  --static-time --upload-dar true \
  --dar cap-governance/examples/BabyDso/original/Test/.daml/dist/baby-dso-test-0.1.0.dar
```

Every demo reports `SUCCESS` on the sandbox (`ok` under `dpm test`).

## Contributing

External adopters and contributors are welcome to read the docs, open issues,
and comment. The library is pre-release and its interfaces are still moving, so
please open an issue to discuss before substantial changes.

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).
