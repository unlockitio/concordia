<!-- SPDX-License-Identifier: Apache-2.0 -->

# Glossary

Each entry points at where the term is defined — a type or a doc section — and
does not define it again. When the code and an entry disagree, the code is
right and the entry is a bug.

## cap-core

**Authentic key** — names a contract by the set of parties that sign it and an
id. A checked fetch compares the key a contract computes from its own
signatories with the key the caller expects. `AuthenticKey` in `Cap.Core.Types`;
`signedKey` and `fetchCheckedAny` in `Cap.Core.Utils`.

**Mechanism** — names the resolver, the procedure and the proposal a ballot or
bid belongs to. The resolver is the contract that resolves the proposal: a
governor in cap-governance, an auction in cap-auctions. `resolver` is its key
and `resolverCid` optionally pins its contract id. A procedure fetches its
ballots by the mechanism.
`Mechanism` in `Cap.Core.Types`; `fetchGroup` and `requireMechanism` in
`Cap.Core.Utils`.

**Execution core** — who may execute or cancel an authorized execution or a
settlement, and when. `ExecutionCore` in `Cap.Core.Types`.

**Authority, admission** — `Set Party` is authority, every member signs;
`[[Party]]` is admission, the caller must cover one listed group entirely.
`admitActors` in `Cap.Core.Utils`.

## cap-governance

**Decentralized party** — the parties that sign a governor and govern through
it. `Mechanism.resolver.authorities`.

**Governor** — the resolver of a governance app: a template the app writes,
signed by the decentralized party. Each procedure is a choice on it, run under
the decentralized party's authority ·
[Governor](cap-governance-rationale.md#governor).

**Target** — a contract a decision acts on, named by an `AuthenticKey`. Targets
are presented as a `Map AuthenticKey AnyContractId`. `Cap.Governance.Utils.Targets`.

**Bind** — what must still be true of a target when the execution acts on it: a
pinned state, a pinned contract id, or neither. `Bind` in `Cap.Governance.Types`.

**Pin** — filling an open part of a bind with the target's current state or
contract id. `pinStatesWith` and `pinCidsWith` pin; `mergeBinds` merges the pins
into the action's binds. `Cap.Governance.Utils.Targets`.

**Drift** — the change a target is allowed between being pinned and being acted
on, judged by a policy the action supplies. `DriftPolicy` and `checkTarget` in
`Cap.Governance.Utils.Targets`.

**Action** — implemented by the owner of a target to let a decentralized party authorize
changes to it. It declares its binds and authorizers and reads its targets'
state for pinning. `Action` in `Cap.Governance.ActionV1` ·
[Action and Executable](cap-governance-rationale.md#action-and-executable).

**Executable** — an authorized execution, carrying the binds it was authorized
under and the outcome. `Executable` in `Cap.Governance.ExecutableV1`.

**Ballot** — a template the app writes that carries votes for one proposal. The
library reads it through `IsBallot`. `IsBallot` in
`Cap.Governance.Utils.Ballots` · [Ballots](cap-governance-rationale.md#ballots).

**Weight** — how much a voter's vote counts, read from contracts the app
registers. `HasWeight` and `readWeights` in `Cap.Governance.Utils.Weights`.

## Counting

Exported from `Cap.Governance.Utils.Count`.

**Cast** — one voter's weight and vote. A vote of `None` is an abstention.
`Casts` maps each voter to their cast.

**Quorum** — whether the turnout is enough for the tally to bind. Decides
nothing about which outcome won.

**Tally** — what the votes concluded, or nothing.

**Verdict** — what a count concluded: `Decided`, `Undecided` when the tally
reached nothing, or `Lapsed` when the quorum failed. `rule` combines a quorum
and a tally into one. `Verdict` in `Cap.Governance.Types`.
