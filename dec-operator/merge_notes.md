<!-- SPDX-License-Identifier: Apache-2.0 -->

# dec-operator merge notes

Information for a later merge of `dec-operator` with `cap-governance`.
Each entry states a fact: what exists in both modules, where, and how the two
differ. [cap-governance structure](#cap-governance-structure) describes the
package layout that both modules use.

`dec-operator` depends on `cap-core-identity-v1`, `cap-governance-vote-v1` (for
`Reason`) and `cap-governance-execution-v1` (for `KeyedTarget`).

## Packages

| dec-operator package | Path | Depends on |
| --- | --- | --- |
| `dec-operator-interfaces-v1` | `dec-operator/interfaces` | `cap-core-identity-v1`, `cap-governance-vote-v1`, `cap-governance-execution-v1` |
| `dec-operator-templates` | `dec-operator/templates` | `cap-core-identity-v1`, `dec-operator-interfaces-v1`, `cap-governance-vote-v1`, `cap-governance-execution-v1` |

`dec-operator-interfaces-v1` holds the four interfaces and every
dec-operator data type (`Cap.DecOperator.Types`) in one package. A package
that defines interfaces cannot be upgraded, so these types cannot gain fields;
`DecOperatorConfig` carries `meta : Metadata` for settings added later. The interface views use the same types as the
stored data.

## Shared definitions

| Definition | Defined in | Used by dec-operator in |
| --- | --- | --- |
| `VoteRecord`, `CastVote`, `Vote`, `Reason` | `Cap.Governance.VoteRecordV1` | The app's shared vote record template; `DecOperatorRules_CastVote` and `DecOperatorRules_CloseVote` |
| `ActionId` | `Cap.Governance.ActionV1` | `ActionConfirmationView.action`; the options of a vote record |
| `KeyedTarget` | `Cap.Governance.KeyedTargetV1` | `MemberState` |

dec-operator had its own `Vote`, `Reason`, `CastVote` and `SharedVoteRecord`
interface. All are removed. The app's shared vote record implements
`VoteRecord`, and cap-governance has no `VoteTopic` interface any more.

## Concepts with different names

| dec-operator | cap-governance | Note |
| --- | --- | --- |
| `VoteOutcome` (`VO_Accepted`, `VO_Rejected`, `VO_Expired`) | `ResolutionStatus` (`RS_Decided`, `RS_Rejected`, `RS_Lapsed`, `RS_Undecided`) | dec-operator has no quorum, so it has no case for "too few voters" separate from expiry, and no "undecided" separate from rejected. |
| `DecOperatorRules` interface | `Resolver` interface | Both are the contract on which votes are cast and decided. `Resolver_CastVote` takes `caster`, `identity`, `resolution`, `vote : Vote AnyValue`, `reason : Optional Reason`, `extraArgs`. `DecOperatorRules_CastVote` takes `actor`, `voteRecordCid`, `vote : Vote AnyValue` (`Absent` withdraws), `reason : Reason`; the identity is derived from the actor. `Resolver_Resolve` has `actors : [Party]` and returns `executions`; `DecOperatorRules_CloseVote` has `actor : Party` and executes the action in the same transaction. |
| Shared vote record: one contract holds the votes of every member | `VoteRecord` interface | The same interface covers one record per decision and one record per voter. The design doc calls the shared vote record "vote request". Its `governance` key and mechanism are `resolution.resolver` and `resolution.mechanism`. |
| `Member.payoutWeight` | `HasWeights` / voting weights | `payoutWeight` is not a voting weight; every Active member counts once in a vote. |
| Members keyed by `Identity` with `actingParties : Set Party` | `HasActingParties`, `actingPartiesFor` in `Cap.Core.Identity` | dec-operator stores the mapping in `DecOperatorRulesView.members`. |
| `ActionConfirmation`, `ActionConfirmationRules` | none | No cap-governance equivalent. |

## Design references

The design doc (Consortium layer for decentralized parties, version 2) names
`fetchTargetMatchingIdentity`. It is in `Cap.Governance.Targets.Execution`
(`cap-governance-execution-utils`), with `fetchTargetMatchingSnapshot`.

## Overlapping functionality

| dec-operator | cap-governance | Note |
| --- | --- | --- |
| _(filled in as utils are written)_ | | |

## cap-governance structure

`dec-operator` keeps its own interfaces. Only `KeyedTarget` moved to
`cap-governance`. `cap-governance` is grouped by goal: each goal has a
directory with an `interfaces` package (interfaces and the types they use) and a
`utils` package (helpers).

### Problems in the previous layout

The previous layout had a types package (`cap-governance/types`), one package
per interface (`cap-governance/interfaces/<name>`) and one `utils` package.

- **The types package does not make its types upgradable.** A package that
  defines interfaces cannot be upgraded
  ([smart-contract-upgrade](https://docs.canton.network/appdev/deep-dives/smart-contract-upgrade.md)).
  `Vote`, `Reason` and `ResolutionStatus` appear in the views and choices of
  `VoteRecord`, `VoteTopic` (since removed) and `Resolver`, so those packages pin
  `cap-governance-types-0.1.0`. The Token Standard defines each type in the
  package of the interface that uses it, and shares only `MetadataV1`.
- **Two interface packages depend on `cap-governance-utils`.**
  `cap-governance-vote-record-v1` defines the `IsVoteRecord VoteRecord`
  instance, and `cap-governance-voting-rights-v1` defines the
  `HasWeights VotingRights` instance. Both pin `cap-governance-utils-0.1.0`.
  With `cap-governance-utils-0.2.0`, these instances belong to the classes of
  version 0.1.0, not to those of the version the app uses.
- **Every interface package depends on `cap-core-utils`.** Interface choices
  call `admitActors`, `entitled`, `requireOpen` and `requireExpired`, so every
  `-v1` package pins `cap-core-utils-0.1.0`.

The layout follows two rules:

1. A `-v1` package depends only on packages that are frozen with it:
   `cap-core-identity-v1`, `cap-core-resolution-v1`,
   `splice-api-token-metadata-v1` and other `-v1` packages.
2. The type class instances for an interface are defined in a `utils`
   package, not in the `-v1` package of the interface.
3. An interface choice only calls its implementation method. The checks a
   choice needs are `MUST` rules in its doc comment, and the implementation
   runs them with helpers from a `utils` package, as in the Token Standard
   (`TransferFactory_Transfer`).

### Layout

| Goal | Package | Modules | Contents |
| --- | --- | --- | --- |
| **vote**: put a decision to voters and record their votes | `cap-governance-vote-v1` | `Cap.Governance.VoteRecordV1` | `VoteRecord`, `CastVote`, `Reason`, `Vote` and its `Functor`, `Foldable` and `Traversable` instances |
| | `cap-governance-vote-utils` | `Cap.Governance.Vote`, `Cap.Governance.Resolution`, `Cap.Governance.Count` | `Vote`: `IsVoteRecord` and the `VoteRecord` instances, vote encoding and decoding, vote checks (`validOneOf`, `validWithinBounds`, `validVotes`) and record checks (`validDistinctOptions`, `validVoteRecordAction`, `validVoteRecordSnapshots`). `Resolution`: `fetchVoteRecords`, `fetchSharedVoteRecord`, `expectSame*`, `consumeVoteRecords`, `casts`. `Count`: quorums, tallies and `decide` |
| **rights**: state who votes and with which weight | `cap-governance-voting-rights-v1` | `Cap.Governance.VotingRightsV1` | `VotingRights` |
| | `cap-governance-rights-utils` | `Cap.Governance.Rights` | `HasWeights` and its `VotingRights` instance, `validWeights`, the weight fetches |
| **resolution**: cast votes on and resolve decisions without knowing the resolver's template | `cap-governance-resolver-v1` | `Cap.Governance.ResolverV1` | `Resolver`, `ResolutionStatus` |
| **execution**: execute the outcome on the targets it names | `cap-governance-execution-v1` | `Cap.Governance.ActionV1`, `Cap.Governance.ExecutableV1`, `Cap.Governance.KeyedTargetV1` | `ActionId`, `Action`, `Executable`, `KeyedTarget` |
| | `cap-governance-execution-utils` | `Cap.Governance.Execution`, `Cap.Governance.Targets.Snapshots`, `Cap.Governance.Targets.Execution` | The checks the `Action` and `Executable` implementations run; snapshots, drift policies, target checks |

```
cap-governance/
├── vote/        interfaces/  utils/
├── rights/      interfaces/  utils/
├── resolution/  interfaces/
├── execution/   interfaces/  utils/
└── tests/       unit/ and ledger/, one module per topic
```

The interface modules kept their names, so imports changed only where a type
moved. `cap-governance/types` and the umbrella module `Cap.Governance.Utils`
are removed: import the goal module instead. `Cap.Governance.Count` re-exports
`Vote` and `Identity`.

### Dependencies

Every package also depends on `splice-api-token-metadata-v1` and
`cap-core-identity-v1`. Every `utils` package also depends on `cap-core-base`.

| Package | Depends on |
| --- | --- |
| `cap-governance-execution-v1` | |
| `cap-governance-vote-v1` | `cap-governance-execution-v1` (`ActionId` in `VoteRecordView.options`) |
| `cap-governance-resolver-v1` | `cap-governance-vote-v1`, `cap-governance-execution-v1` |
| `cap-governance-voting-rights-v1` | |
| `cap-governance-execution-utils` | `cap-governance-execution-v1` |
| `cap-governance-vote-utils` | `cap-governance-vote-v1`, `cap-governance-execution-v1` |
| `cap-governance-rights-utils` | `cap-governance-voting-rights-v1` |

No `-v1` package depends on a `utils` package or on `splice-util`. The vote
helpers are in `vote-utils`, not in the resolution goal, because resolution
depends on votes and votes do not depend on resolution: dec-operator counts
votes on `VoteRecord`s without the `Resolver` interface. `vote-utils` depends
on neither `rights-utils` nor `resolver-v1`: `casts` takes the weights as
`Map Identity Decimal`, and `decide` returns a `Verdict`, not a
`ResolutionStatus`.

The `Action` and `Executable` choices run no checks. Each implementation calls
the helper for its choice from `Cap.Governance.Execution`
(`cap-governance-execution-utils`): `validAuthorizers`, `validExecution`,
`validCancellation` or `validExpiry`, and passes the result to `requireMet`. A fix to one of these checks is an
upgrade of `execution-utils`, not a new version of `execution-v1`. An
implementation that skips them is not protected by the interface, which is the
same trust as before: an implementation runs with its signatories' authority,
so a participant vets only packages it trusts.

The `VoteRecord` instances of `HasCheckedFetch`, `HasIdentities` and
`IsVoteRecord` are in `Cap.Governance.Vote`. The `VotingRights` instances of
`HasWeights` and `HasActingParties` are in `Cap.Governance.Rights`.

### dec-operator on this layout

| dec-operator package | Depends on | For |
| --- | --- | --- |
| `dec-operator-interfaces-v1` | `cap-governance-vote-v1`, `cap-governance-execution-v1` | `Reason`, which replaces the dec-operator copy; `KeyedTarget` |
| `dec-operator-templates` | `cap-governance-vote-v1`, `cap-governance-execution-v1` | `KeyedTarget` in `MemberState` |

To count votes and check targets, `dec-operator-templates` adds three
packages: `cap-governance-vote-utils` (`decide`, `atLeastShareOfTotal`),
`cap-governance-execution-utils` (target checks) and `cap-core-base`
(`Checks`, `Admission`). dec-operator does not need the rights goal or
`cap-governance-resolver-v1`. `Fraction` stays in dec-operator:
`atLeastShareOfTotal` takes its numerator and denominator.

### Trade-offs

| Choice | Gain | Cost |
| --- | --- | --- |
| Types in the package of their interface | One package fewer; a type is next to the interface that uses it | A type that two goals use (`Vote`, `Reason`) makes the second goal depend on the first goal's `-v1` package |
| Several interfaces in one `-v1` package per goal | One dependency per goal; interfaces that refer to each other are versioned together | A new version of `Executable` is also a new version of `Action` and `KeyedTarget` |
| One `utils` package per goal | An app depends only on the goals it uses; each one has its own version | Four `daml.yaml` files instead of one |
| Instances in the `utils` packages | No `-v1` package pins a `utils` package | Code that reads `VoteRecord` through `IsVoteRecord` imports `vote-utils` as well as `vote-v1` |

### To revisit

- **execution in cap-core.** If cap-auctions needs `KeyedTarget` or
  `Executable`, the execution goal belongs in cap-core. `SCOPE.md` then needs
  a change, because it states that cap-core defines no interfaces.
- **The `Resolver_Resolve` of private votes.** The private majority vote keeps
  each vote on a template that is not a `VoteRecord`, so its
  `Resolver_Resolve` aborts and points to `Resolver_ResolveMajority`.

## What a voter can trust

A client shows a vote record only if the record is signed by
`resolution.resolver.authorities` and the Ledger API reports an
`InterfaceView.implementation_package_id` that the voter trusts. The voter's
participant computes the view with that package. The view computes each
`ActionId` from the typed action the record stores, the resolver checks that a
vote is one of the options (`validOneOf`), and the resolver requires that the
action it executes has the id of the winning option. See "What a voter can
trust" in `docs/governance/rationale.md`.

## cap-core structure

cap-core holds only what governance and auctions share, in the same layout as
cap-governance. See the cap-core entry in `README.md`.

| Goal | Frozen package | Modules | `utils` package | Modules |
| --- | --- | --- | --- | --- |
| identity | `cap-core-identity-v1` | `Cap.Core.IdentityV1` (`Identity`), `Cap.Core.AuthenticKeyV1` (`AuthenticKey`) | `cap-core-identity-utils` | `Cap.Core.Identity`, `Cap.Core.AuthenticKey` |
| resolution | `cap-core-resolution-v1` | `Cap.Core.ResolutionV1` (`ResolutionInfo`) | `cap-core-resolution-utils` | `Cap.Core.Resolution` |
| base | | | `cap-core-base` | `Cap.Core.Checks`, `Authorization`, `Time`, `Fetch`, `AnyValue`, `Optional` |

What moved out of cap-core:

- **`Snapshot`** is in `Cap.Governance.KeyedTargetV1` and **`TargetReader`**
  in `Cap.Governance.ActionV1`. Only governance and dec-operator use them.
- **`ExecutionCore` and `ExecutionAction`** are removed.
  `Cap.Governance.ExecutableV1` defines `ExecutionTerms`, with `executors` and
  `cancellers` in place of `availableActions`. `SettlementView` holds the same
  fields with `settlers`. `EA_Custom` was unused.

The time and authorization helpers are checks that return `Unmet`
(`validOpen`, `validClosed`, `validBefore`, `validExpired`, `validActors`,
`validActorsForRights`) and take the current time as an argument. The cap-auctions
interfaces only call their implementations, as the governance ones do, and
`Cap.Auctions.Checks` holds the checks their implementations run.
