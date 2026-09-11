# Design — `cap-governance`

## What it is

Four interface packages and a utils package, for approving effects on live state.

A proposal is resolved by a `Resolver` from `cap-core`: it collects `Submittable`s and
runs a named `Procedure` over them to reach a `Verdict`. Ballots are those submittables.
An action declares the contracts a decision may act on, each with a `BindSpec` saying when
its state and its contract id are fixed. A proposal may bind several targets across several
packages, and `Executable_Execute` checks and writes them in one transaction. The pins are set out in [State Awareness](#state-awareness) below.

On acceptance an `Executable` is created, carrying pre-committed authority and the
bindings. `Action_AuthorizeExecution` is one way to create it: the action reads the decision
from the resolution's `outcome` and returns the executables, so the effect can live in a
package deployed after the governance body. It is also a way to delegate authority, some examples where this is useful can be found
in [Governance flows examples](#governance-flows-examples). 
The executable is created under the action's signatures, with no
signature from that authority at resolution or at execution. 
A resolver may also create the `Executable` itself. 
The effect happens at `Executable_Execute`, which can also be exercised inside Resolve. 
**Governance does not necessarly depend on the governance targets. Governance and targets both depend on cap-governance and not on each other. Similar to Token standard, Amulet does not depend on every wallet and no
wallet depends on Amulet, both depend on the Token Standard interfaces.**


## State Awareness

The governance packages name no governable type. A decision reaches the contract
it acts on through a bind, so a kind of target that has never been governed
before is a deploy, not a change in the governance logic.

### Where the vocabulary lives

`Cap.Governance.BindingV1` holds `TargetKey`, `Bind`, the `AuthenticTarget`
interface and the fetches over it. `Cap.Governance.ActionV1` holds `Stage`,
`AsOf`, `BindSpec`, `dueAt` and `wellFormedAt`, because an action is the only
thing that declares a bind spec.

### The bind spec

An action declares one `BindSpec` per target it may touch. Each `AsOf` carries
the `Stage` at which that part is fixed, and the value once something has fixed
it. `state = None` says the target publishes no state.

```haskell
data BindSpec = BindSpec with
    state : Optional (AsOf AnyValue)
    cid : AsOf AnyContractId
```

`wellFormedAt stage` holds when, for every spec:

- an opaque target has no state slot, since it publishes nothing to pin;
- a state slot exists only where the contract is fixed strictly later, since
  once the contract is fixed the state follows from it;
- no part carries a value before its stage.


### The bind

Everything downstream carries the settled form, which has the values and no
stages.

```haskell
data Bind = Bind with
    state : Optional AnyValue
    cid : Optional AnyContractId
```

`BindSpec` and `Bind` are both keyed by `TargetKey` from outside, so
`ActionView.bindSpec`, `BallotView.bindings` and `ExecutableView.bindings` are
maps.

### The path a bind takes

- The action declares `bindSpec`.
- A submitter fills the parts due at `Submission`, reading the targets presented
  to it. `pinStateAt` in `cap-governance-utils` does that, called from
  `ballot_castImpl` in both governance examples.
- The resolver compares the submitters' binds against each other with `holds`
  and carries one set forward.
- `Action_AuthorizeExecution` puts the binds on the executable it creates.
- `Executable_Execute` receives the contracts to act on and checks each against
  its bind with `holds`.

The interfaces do not check that a bind carries what it claims. Each
implementation decides what to compare and at which stage.

### Drift policies

A `DriftPolicy` defines `onState : AnyValue -> AnyValue -> Bool` and does not
export it. `holds` is the only way to apply one, and it takes binds:

```haskell
holds : DriftPolicy -> Bind -> Bind -> Bool
```

A pinned contract must be the contract presented. With no contract pinned, the
two states are compared under the policy. A policy never sees a contract id, so
none can waive that rule.

`cap-governance-utils` supplies the common cases: `anyDrift`, `unchanged`,
`unchangedAt`, `typed`, `withinVersions`, `withinRatio`, `notAfter` and
`atSlice`. They compose with `<>`.

### Targets

A target can be: 
- an instance of the `AuthenticTarget` interface, publishing
`{ authorities, id, state }` with its state type-erased as `AnyValue`. That state
is the target's own claim about itself, so it is worth the trust placed in the
target's signatories.
- Only a contract id in that case there is no state to compare, so no drift policy applies, and any change to the
contract breaks the bind.

Every cell below is expressible; these are the combinations worth writing.

| Submission | Resolution | Execution | What the bind says |
|:---:|:---:|:---:|---|
| state | | cid | the voters agreed about a state; the executor names the contract carrying it at execution |
| state | cid | | the voters agreed about a state, and the resolution fixed which contract carries it |
| | state | cid | the resolution pinned the state it saw; the executor names the contract at execution |
| cid | | | the proposal named the exact contract, and nothing about its content |
| | cid | | the resolution fixed the contract, and nothing the voters saw is pinned |
| | | | nothing pinned: the bind names the target key and constrains nothing |
| cid | cid | cid | an opaque target: a contract and nothing else, on nothing but the ledger |

Splice's `AmuletRules` is row one: `AmuletRules_SetConfig` carries a `baseConfig`
pinned when the action was proposed, and `DsoRules_ExecuteConfirmedAction` takes
the `amuletRulesCid` at execution. What differs is the comparison. `patch`
writes the proposal's value where it differs from the base and never refuses,
where a `DriftPolicy` may.

`wellFormedAt` requires a state to be fixed strictly before the contract, and
that gap is what the drift policy judges. The wider it is, the more the target
may legitimately move between resolution and execution.

Reading the rows: a state pin without a cid pin follows the target through
re-creation — the decision acts on whatever contract now carries the key,
provided its state still passes the drift policy. A cid pin without a state pin
is all-or-nothing on identity: that contract or nothing, whatever it now says.

Stages are read by `wellFormedAt` and by each format's own code. No fixed
body reads one.

## Governance flows examples

- **A target under another authority.** A provider holds contracts a decentralized party's
  votes should be able to change. It signs one action naming that party in `authorizers`;
  from then on its resolutions produce executables carrying the provider's signature, and
  the provider signs nothing per decision.
- **Several bodies, each able to act alone.** A caller is admitted if it covers any one
  group, so `authorizers = [[bodyA], [bodyB]]` lets either body authorize an execution by
  itself, from a single action. A flat set would have required both to sign together, and
  two separate actions to avoid it. A group with more than one member is the joint case:
  `[[bodyA], [x, y]]` means bodyA alone, or x and y together.
- **A final approval before execution.** The resolution runs as usual and creates the
  executable, and `core.availableActions` names the approving party alone under
  `EA_Execute`. Giving that party `EA_Cancel` as well lets it end the decision early
  through `Executable_Cancel`; without it, refusing is not executing, and the executable is
  cleared by `Executable_Expire` once `core.expiresAt` has passed.
- **A timelock.** `core.opensAt` is set ahead of the resolution, so no executor can write
  before it. 
- **Casting by a delegate.** `Ballot_Cast` is entitled through `availableActions`, not
  through `voter`, so a ballot mapping `BA_Cast` to `[[voter], [delegate]]`. The map is per action:
  leaving `BA_Withdraw` at `[[voter]]` means the delegate casts and only the principal
  takes it back.
- **An emergency path.** One resolver publishes two procedures over the same action. The
  ordinary one opens execution after `executionDelay`; the emergency one opens it at once
  and asks for example a higher quorum. 


## How it generalizes from Splice

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
│   ├── binding/             Bind, TargetKey, AuthenticTarget
│   ├── executable/          Executable, ExecutableView
│   ├── action/              Action, Action_AuthorizeExecution, BindSpec, AsOf, Stage
│   └── ballot/              Ballot, BallotView
└── cap-governance-utils/    DriftPolicy and its combinators, pinStateAt

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

lib/                         vendored Token Standard DARs the interfaces bind to
```

Rational behind each interface [`RATIONALE.md`](RATIONALE.md).
