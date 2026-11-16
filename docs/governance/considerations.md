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

Votes cast through the `VoteTopic` interface and counted through the `VoteRecord`
interface are `AnyValue`, so a wallet, a vote record and a resolver written apart
must agree on how each vote is encoded.

The mechanism name (`ResolutionInfo.mechanism`, shown in every `VoteTopicView`)
is the key to the format. A wallet reads it and encodes the vote the way that
mechanism documents. No type or map in code states the format: the resolver
decodes and checks every vote it counts with its own decoder, and a vote topic
that can check a vote when it is cast should, so that a malformed vote fails
at submission rather than being ignored at the count.

Each mechanism documents:

- the vote: its type and the encoder in `Cap.Governance.Utils.Votes` that
  produces it, or its own encoder for a structured vote;
- whether it accepts `Abstained`;
- any per-topic options or bounds, and the `VoteTopicView.meta` keys that
  carry them.

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
`Cap.Governance.Utils.Votes`.

## Casting through a vote topic or through the resolver

A vote can be cast in two ways: on a `VoteTopic`, with `VoteTopic_CastVote`, or
on the resolver, with `Resolver_CastVote`. Each implementation chooses which it
offers. A topic pins one decision and its action, which suits a vote on one
proposal. Casting on the resolver needs no topic, which suits votes that name
their own action, such as confirmations, and standing votes with no action,
such as a price vote.
