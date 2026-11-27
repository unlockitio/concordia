<!-- SPDX-License-Identifier: Apache-2.0 -->

# cap-governance considerations

This file lists topics the cap-governance documentation must cover in the
future.

## The outcome shared by a resolver and an action

A resolver passes the outcome of a mechanism to an action as an `AnyValue`
(`outcome` in `Action_AuthorizeExecution`). Neither side knows the other's
template, so the shape of the outcome is an agreement between the mechanism and
the action that no type or interface states.

- An action that defines its own parameters (for example, reduce a fee by 2%)
  needs only to be approved, and works with any mechanism.
- An action that takes its parameters from the outcome (for example, set a fee
  to the median of the votes) works only with mechanisms that produce a type the action can decode.

If the shapes do not match, `Action_AuthorizeExecution` aborts when the
resolution runs, after the vote has taken place. The transaction rolls back, so
no vote record is consumed, but the vote cannot authorize that action.

The documentation must cover:

- that each mechanism documents the outcome it produces, and actions are
  written against it;
- an example of a matching pair, and of a pair that does not match;
- a possible addition in a later interface version: a label in `ActionView`
  naming the outcome an action accepts, so that a resolver can refuse a
  mismatched action when a vote opens rather than at resolution.

## Vote encoding

Votes cast through the `Resolver` interface and counted through the `VoteRecord`
interface are `AnyValue`, so a wallet, a vote record and a resolver written apart
must agree on how each vote is encoded.

The mechanism name (`ResolutionInfo.mechanism`, shown in every `VoteRecordView`)
is the key to the format. A wallet reads it and encodes the vote the way that
mechanism documents. No type or map in code states the format: the resolver
decodes and checks every vote it counts with its own decoder. A resolver that
can check a vote when it is cast should, so that a malformed vote fails at
submission rather than being ignored at the count.

A vote's value is what the mechanism defines it to be: an approval such as
`yay`, one of the record's options, or a value such as a fee that counting
turns into the outcome passed to the action. Each mechanism documents:

- the vote: its type and the encoder in `Cap.Governance.Vote` that
  produces it, or its own encoder for a structured vote;
- whether it accepts `Abstained`;
- the labels or bounds a vote must meet, and the `VoteRecordView.meta` keys
  that carry them. When voters choose among a record's options, the labels are
  its `options`, checked with `validOneOf`.

Mechanism names are namespaced by app, because a wallet looks the format up by
name alone: two apps that both call a mechanism `majority` with different
encodings could not be told apart.

The mechanisms of the examples:

| Mechanism | Vote | Abstained | Options or bounds |
|---|---|---|---|
| `private-majority-vote.majority` | `Text`, `encodeTextVote`: `"yay"` or `"nay"` | no | fixed by the mechanism |
| `babydso.weighted-vote` | named numbers, `AV_Map` of `AV_Decimal`, `encodeParametersVote` | no | none |
| `babydso.confirmation` | the outcome itself, as an `AnyValue` | no | none |

The documentation must still cover the structured vote kinds that more than one
app needs, such as rankings, and give them encoders in
`Cap.Governance.Vote`.

## Casting through the resolver

A vote is cast on the resolver, with `Resolver_CastVote` or with a choice of the
app's own, such as `DecOperatorRules_CastVote`. The vote record has no cast
choice, for two reasons:

- **The resolver checks the voter.** It holds the current voters, weights and
  members, so a cast is checked against them.
- **A private vote creates a record.** With one record per voter, a cast
  creates or changes the voter's record, not the record of the decision.

`Resolver_CastVote` takes the decision's `ResolutionInfo`, which a wallet reads
from the record's view. Its `extraArgs` carry what the implementation needs,
such as the vote record to cast on, the way the Token Standard passes a choice
context.
