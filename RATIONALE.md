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

