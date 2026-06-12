# Concordia — Canton Allocation Primitives (CAP)

> **Status — M1 (Core Design and Scope Definition) in progress.**
> This repository tracks active development; Things will change without notice until the M6 release.

Concordia is the reference implementation of **Canton Allocation Primitives (CAP)** — an open-source DAML library for privacy-preserving multi-party allocation and decision workflows on the Canton Network.

CAP gives developers a reusable foundation for the class of coordination problems where multiple parties submit private inputs, a rule resolves those inputs into an outcome, and the outcome can trigger a downstream executable action — atomically, with on-ledger authority. Allocation problems of this shape appear in multi-party workflows such as auctions and voting.

## Architecture at a glance

Concordia is structured as a **two-tier library**:

- **Tier 1 — `cap-core`.** Domain-agnostic Daml interfaces: `Submittable`, `Resolvable`, `ExecutableOutcome`, factories. Not implemented by end-user templates. This tier captures the shared structure of allocation workflows: privacy-preserving submission, deterministic resolution, executable outcomes carrying pre-committed authority, deadlines, and idempotent termination.
- **Tier 2 — domain standards.** Each domain standard extends tier 1, adds domain-specific methods, and lives in its own package. The first release (M6) ships two:
  - **`cap-governance`** — proposal lifecycle, ballot submission, quorum / threshold / weighted approval rules, post-approval execution hooks.
  - **`cap-auctions`** — sealed-bid first / second price, Dutch, multi-unit auction formats.

A workflow author writes templates that implement a tier-2 interface. Tier 1 stays stable; tier 2 absorbs domain divergence; future modules extend the same tier-1 surface without rewriting it.

Concordia sits **above** Canton's asset and settlement infrastructure — allocation and decision mechanics produce executable outcomes that compose with the settlement layers a team already uses.

## Repository layout

```
concordia/
├── cap-core/                    # Tier 1: domain-agnostic Daml interfaces
├── cap-governance/              # Tier 2: governance standard (M3 expansion)
├── cap-auctions/                # Tier 2: auctions standard (M4 expansion)
├── cap-test/                    # Daml Script tests and example flows
├── docs/                        # Design docs, divergence axes, invariants
├── .github/workflows/           # CI workflow (build + test on push and PR)
├── CHANGELOG.md                 # Notable changes per milestone
├── LICENSE                      # Apache License 2.0
└── README.md                    # this file
```

## Documentation

The `docs/` folder collects the design artifacts that inform implementation. Working drafts during M1, stabilising into the formal design document at the close of M1.

## Project context

Concordia is a Canton Network Development Fund proposal. The full proposal — scope, milestones, deliverables, acceptance criteria — is referenced in the development-fund tracker; the canonical specification is maintained alongside the repository through M1 and folded into `docs/design.md` at milestone close.

Public development continues through the entire 6-month engineering window. External adopters and contributors are welcome to read, comment, and open issues; substantive contributions are coordinated post-M6.

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).
