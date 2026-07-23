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

## Milestone 1

Each deliverable maps to one place in the repo:

| M1 deliverable | Where to look |
| --- | --- |
| Design document for `cap-core` | [`DESIGN.md`](DESIGN.md) |
| First-release scope and out-of-scope items | [`SCOPE.md`](SCOPE.md) |
| Extension points for downstream modules | [`POST-RELEASE.md`](POST-RELEASE.md) |
| Prototype of a typical workflow on Canton sandbox | [`examples/BabyDso/DEMOS.md`](examples/BabyDso/DEMOS.md) — [how to run](#running-the-demos) |

M1 tracks as [issue #538](https://github.com/canton-foundation/canton-dev-fund/issues/538).

## Architecture at a glance

Concordia is a **two-tier library**. Composed of domain-agnostic interfaces
and domain standards that require cap-core and add their own methods and interfaces:

A workflow author writes templates that implements the interfaces of both cap-core and cap-governance; CAP sits **above**
Canton's asset and settlement layer — auction outcomes compose with the Token Standard.

## Repository layout

```
concordia/
├── cap-core/                       # Tier 1: domain-agnostic interfaces
│   ├── Interfaces/                 #   mechanism, submittable, outcome
│   ├── internal/checks/            #   library-only fixed-body checks the interfaces call
│   └── util/                       #   public toolkit: checked-fetch, patchable, policies, time
├── cap-governance/                 # Tier 2: governance
│   ├── Interfaces/                 #   target, outcome, ballot, governor
│   └── util/
├── cap-auctions/                   # Tier 2: auctions (Token Standard V2, CIP-0112)
│   └── Interfaces/                 #   types, bid, outcome, auction
├── examples/BabyDso/               # Splice DSO reproduced twice — see below
│   ├── original/{Impl,Test}        #   plain-Daml reference + its demo scripts
│   └── cap-version/{impl,Test}     #   same mechanisms on cap + its demo scripts
├── lib/                            # vendored Token Standard DARs (prebuilt binaries)
├── docs/                           # architecture docs (threat models inline) and glossary
├── multi-package.yaml              # dpm workspace (build order + data-dependencies)
├── CHANGELOG.md
├── LICENSE                         # Apache-2.0
└── README.md                       # this file
```


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
[`examples/BabyDso/DEMOS.md`](examples/BabyDso/DEMOS.md).

Prerequisite: **Daml SDK 3.4.11** via `dpm`. Build the DARs first:

```bash
dpm build --all        # from the repo root (uses multi-package.yaml)
```

**Quick check** — the in-memory script runner, no sandbox. Run each Test
package from the repo root with `--package-root`:

```bash
# cap version
dpm test --package-root examples/BabyDso/cap-version/Test

# plain-Daml reference
dpm test --package-root examples/BabyDso/original/Test
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
  --dar examples/BabyDso/cap-version/Test/.daml/dist/cap-version-test-0.1.0.dar

# the plain-Daml reference, same shape
dpm script --all --ledger-host localhost --ledger-port 6865 \
  --static-time --upload-dar true \
  --dar examples/BabyDso/original/Test/.daml/dist/baby-dso-test-0.1.0.dar
```

Every demo reports `SUCCESS` on the sandbox (`ok` under `dpm test`).

## Contributing

External adopters and contributors are welcome to read the docs, open issues,
and comment. The library is pre-release and its interfaces are still moving, so
please open an issue to discuss before substantial changes.

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).
