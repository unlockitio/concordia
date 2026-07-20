<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# BabyDso demos

BabyDso reproduces Splice's DSO governance twice: `original/` is an idiomatic
plain-Daml reproduction with no cap dependency, and `cap-version/` implements
the same mechanisms on the cap interfaces. Every demo below runs in **both**
packages under the same name, with the same parties, action, and step order —
so the diff between the two scripts is exactly what the cap standard adds.
Demos 1–3 walk Splice's three different mechansims end to end. Demos 4–5 exercise
scenarios outside the scope Splice was designed for — reasonable decisions for
a single application on its own network, but that a reusable standard must
generalize.

| # | Demo | Splice mechanisms | What it shows in one line |
|---|---|---|---|
| 0 | `demo_ballot_box` | — (cap-version only) | The container ballot format: Splice's single-contract vote map on the same doors |
| 1 | `demo_voting` | `VoteRequest` (linear) | Full lifecycle: request → cast → close → execute, plus concurrent-outcome handling |
| 2 | `demo_confirmation` | `Confirmation` (standing, per-decision) | A quorum built asynchronously; execution consumes it at threshold |
| 3 | `demo_median` | `AmuletPriceVote` (standing, persistent) | One standing vote per SV, re-read every round; the median needs exactly the electorate |
| 4 | `demo_two_organizations` | generalization | Two organizations sharing one authority party: what keeps a decision inside its institution |
| 5 | `demo_rotating_cid` | generalization | An action that names a contract id vs. one that names a key, across a benign contract rotation |



## Demo 0 — `demo_ballot_box`

`cap-version` only, no `original` counterpart: it exists just to show that
cap-governance also supports Splice's own ballot shape — one authority-signed
contract accumulating every SV's vote in a map (`VoteBox`), recreated on each
cast. Three SVs cast into the same contract through the same `Ballot_Cast`
door the per-voter format uses, and the same close door resolves it at the
same threshold. The rest of the demos use the per-voter format, which is what
dissolves Splice's cast cooldown and contention.

## Demo 1 — `demo_voting`

Four SVs bootstrap a DSO; one requests a `SetConfig` vote — in `cap-version`
the request, the requester's yes, and every SV's voting right mint in one
transaction, mirroring Splice's auto-accept — three accept, the close passes
the supermajority threshold, and the new config lands on `DsoRules`. Negative
beat: a non-SV cannot cast. Then two concurrent config
votes prepared against the same base but touching different fields both close,
and the second patches onto the first instead of reverting it — Splice's
field-wise `Patchable` merge in `original`, the same merge as cap's `SetState`
delta in `cap-version`. The cap version closes with a state-pinned beat: a
reserve payout is approved by vote at price 10.0, the confirmation mechanism
moves the price before the close, and the close — which resolves, issues the
outcome, and executes it in one transaction — aborts whole at `driftAborts`,
so no stale approval enacts and no false acceptance record commits.

## Demo 2 — `demo_confirmation`

A grant-shaped action through the confirmation mechanism: two of four SVs
confirm, execution fails below the threshold, the rest confirm, and execution
consumes three confirmations and enacts the action. Negative beat: a spent
confirmation cannot be presented again. A *live* confirmation, though, is valid
for its whole time window: the demo gathers one more confirmation than the
threshold, executes once, then combines the leftover — created for that first
decision — with fresh ones into a **second execution of the same action**, one
its confirmer never intended. In `original` (grant stand-in `NoOp`) this second
execution is admitted: nothing between the TTL and action equality scopes
*when* a standing approval may still be used, which is why Splice keeps
confirmable actions idempotent by convention. In `cap-version` that scope is
declared per action — each action's whole meaning (target, pin, drift policy,
effect) is one `ActionSpec` record. The `GA_SetTransferFee` action is
identity-pinned and replays exactly as in `original`. State-pinned actions
instead pin the state their confirmers saw (the target's state token) and
route drift to their own declared policy: the demo confirms three
`GA_SetAmuletPrice` actions and a `GA_PayFromReserve` payout against the same
state, executes the first price, and shows the second still executing under
its tolerance-bounded policy, the third refused for drifting beyond it, the
payout — under the strict policy — refused by the very drift the price
tolerated until re-approved against the live state, and a stale leftover
unable to aggregate with fresh confirmations at all.

## Demo 3 — `demo_median`

The standing electorate: each SV holds one persistent price vote, re-read at
every round advance. Three of four SVs set a price, the round advances at the
median, and the unpriced SV covers the electorate without counting toward it.
Negative beat: advancing with a cherry-picked subset of votes fails — the
median is only sound over exactly the current electorate, proven at the
advance door by exact cover against the live SV list (an unpriced vote covers
its SV without counting). In the cap version every standing vote is admitted
under the anchor's binding group, and pricing runs through the same
`Ballot_Cast` door the voting mechanism uses.

## Demo 4 — `demo_two_organizations`

Two `DsoRules` instances — different configs, different organizations — hosted
by the same `dso` authority party. A `SetConfig` vote is opened and approved
under institution 1, then closed presenting institution 2's rules. Splice's
admission is keyed by the authority party, which both organizations share, so
in `original` the close passes and institution 1's action executes on
institution 2's state — outside Splice's design scope, since it runs as the
sole instance on its own network. In `cap-version` each organization is its
own target `id` on `DsoRules`, and the same replay is refused twice, at named
checks: closing org 1's approved request against org 2's rules dies at the
outcome's target binding, and resolving org 2's own request with org 1's
ballots dies at the ballots' contract-identity binding. The honest close under
organization 1 then succeeds unchanged, and organization 2 never moves.

## Demo 5 — `demo_rotating_cid`

A governance action that embeds a `ContractId` (here of `AmuletRules`,
standing in for Splice's `ARC_AnsEntryContext` shape). In `original` the
confirmed action executes while the pinned instance is live; after the
contract is benignly recreated it can never execute — it names a dead contract
id — and an SV who confirmed while seeing the rotated id names a different
action value, so the two confirmation sets can never combine to a threshold.
Both dead ends arise from ordinary operation, with no adversary. In
`cap-version` no action value can carry a contract id — actions bind their
target by key — so the same beats run on a fee write: confirmations gathered
before and after the rotation converge, execution re-fetches the live contract
by key, and the action executes.

## Running the demos

A simplified prototype of a typical `cap-core` workflow on
a Canton sandbox. These demos are that prototype: they run either in-memory for
a quick check, or against a live **Canton sandbox** over gRPC. Both packages'
demos live in
`cap-governance/examples/BabyDso/original/Test/daml/Cap/BabyDso/Test/Demos.daml`
and
`cap-governance/examples/BabyDso/cap-version/Test/daml/Cap/Final/Test/Demos.daml`.

Prerequisite: **Daml SDK 3.4.11** via `dpm`

**Build** the DARs from the repository root — `--all` builds the whole
workspace, including both example `Test/` packages, in dependency order:

```bash
dpm build --all        # from the repo root (uses multi-package.yaml)
```

**Quick check** (in-memory script runner, no sandbox) — run each Test package
from the repo root with `--package-root`, so the whole path names which one you
are testing:

```bash
# cap version
dpm test --package-root cap-governance/examples/BabyDso/cap-version/Test

# original oracle
dpm test --package-root cap-governance/examples/BabyDso/original/Test
```

**On a Canton sandbox** (the Milestone 1 deliverable) — two terminals. The
scripts drive time, so `--static-time` is required on both the sandbox and the
runner. DAR paths are given in full from the repo root:

```bash
# terminal 1 — a FRESH sandbox
dpm sandbox --static-time

# terminal 2 — uploads the DAR and runs every demo over gRPC (cap version)
dpm script --all --ledger-host localhost --ledger-port 6865 \
  --static-time --upload-dar true \
  --dar cap-governance/examples/BabyDso/cap-version/Test/.daml/dist/cap-final-test-0.1.0.dar

# the original oracle, same shape
dpm script --all --ledger-host localhost --ledger-port 6865 \
  --static-time --upload-dar true \
  --dar cap-governance/examples/BabyDso/original/Test/.daml/dist/baby-dso-test-0.1.0.dar
```

Expected: every demo reports `SUCCESS` (per script on the sandbox; `ok` under
`dpm test`). Restart the sandbox between runs — party allocations persist, so a
second run on the same instance fails with "Party already exists".