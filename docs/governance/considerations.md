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
no ballot is consumed, but the vote cannot authorize that action.

The documentation must cover:

- that each mechanism documents the outcome it produces, and actions are
  written against it;
- an example of a matching pair, and of a pair that does not match;
- a possible addition in a later interface version: a label in `ActionView`
  naming the outcome an action accepts, so that a resolver can refuse a
  mismatched action when a vote opens rather than at resolution.

## Vote encoding

Votes counted through the `Ballot` interface are `AnyValue`, so a ballot and a
resolver written apart must agree on how each vote kind is encoded.
`Cap.Governance.Utils.Votes` defines the encoder/decoder pairs for `Text` and
`Decimal` votes. The documentation must cover that pairing, the mechanism name
as the key to the format, and the structured vote kinds not yet covered.
