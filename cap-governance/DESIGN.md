# Design — `cap-governance`

## What it is

Four interface packages and a utils package, for approving effects on live state.

A proposal is resolved by a `Resolver` from `cap-core`: it collects `Submittable`s and
runs a named `Procedure` over them to reach a `Verdict`. Ballots are those submittables.
`ProposalTerms` carries the options voters choose between and a `[Bind]`: the contracts
the decision will act on with a pin of itd state. A proposal may
bind several targets across several packages, and `Executable_Execute` checks and writes
them in one transaction. A bind pins by
state, by contract id, and it can be pinned at different stages (Submission, Resolution, Execution). 
For example, pin a contract by state at submission and then pin by cid at resolution is possible.
State is what the target says about itself, so it forces
trusting the target's authorities (similar to amulet-rules in splice), and admit detailed drift policies. A
contract id is a ledger fact and needs no such trust, but it is all-or-nothing: any
change to the contract breaks the bind.

On acceptance the resolver exercises `Action_IssueExecution` on the action the proposal
names. The action reads the
decision from the resolution's `outcome` and mints an `Executable`, which carries pre-committed
authority and the bindings. The effect happens at `Executable_Execute`, presenting the bound contracts as
`currentTargets`. Its fixed body checks only the window and the executor set — checking
the bindings and writing the targets are the format's own,
`cap-governance-utils` has helpers for it.

A target participates by implementing `AuthenticTarget`, publishing
`{ authorities, id, state }` with its state type-erased as `AnyValue`. A `DriftPolicy`
decides wheather to continue the process or finish it there based on state differences. A target that implements
nothing can still be governed through an `OpaqueBind`, pinned by contract id alone.

**Governance does not depend on the governance targets. Governance and targets both depend on cap-governance and not on each other. Similar to Token standard, Amulet does not depend on every wallet and no
wallet depends on Amulet, both depend on the Token Standard interfaces.**


## How it differs from Splice

**What can be governed without changing the governance package.** 
- Splice: only the
contract types named in [`ActionRequiringConfirmation`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-dso-governance/daml/Splice/DsoRules.daml#L68), so governing one more
means a new variant and a new release. 
- CAP: any contract, provided some package outside the governance core implements an `Action` that acts on it.

**How a governance target is named.** 
- Splice: A **contract-group id** derived from the contract's
  payload and compared by `fetchChecked` — [`ForDso`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-amulet/daml/Splice/Types.daml#L29) is the DSO party,
  [`ForRound`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-amulet/daml/Splice/Types.daml#L37) adds a round, [`ForOwner`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-amulet/daml/Splice/Types.daml#L45) adds an owner. And a **contract id**, either
  supplied at execution as [`amuletRulesCid : Optional (ContractId AmuletRules)`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-dso-governance/daml/Splice/DsoRules.daml#L730), or pinned in the
  action as [`ansEntryContextCid : ContractId AnsEntryContext`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-dso-governance/daml/Splice/DsoRules.daml#L74).
- CAP: `AuthenticTargetKey` is `{ authorities : Set Party, id : Text }`, so two instances under one authority differ
by `id`, and a target co-signed by several bodies is expressible; the contract id is type-erased as `AnyContractId` and pinned in the `Bind`.

**What is checked at execution.** 
- Splice: group membership, or a contract id pinned in
the action as [`ARC_AnsEntryContext`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-dso-governance/daml/Splice/DsoRules.daml#L73) does.
- CAP: a `DriftPolicy` compares what the ballots/resolve saw against target's current state.

**What happens on state drift.** 
- Splice: [`patch`](https://github.com/canton-network/splice/blob/a4ea43aa55db83a028a30e95ace45e1350edde9a/daml/splice-util/daml/Splice/Util.daml#L177)
writes the proposal's value where it differs from the base and keeps the current one
otherwise, it never refuses. 
- CAP: the check runs first and decides on the change that actually occurred; it can
refuse. What is written afterwards is the implementation's — `patch` is one option, not
a rule.

> Splice links are pinned to commit
> [`a4ea43a`](https://github.com/canton-network/splice/tree/a4ea43aa55db83a028a30e95ace45e1350edde9a) (2026-08-28),
> `splice-dso-governance` 0.1.28. Line numbers move on `main`.

## Layout

```
cap-governance/
├── Interfaces/
│   ├── binding/             Bind, AsOf, Stage, TargetKey, AuthenticTarget
│   ├── executable/          Executable, ExecutableView
│   ├── action/              Action, Action_IssueExecution
│   └── ballot/              Ballot, ProposalTerms
└── cap-governance-utils/    DriftPolicy and its combinators

examples/governance/
├── private-majority-vote/
│   └── {impl,test}          a private-ballot majority vote
└── baby-dso/
    ├── plain/               the reduced DsoRules/AmuletRules — the shape argued against
    └── cap/
        ├── ans/             AnsRules — implements nothing, knows nothing about CAP
        ├── config/          AmuletConfig, the DSO's own AuthenticTarget
        ├── governance/      the DSO body: DsoResolver, SvBallot, SvConfirmation.
        │                    Imports no app package — neither ans/, config/ nor action/.
        ├── action/          a CAP-aware app: Action + Executable over config/,
        │                    plus the bridge that governs ans/
        └── test/            the demos

lib/                         vendored Token Standard and Splice DARs
```
