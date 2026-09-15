<!-- SPDX-License-Identifier: Apache-2.0 -->

# Glossary

Each entry points at where the term is defined — a type or a doc section — and
does not define it again. When the code and an entry disagree, the code is
right and the entry is a bug.

## cap-core

**Mechanism** — names the resolver, procedure and round a submittable belongs
to. `Mechanism` in `Cap.Core.SubmittableV1`.

**Submittable** — a contract presented to a resolver. It publishes a mechanism
and nothing else. `Submittable` in `Cap.Core.SubmittableV1`.

**Resolver** — reaches a verdict over the submittables presented to it, by
running one of its named procedures. `Resolver` in `Cap.Core.ResolverV1`.

**Procedure** — one named way a resolver resolves: who may call it, and the
body that runs. `Procedure` in `Cap.Core.ResolverV1`.

**Verdict** — what a resolution concluded: `V_Decided`, `V_Undecided` or
`V_Lapsed`. `Verdict` in `Cap.Core.ResolverV1`.

**Authority, admission** — `Set Party` is authority, every member signs;
`[[Party]]` is admission, the caller must cover one listed group entirely.
[Authority and admission are separate fields](RATIONALE.md#authority-and-admission-are-separate-fields).

## cap-governance

**Target** — the contract a decision acts on, named by a `TargetKey` that is
either `Authentic`, publishing state, or `Opaque`, a contract id alone.
`TargetKey` and `AuthenticTarget` in `Cap.Governance.BindingV1` ·
[Targets](cap-governance/DESIGN.md#targets).

**Bind** — what must still be true of a target when the decision acts on it.
`Bind` in `Cap.Governance.BindingV1` ·
[The bind](cap-governance/DESIGN.md#the-bind).

**Bind spec** — an action's declaration of which parts of a bind are fixed, and
at which stage. `BindSpec` in `Cap.Governance.ActionV1` ·
[The bind spec](cap-governance/DESIGN.md#the-bind-spec).

**Stage** — when a bind's state or contract id is fixed: `Genesis`,
`Submission`, `Resolution`, `Execution`. Each is fixed once and checked at
every later stage. `Stage` in `Cap.Governance.ActionV1` ·
[Targets](cap-governance/DESIGN.md#targets).

**Drift** — the change a target is allowed between being pinned and being acted
on. `DriftPolicy` in `Cap.Governance.Utils` ·
[Drift policies](cap-governance/DESIGN.md#drift-policies).

**Action** — declares a bind spec and authorizes executions against it.
`Action` in `Cap.Governance.ActionV1`.

**Executable** — an authorized execution, carrying the bind it was authorized
under and the outcome. `Executable` in `Cap.Governance.ExecutableV1`.

**Ballot** — a submittable carrying one voter's vote, with the window in which
it may be cast or withdrawn. `Ballot` in `Cap.Governance.BallotV1`.

## Separable formats

The class of governance formats whose quorum and tally are independent.
Exported from `Cap.Governance.Utils` ·
[Separable formats](cap-governance/DESIGN.md#separable-formats).

**Electorate** — the voters a format counts and the weight each one carries.
Every bar a quorum or tally applies is a function of it.

**Cast** — one voter's ballot as a format reads it. A vote of `None` is an
abstention.

**Quorum** — whether the turnout is enough for the tally to bind. Decides
nothing about which outcome won.

**Tally** — what the votes concluded, or nothing.
