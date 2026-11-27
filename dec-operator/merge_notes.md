<!-- SPDX-License-Identifier: Apache-2.0 -->

# dec-operator merge notes

Information for a later merge of `dec-operator` with `cap-governance`.
Each entry states a fact: what exists in both modules, where, and how the two
differ. It does not say how to merge.

`dec-operator` depends on `cap-core` only. It does not depend on any
`cap-governance` package.

## Packages

| dec-operator package | Path | Depends on |
| --- | --- | --- |
| `dec-operator-interfaces-v1` | `dec-operator/interfaces` | `cap-core-types` |

`dec-operator-interfaces-v1` holds the five interfaces and every
dec-operator data type (`Cap.DecOperator.Types`) in one package. A package
that defines interfaces cannot be upgraded, so these types cannot gain fields;
`DecOperatorConfig` carries `meta : Metadata` for settings added later. The interface views use the same types as the
stored data.

`cap-governance` has a separate types package (`cap-governance-types`) and one
package per interface, under `cap-governance/interfaces/<name>`.

## Duplicated definitions

| dec-operator | cap-governance | Difference |
| --- | --- | --- |
| `Cap.DecOperator.Types.Vote` | `Cap.Governance.Types.Vote` | Same constructors (`Absent`, `Abstained`, `Voted a`). The governance type has `Functor`, `Foldable` and `Traversable` instances; the DecOperator type has none. The DecOperator type is in the interface package. |
| `Cap.DecOperator.Types.Reason` | `Cap.Governance.Types.Reason` | Same fields (`url`, `body`). Defined in the interface package in dec-operator, in the types package in cap-governance. |

## Concepts with different names

| dec-operator | cap-governance | Note |
| --- | --- | --- |
| `VoteOutcome` (`VO_Accepted`, `VO_Rejected`, `VO_Expired`) | `ResolutionStatus` (`RS_Decided`, `RS_Rejected`, `RS_Lapsed`, `RS_Undecided`) | dec-operator has no quorum, so it has no case for "too few voters" separate from expiry, and no "undecided" separate from rejected. |
| `DecOperatorRules` interface | `Resolver` interface | Both are the contract on which votes are cast and decided. `Resolver_CastVote` takes `caster`, `identity`, `vote : Vote AnyValue`, `action`, `reason : Optional Reason`, `extraArgs`. `DecOperatorRules_CastVote` takes `actor`, `requestCid`, `value : Optional AnyValue` (None = abstain), `reason : Reason`; the identity is derived from the actor. `Resolver_Resolve` has `actors : [Party]` and returns `executions`; `DecOperatorRules_CloseVote` has `actor : Party` and executes the action in the same transaction. |
| `SharedVoteRecord` interface | `VoteTopic` interface | Both describe one decision. `VoteTopic` has a `VoteTopic_CastVote` choice; `SharedVoteRecord` has no choices, and votes are cast through `DecOperatorRules_CastVote`. `VoteTopicView` names the resolution by `ResolutionInfo`; `SharedVoteRecordView` names the decentralized operator by `governance : AuthenticKey`. |
| `SharedVoteRecord` interface: one contract holds the votes of every member (`votes : Map Identity CastVote`) | `VoteRecord` interface: one contract per vote | Similar names, different granularity. The design doc calls `SharedVoteRecord` "vote request". |
| `CastVote.value : Optional AnyValue` | `Vote AnyValue` | `None` corresponds to `Abstained`. `Absent` is not stored in dec-operator; the library adds it for members without an entry. |
| `KeyedTarget` interface (view: `key : AuthenticKey`) | `HasCheckedFetch t AuthenticKey` instances and `Targets` helpers | `KeyedTarget` exists so an off-ledger client can find a target's current contract ID by key. |
| `Member.payoutWeight` | `HasWeights` / voting weights | `payoutWeight` is not a voting weight; every Active member counts once in a vote. |
| Members keyed by `Identity` with `actingParties : Set Party` | `HasActingParties`, `actingPartiesFor` in `Cap.Core.Utils.Admission` | dec-operator stores the mapping in `DecOperatorRulesView.members`. |
| `ActionConfirmation`, `ActionConfirmationRules` | none | No cap-governance equivalent. |

## Design references to code that does not exist

The design doc (Consortium layer for decentralized parties, version 2) names
these Concordia definitions. They are not in the repository on branch
`cap-consortium` at commit 68dc82e.

| Name in the design | What exists instead |
| --- | --- |
| `fetchTargetMatchingIdentity` | `cap-governance/utils/.../Targets/Execution.daml`: `validCidForSnapshot`, `validStateForSnapshot`, `validSnapshotWithoutState`, `validTargetsForSnapshots` |

## Overlapping functionality

| dec-operator | cap-governance | Note |
| --- | --- | --- |
| _(filled in as utils are written)_ | | |
