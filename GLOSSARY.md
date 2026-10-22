<!-- SPDX-License-Identifier: Apache-2.0 -->

# Glossary

Each entry points at where the term is defined.

## cap-core

**Authentic key** — names a contract by the set of parties that sign it and an
id. A checked fetch compares the key a contract computes from its own
signatories with the key the caller expects. `AuthenticKey` in `Cap.Core.Types`;
`signedKey` and `fetchCheckedAny` in `Cap.Core.Utils`.

**Mechanism** — the rule a resolver runs to turn ballots or bids into an
outcome: a voting rule in cap-governance, an auction format in cap-auctions.
The resolver implements each mechanism as a choice and names it by a `Text`.
`ResolutionInfo.mechanism`.

**Resolver** — the contract that runs a mechanism over the presented ballots or
bids: a template the app writes in cap-governance, the auction in cap-auctions.
`ResolutionInfo.resolver` is its key and `ResolutionInfo.resolverCid`
optionally pins its contract id.

**Resolution** — the step that runs a mechanism and decides the outcome. In
cap-governance it creates an `Executable` when the mechanism decides. Resolution
means the act of resolving, not the adopted outcome.

**Resolution info** — names the resolver, the mechanism and the resolution a
ballot or bid belongs to. The choice that runs the mechanism fetches its ballots
or bids by the resolution info and checks that it names this resolver and this
mechanism. `ResolutionInfo` in `Cap.Core.Types`; `fetchGroup` and
`requireResolvedBy` in `Cap.Core.Utils`.

**Execution core** — who may execute or cancel an authorized execution or a
settlement, and when. `ExecutionCore` in `Cap.Core.Types`.

**Authority, admission** — `Set Party` is authority, every member signs;
`[[Party]]` is admission, the caller must cover one listed group entirely.
`admitActors` in `Cap.Core.Utils`.

## cap-governance

**Decentralized party** — the parties that sign a resolver and govern through
it. `ResolutionInfo.resolver.authorities`.

**Resolver in a governance flow** — a template the app writes, signed by the
decentralized party. It collects the ballots, and each mechanism is a choice on
it, run under the decentralized party's authority ·
[Resolver](docs/governance/rationale.md#resolver).

**Voter** — a party allowed to submit a ballot to a resolver. Its right to vote
and its weight come from voting rights ·
[Governance flows in Canton](docs/governance/intro.md#2-governance-flows-in-canton).

**Outcome** — what a mechanism decides. The resolver passes it to
`Action_AuthorizeExecution` as `outcome`, and the `Executable` carries it.

**Parameters** — what the action applies to its targets. They come from the
outcome (for example, the median of the proposed fees) or are fixed by the
action (for example, reduce the fee by 2%) ·
[Governance flows in Canton](docs/governance/intro.md#2-governance-flows-in-canton).

**Target** — a contract a decision acts on, named by an `AuthenticKey`. Targets
are presented as a `Map AuthenticKey AnyContractId`. `Cap.Governance.Utils.Targets`.

**Snapshot** — what must still be true of a target when the execution acts on
it: a recorded state, a recorded contract id, or neither. `Snapshot` in
`Cap.Core.Types`.

**Taking a snapshot** — filling an open part of an action's target with the
target's current state or contract id. `snapshotStates` and `snapshotCids` take
snapshots for an action (`snapshotStatesWith` and `snapshotCidsWith` with a
reader you supply); `mergeSnapshots` merges them into the action's targets.
`Cap.Governance.Utils.Targets`.

**Drift** — the change a target is allowed between its snapshot and being acted
on, judged by a policy the action supplies. `DriftPolicy` and `checkTarget` in
`Cap.Governance.Utils.Targets`.

**Action** — implemented by the owner of a target to let a decentralized party authorize
changes to it. It declares its targets and authorizers and reads its targets'
state for snapshots. `Action` in `Cap.Governance.ActionV1` ·
[Action and Executable](docs/governance/rationale.md#action-and-executable).

**Executable** — an authorized execution, carrying the snapshots it was
authorized under and the outcome. `Executable` in `Cap.Governance.ExecutableV1`.

**Ballot** — a template the app writes that a voter submits to a resolver. It
carries the votes of one or more voters for one resolution, the snapshots of the
targets, and the action it asks the resolver to authorize. The library reads it
through `IsBallot`. `IsBallot` in `Cap.Governance.Utils.Ballots` ·
[Ballots](docs/governance/rationale.md#ballots).

**Voting right** — a contract the app registers that gives one or more holders
the right to vote, each with a weight. A contract can carry one right or
several. `HasVotingRights` and `fetchVotingRights` in
`Cap.Governance.Utils.VotingRights`.

**Weight** — how much a voter's vote counts: the sum of the weights of the
voting rights the voter holds. `fetchWeightsPerContract` reads them from one
contract per key, and `fetchWeightsFromOneContract` from a single contract.
`fetchVotersPerContract` and `fetchVotersFromOneContract` return the holders
with a positive weight, for one vote per voter. In
`Cap.Governance.Utils.VotingRights`.

## Counting

Exported from `Cap.Governance.Utils.Count`.

**Vote** — what a cast records for a voter: `Absent` when no vote was
presented, `Abstained` when the voter took part without choosing an option, or
`Voted` with the option.

**Cast** — one voter's weight and `Vote`. `Casts` maps every eligible
voter to their cast.

**Quorum** — whether the turnout is enough for the tally to bind. Decides
nothing about which outcome won.

**Tally** — what the votes concluded, or nothing.

**Verdict** — what a count concluded: `Decided`, `Undecided` when the tally
reached nothing, or `Lapsed` when the quorum failed. `decide` combines a quorum
and a tally into one. `Verdict` in `Cap.Governance.Utils.Count`.
