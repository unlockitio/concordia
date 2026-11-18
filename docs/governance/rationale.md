<!-- SPDX-License-Identifier: Apache-2.0 -->

# cap-governance rationale

This file explains the design decisions in cap-governance.


## Goal

The goal of cap-governance is to reduce the time it takes to build a governance
app on Canton. It provides the parts every governance app needs. Each part is
described below with the reason it is needed.


### Fetching and grouping vote records for resolution

The resolving party chooses which vote records to present, so the
choice that runs the mechanism must refuse a vote record for another resolution and a
vote record presented twice. `fetchGroup` fetches the vote records, refuses duplicates and
checks that each vote record references the same `ResolutionInfo`. `requireResolvedBy`
checks that the `ResolutionInfo` names the resolver where the outcome is decided
and the mechanism used.

**Example:** Two different governance flows with the same vote records templates, the group check
fails if contracts from one governance flow are used by a resolver on another governance flow.

### Checking the identity of the contracts a decision acts on

A target is a contract the execution needs to read or act on. An action names
each target by an `AuthenticKey`, which is the set of signatories plus an id. A
contract id by itself cannot prove who signed the contract. Any party can create
a contract of the same template with the same fields. `fetchCheckedAny` fetches
the presented contract and compares the key it computes from its own
signatories with the key the action names. `fetchWeightsPerKey` does the same check
for the contracts that carry voting rights.

**Example:** an action changes a parameter on a contract A signed by the operator.
An attacker creates a contract B of the same template, signed only by
themselves, and presents it as the target. Without the identity check, the
execution acts on the attacker's copy and contract A never changes, even though
the action counts as executed. The same risk applies to voting rights: a
self-signed voting-right contract would give its creator voting power they
don't have.

### Admitting votes

A vote counts only if its voter has the right to vote, has not voted already,
and voted for something the mechanism counts. A resolver passes the vote records
through `validVotes`, `fetchVoteRecords` and `validateDistinctIdentitiesAmong`,
and the voting rights through `validWeights`, before `casts` counts them. They
refuse:

- a voter who appears on two vote records;
- a voter with no weight;
- a negative weight;
- a vote that the mechanism's decoder does not accept.

**Example:** a vote record template carries votes of type `Approve | Reject | SetFee
Decimal`, and a yes/no mechanism counts only `Approve` and `Reject`. 

### Quorums and tallies

Every governance app decides two things: whether enough voters took part
(`Quorum`), and what the votes concluded (`Tally`). `decide` combines them into a
`Verdict`: `Lapsed` when the quorum fails, `Undecided` when the tally reaches
nothing, and `Decided` otherwise. They are kept separate because low turnout
and a split vote are different outcomes, and an app may handle them
differently. `Cap.Governance.Utils.Count` provides common quorums
(`atLeastPresent`, `atLeastVotes`, `atLeastVotedWeight`, `atLeastShareOfTotal`)
and tallies (`unanimous`, `plurality`, `shareOfVotes`, `median`,
`weightedMedian`), so an app builds its rule from these parts.

`Casts` holds one cast per eligible voter. Each cast's `vote` is `Absent`
when no vote was presented for the voter, `Abstained` when the voter took part
without choosing an option, or `Voted` with the option. Quorums can count
abstentions as turnout, and tallies only see `Voted`, because an abstention is
part of the turnout but not of the result.

**Example:** `decide (atLeastShareOfTotal 1 2) (shareOfVotes 2 3)` requires the
voters present, abstentions included, to hold half of the eligible voters' weight, and
one option to get at least two thirds of the weight of the votes. Shares are
given as a numerator and a denominator so that a share like two thirds is exact.

### Taking a snapshot of a target and checking it again at execution

A target can change between the vote and the execution, and voters approved the
change against the state they saw. A `Snapshot` records, per target, the state
or contract id the decision was made against. An action declares its `snapshots`
when it is created and can fix parts of their snapshots then. `snapshotStates`
and `snapshotCids` fill the parts the action left open with the target's current
state or contract id, and `mergeSnapshots` merges those snapshots into the
action's targets. At execution, `fetchTargetMatchingSnapshot` compares the target's live state
with the snapshot state using the action's
`DriftPolicy`. It refuses the execution if the target changed in a way the
policy does not allow.

**Example:** a target's fee is 1%, and voters approve action A to raise it to 2%.
Before A executes, action B raises the fee to 3%. If A then executes, it
lowers the fee from 3% to 2%, which nobody voted for. With a snapshot of the fee at 1%
and the `unchanged` policy, `fetchTargetMatchingSnapshot` sees that the fee is now 3% and
refuses A.

Other policies allow some changes. Take the same target with a fee of 1% and a
withdrawal limit of 10,000, and action A that raises the fee to 2%. The drift
policy `sameOn (.fee)` requires only the fee to be unchanged. If action B
lowers the withdrawal limit to 5,000, A still executes, because the fee is
still 1%. A drift policy only decides whether the execution goes ahead. To keep
B's change, A's execution must write only the fields A changes, for example
with Splice's `patch`, which applies the fields that differ between the new and
the snapshot state to the live state.


## When CAP uses an interface

CAP defines an interface only where we expect code to work with a contract
whose template it cannot know when it is compiled. Elsewhere it uses templates,
type classes and helper functions.

### Action and Executable

`Action` and `Executable` separate a governance app from the contracts it
changes. Through them, a decentralized party can govern contracts in other
packages or owned by other parties, without its package depending on theirs,
and one action contract can be used by several governance mechanisms.

`Action` is an interface because a resolver calls it on contracts written by
someone else. The owner of a target signs an action that names the
decentralized party in `authorizers`, and the resolver's choice that runs the
mechanism exercises `Action_AuthorizeExecution` on it. That choice is written without
knowing the action's package. The action's signatories don't have to be the
resolver's signatories, so a target owner can delegate authority over it to a
decentralized party.

`Executable` is an interface because it is what that call returns, and an
executor app can execute it without knowing the action's package.

The diagram shows how a governance app uses an action and its targets. The
governance app never reads or changes a target directly. It reads targets
through the action, and it holds their state only as `Snapshot`s.

```mermaid
flowchart LR
  subgraph GOV["Governance "]
    VoteRecord["VoteRecord<br/>(vote + target snapshots)"]
    Resolver["Resolver<br/>(counts, decides outcome)"]
  end

  subgraph ACT["Action package"]
    Action["Action<br/>view: authorizers, snapshots"]
    Executable["Executable<br/>(execution window, entitled actors)"]
  end

  Targets["Targets<br/>any contract the action names"]

  VoteRecord -- "① action_readStateImpl<br/>snapshotStates / snapshotCids" --> Action
  Action -. "reads state" .-> Targets
  Resolver -- "② Action_AuthorizeExecution<br/>outcome + snapshots" --> Action
  Action -- "mergeSnapshots, creates" --> Executable
  Executable -- "③ Executable_Execute<br/>fetchTargetMatchingSnapshot + DriftPolicy" --> Targets
```

1. When a vote record is cast or at resolution, the governance app reads the
   targets through `action_readStateImpl`, using `snapshotStates` or
   `snapshotCids`. The result is a `Snapshot` per target.
2. The resolver's choice that runs the mechanism exercises `Action_AuthorizeExecution` with the
   `outcome` (vote aggregation) and the snapshots. The action merges them into its own targets
   using `mergeSnapshots` and creates an `Executable`.
3. An entitled actor exercises `Executable_Execute` and presents the target
   contracts (`targetCids`). `fetchTargetMatchingSnapshot` compares each target's live state with its snapshot using
   the action's `DriftPolicy`, and the action then changes the targets.


### Vote records

`IsVoteRecord` in `Cap.Governance.Utils.VoteRecords` lets the library work with any
vote record template. It gives the library a vote record's casts,
its snapshots, the action it asks to authorize, and how to consume it. An app writes
one instance per vote record template.



### Vote topic

A `VoteTopic` interface lets software that does not know an app's templates find
an open decision, read what it decides, and cast a vote on it. Its callers are
wallets and dApps (an SV voting from a CIP-103 wallet, as in the SV governance
dApp CIP), indexers, and a decentralized party that is itself a voter in
another governance flow, whose resolver casts into the other flow's vote topic.

The two governance flows in Canton both have this shape. The DSO's
`VoteRequest` and the DRO's `GovernanceProposal` (the `.canton` naming CIP)
each have a proposer, what is decided, a voting window, an optional effective
time, and votes by identities the governance knows. They differ in where the
vote is cast (on the resolver, `DsoRules_CastVote`, or on the proposal,
`GovVote`), in whether the proposer is a name or a party, in whether a vote can
be changed, and in what each reads live when a vote is cast. `VoteTopicView`
holds what they share, and `VoteTopic_CastVote` leaves the rest to the
implementation:

- It is nonconsuming and returns the contracts it creates, so a vote topic can
  store the vote on itself (and recreate itself) or on a vote record per voter.
- It takes a `caster` and an `identity`, so the implementation checks that the
  caster may cast for that identity.
- Its `extraArgs` carry what the implementation needs, such as the voter's
  vote record or the live voter registry, the way the Token Standard passes a
  choice context.

The view carries no votes. A vote topic that stores its votes can also implement
`VoteRecord`, and one whose votes are private exposes none. It carries no vote
format either: the mechanism name documents it (see "Vote encoding" in
[considerations.md](considerations.md)), and the resolver checks every vote it
counts. It names at most one action: a change to several targets is one action,
and a choice among options is one action that takes the winning option as its
outcome.

The cost is that the view is frozen, so every vote topic has to fit it, and that
a choice taking `ContractId VoteTopic` reaches any template that implements it.
A caller trusts a vote topic only as far as it trusts the resolver its
`resolution` names.
