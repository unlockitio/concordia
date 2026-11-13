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

A ballot counted through the `Ballot` interface (`Cap.Governance.BallotV1`)
carries its votes as `AnyValue`. A resolver counting it, possibly in another
package than the ballot's template, decodes them as the kind its mechanism
counts. This document fixes how votes are encoded so that ballots and
resolvers written apart agree.

It covers the two simple kinds: an option label (`Text`) and a number
(`Decimal`). Structured votes, such as a map of parameters, are not covered
yet.

### The mechanism owns the format

The mechanism defines the vote format; the ballot only carries it. Each
mechanism declares, in one place:

- its name, the value of `ResolutionInfo.mechanism`;
- the kind of vote it counts, `Text` or `Decimal`;
- the options it accepts (for `Text`) or its bounds (for `Decimal`), if any.

Because a ballot belongs to a resolution and the resolution names its
mechanism, the name is all a ballot or a client needs to know which format to
use.

#### Mechanism names

A mechanism name is `<namespace>.<mechanism>.v<N>`, for example
`cap.majority.v1`. Mechanisms defined in this repository use the namespace
`cap`.

The format of a mechanism never changes in place. A change to its kind, its
options or its bounds is a new version, `cap.majority.v2`. Ballots already cast
keep the name their resolution recorded and keep decoding as before.

### The rules

|                      | `Text` vote                                         | `Decimal` vote                                              |
|----------------------|-----------------------------------------------------|-------------------------------------------------------------|
| Use                  | a choice among options: plurality, unanimity, yes/no | a number: median, weighted median                          |
| Encoding             | `AV_Text label`                                     | `AV_Decimal value`                                          |
| Decoder              | `textVote`                                          | `decimalVote`                                               |
| Does not decode      | any other constructor                               | any other constructor, including `AV_Int` and `AV_Text`     |
| Comparison           | exact: case-sensitive, untrimmed                    | Daml `Decimal` equality                                     |
| Accepted values      | the options the mechanism lists (`optionAmong`)      | the mechanism's bounds, inclusive (`withinBounds`)          |
| Abstaining           | the `Abstained` constructor, never a label           | the `Abstained` constructor                                 |

Two principles sit behind the table.

**Decoding checks the shape only.** The decoder answers whether a vote is a
`Text` or a `Decimal`, nothing more. Whether its value is accepted is a
separate check with its own message, so that "the vote does not decode" and
"the option is not among the options" stay distinct, and the options or bounds
can change without changing the decoder.

**Decoding is exact.** No value is converted: `AV_Int 3` is not a `Decimal`
vote and `AV_Text "0.5"` is not one either. Exactness matters for clients: an
encoder that sends another constructor produces a ballot that is rejected or
ignored, not one that is read differently by different resolvers.

### In code

The decoders and value checks are in `Cap.Governance.Utils.Votes`; the ballot
checks are in `Cap.Governance.Utils.Ballots`. Both are re-exported by
`Cap.Governance.Utils`.

A mechanism declares itself in one block at the top of its module:

```daml
-- Mechanism: "cap.majority.v1"; vote: Text, options "yes" / "no"
majorityMechanismName : Text
majorityMechanismName = "cap.majority.v1"

majorityOptions : [Text]
majorityOptions = ["yes", "no"]

majorityVoteChecks : [Check Ballot]
majorityVoteChecks =
  [ votesDecodable textVote
  , votesSatisfy textVote (optionAmong majorityOptions)
  ]
```

A resolver counting through the interface fetches the ballots with
`fetchGroup`, applies these checks together with the checks on who stands
behind each ballot, and counts with the same decoder. The checks that are the
voter's error go to `ignoreFailing`; those that are the resolver's own error
would go to `requireChecks`:

```daml
presented <- fetchGroup resolution ballotCids
(kept, ignored) <- ignoreFailing
  (majorityVoteChecks <> [votersSignedBallot, votersEligible voters, consumableBy actors])
  [distinctVotersAmongBallots]
  presented
let cs = casts textVote weights (map snd kept)
```

`votesSatisfy` skips votes that do not decode, so a vote of the wrong kind is
reported once, by `votesDecodable`.

A Decimal mechanism is the same with `decimalVote` and
`withinBounds (Some 0.0) (Some 1.0)`; `None` leaves a side unbounded.

#### Templates counted by their own resolver

When a resolver counts a template it knows, through the template's own
`IsBallot` instance, its votes are typed and need no decoding. If the vote type
is `Text` or `Decimal`, pass `Some` as `decodeVote`; the value checks are the
same:

```daml
[ votesSatisfy Some (optionAmong majorityOptions) ]
```

A template that also implements `Ballot` must expose its votes in the encoding
above, so that both ways of counting it read the same votes.

