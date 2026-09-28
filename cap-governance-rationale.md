<!-- SPDX-License-Identifier: Apache-2.0 -->

# cap-governance rationale

This file explains the design decisions in cap-governance.
Each section states a decision, the reason for it, and what it
does not cover.

## Goal

The goal of cap-governance is to reduce the time it takes to build a governance
app on Canton. It does this in three ways.

- It provides the parts every governance app needs and that are easy to get
  wrong: fetching and grouping ballots by proposal, checking the identity of the
  contracts a decision acts on, weighing and admitting votes, quorums and
  tallies, pinning the state of a target and checking it again at execution, and
  authorizing an execution through an action.
- It leaves the parts that differ between apps to the app, as plain templates:
  the rules contract, the ballot templates, the procedures and the vote kinds. 
- It separates a governance app from the contracts it changes. Through `Action`
  and `Executable`, a body can govern contracts in other packages or owned by
  other parties, without its package depending on theirs, and one action can be
  used by several bodies and procedures.

The libraries are a set of helpers. An app uses the helpers it
needs and writes its own code where a helper does not fit, for example a
counting rule the library does not provide.

## What cap-governance is

cap-governance lets a governing body authorize changes to contracts, including
contracts the body does not own. It consists of:

- two interfaces: `Action` (`cap-governance-action-v1`) and `Executable`
  (`cap-governance-executable-v1`);
- stored types: `Bind` (`cap-governance-types`), and `AuthenticKey`, `Mechanism`
  and `ExecutionCore` (`cap-core-types`);
- libraries: `cap-governance-utils` (modules `Targets`, `Ballots`, `Weights`,
  `Count`) and `cap-core-utils`.

An app writes its own rules contract, ballot templates and procedures. The
libraries provide the checks those have in common.

## When CAP uses an interface

CAP defines an interface only where code has to work with a contract whose
template it cannot know when it is compiled. Code inside one app knows every
template it touches, so it uses templates, type classes and helper functions
instead.

### Action and Executable

`Action` is an interface because a body calls it on contracts written by someone
else. The owner of a target signs an action that names the body in
`authorizers`, and the body's procedure exercises `Action_AuthorizeExecution` on
it. The body's code is written without knowing the action's package, and the action's
author may be another organization.

`Executable` is an interface because it is what that call returns, and an
executor app can execute it without knowing the action's package.


### Ballots

Ballots have no interface because the app that defines a ballot template is the
app that counts it. The rules contract fetches its own ballot templates and runs
its own tally, so the caller always knows the concrete type.

The former `Ballot` interface had these costs:

- votes were stored as `AnyValue`, because an interface cannot take a type
  parameter, and every procedure decoded them again;
- its package was frozen, while each app needs a different ballot shape.

`IsBallot` in `Cap.Governance.Utils.Ballots` is the type class that replaces it.
It gives the library a ballot's casts, its binds, the action it asks to
authorize, and how to consume it. An app writes one instance per ballot
template.


### Resolver, Governor and Submittable

These have no interface because a resolution runs on the body's own rules
contract, under the body's own authority. The removed interfaces added:

- a `procedures` map looked up by name, although the template already knows its
  procedures;
- a generic entry point, `Resolver_Resolve`, that a generic client could not use,
  because it cannot build the presented contracts or `extraArgs` for a format it
  does not know;
- `Submittable`, whose only use was carrying a `Mechanism` to that entry point.

Each procedure is now a named choice on the rules contract. 


## Rules contract and procedures

The rules contract is a template the app writes, signed by the body. Each
procedure is a choice on it, for example `DsoResolver_ResolveVote` in the
babydso example.

- Authority: the body signs the rules contract, so its choices run with the
  body's authority. A decentralized party that cannot submit commands itself acts
  this way: a member exercises the choice and the body's hosting nodes confirm
  the transaction.
- Grouping: every ballot carries a `Mechanism`, which names the body's key, the
  proposal id, the procedure and optionally one rules contract. A procedure
  fetches its ballots with `fetchGroup mechanism`, which requires every ballot to
  carry exactly that mechanism, and checks the mechanism with `requireMechanism`.
- `resolverCid` ties a proposal to one rules contract. Leaving it `None` lets a
  recreated rules contract resolve proposals opened under the previous one.

A procedure is part of the rules template, so adding one means upgrading the
app's package.

## Ballots and votes

- A vote type describes how votes are combined, not what the action does with
  the outcome. Examples are choosing an option (`V_Choice`), confirming a value
  (`V_Confirm`) and giving a number per named parameter (`V_Parameters`). The
  procedure passes the combined result to the action as `AnyValue`, and only the
  action decodes it. Governance code never names an action's types, so a new
  action needs no governance change unless it needs a new way of combining votes.
- An app defines one closed sum of the vote kinds its procedures count. A closed
  sum is enough because adding a procedure already means upgrading the app.
  Storing votes as `AnyValue` is only needed if a ballot template must serve
  procedures defined in other packages.
- A ballot template's `ensure` checks that every stored vote is a kind its
  procedure counts, so a vote of the wrong kind fails when it is cast.
- The procedure, not the ballot, states which kind it counts: it passes an unwrap
  function to `weighBallots`. One ballot template can therefore serve several
  procedures.

Each ballot template allows the vote types for what the procedures in which it will be used needs.


