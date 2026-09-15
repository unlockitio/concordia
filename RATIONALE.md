<!-- SPDX-License-Identifier: Apache-2.0 -->

# Rationale

CAP ships interfaces and opt-in helpers, no template a deployment instantiates. 
The thing being governed or sold lives in a package CAP does not know about.

Per-domain decisions are recorded beside their package:
[`cap-governance/RATIONALE.md`](cap-governance/RATIONALE.md) and
[`cap-auctions/RATIONALE.md`](cap-auctions/RATIONALE.md).


## Authority and admission are separate fields

Two party fields recur across the interfaces, and the type says which rule
applies. `Set Party` is authority: every member signs. `[[Party]]` allows admission:
the caller passes the parties it is acting as, and `admitActors` requires it to
cover one listed group entirely.

Admission is published at every choice, and the fixed body checks it there:

| Choice | Published as |
|---|---|
| Submit, cast, withdraw | `availableActions : Map _ [[Party]]` |
| Resolve | `Procedure.resolvers` |
| Authorize an execution | `ActionView.authorizers` |
| Execute, settle | `ExecutionCore.availableActions` under `EA_Execute` |
| Cancel an execution or settlement | `ExecutionCore.availableActions` under `EA_Cancel` |



## What the fixed bodies check

A fixed body checks only what the view's own fields would otherwise not mean. For example, a
view that publishes `expiresAt` gets an expire choice whose body checks that the
contract has expired.
`Ballot_Cast` checks the submission window it publishes, and every choice in the
table above checks its admission field. Anything past that belongs to the
implementation, since a rule the fixed body imposes is a format CAP cannot
carry.

`Resolver_Resolve` is the only fixed body that fetches. Everything a resolver
acts on reaches it as a submittable, so `fetchSubmittables` is the one read a
body needs, and there is nothing to do before it beyond the two checks already
there: the declared mechanism names this resolver, and its `resolverCid`, where
set, is this contract. Every other body works from its own view and its
arguments.

The reads and rules an implementation does need are helpers in the utils
packages, not bodies: `pinStateAt` for filling a bind at submission, the drift
policies for comparing one, and the quorum and tally kit for counting votes.
They cover the common cases and constrain nothing — a format that needs
something else writes it.
