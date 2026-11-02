<!-- SPDX-License-Identifier: Apache-2.0 -->

# Naming

Naming conventions for the Daml packages in this repository.


### Domain specific naming

- Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for managing
  proposals, requests etc.
- Use only the term `amount`, not `quantity` or `number`, because the three are
  easily confused.
- Use `sender`/`receiver`, not `payer`/`payee`.

### Fields

- Use a plural name for list fields.
- Use the suffix `contract_id` for fields that store contract ids and
  `party_id` for fields that store party ids. In Splice this rule applies to
  Protobuf messages.

### Choice return types

- A choice `X` returns a type named `XResult`: `Foo_Bar` returns
  `Foo_BarResult`. Splice enforces this in CI with
  `scripts/check-daml-return-types.sh`. Tests, `*_Fetch` choices and a fixed
  list of older choices such as `AmuletRules_Transfer` are exempt.

### Config parameters

- Name flags `enableXXX`, not `disableXXX`, to avoid a double negation.


## Interfaces, choices and results

The interface naming follows the Splice token standard so that CAP interfaces
read the same way as the interfaces they compose with.

- Interfaces are PascalCase nouns: `Bid`, `Settlement`, `Executable`.
- The view type is `<Interface>View`: `BidView`, `ExecutableView`.
- Choices are `<Interface>_<Verb>`: `Bid_Withdraw`, `SaleProposal_Accept`.
- A choice that returns a record returns `<Interface>_<Verb>Result`:
  `Bid_WithdrawResult`. Every result record has a `meta : Metadata` field.
- The implementation method behind a choice is `<interface>_<verb>Impl`, with
  the interface name in camelCase: `bid_withdrawImpl`, `saleProposal_acceptImpl`.

## Available actions

- The action type of an interface is `<Interface>Action`: `BidAction`,
  `SaleProposalAction`, `ExecutionAction`.
- Constructors are `<Initials>_<Verb>`, where the initials abbreviate the
  interface name: `BA_Withdraw`, `BPA_Accept`, `SPA_Reject`, `EA_Execute`.
- Every action type ends with `<Initials>_Custom with id : Text`, for
  implementation-specific actions.
- The verb in a constructor matches the verb in the choice it entitles:
  `BA_Withdraw` entitles `Bid_Withdraw`.
- The field holding the entitlements is `availableActions : Map <Interface>Action [[Party]]`.

## Choice arguments

- `actors : [Party]` is the controller of a choice that requires entitlement.
- `actor : Party` is the controller of a choice any single party may exercise,
  such as `Executable_Expire`.
- `extraArgs : ExtraArgs` is the last argument of every choice that takes
  context from the caller.

## Fields

- `authorities : Set Party` is the set of parties that sign a contract or key.
- `meta : Metadata` is the metadata field on views and results.
- A field holding a contract id ends in `Cid` (`resolverCid`, `factoryCid`),
  and a collection of contract ids ends in `Cids` (`targetCids`,
  `executionCids`). A field named `cid` is used only when the record has no
  other contract id to distinguish.
- A field holding a point in time ends in `At`: `opensAt`, `biddingClosesAt`,
  `expiresAt`, `requestedAt`.
- A field holding one end of a window is `<verb>After` or `<verb>Before`:
  `cancelAfter`, `acceptBefore`.
- Collection fields are plural: `quotes`, `fundings`, `snapshots`, `lots`.
- A `Map` keyed by party is named for its values, not its keys: `fundings : Map Party Funding`.

## Functions

- `require<Condition>` checks a condition and aborts with the given message
  when it fails: `requireOpen`, `requireExpired`, `requireResolvedBy`.
- `is<Condition>` returns a `Bool` for the same kind of check: `isOpen`,
  `isPresent`.
- `unmet<Condition>` returns the error message of every part of the condition
  that fails, and an empty list when the condition holds. The matching
  `require<Condition>` aborts with the first message, and the matching
  `ignoreBidsWithout<Condition>` uses the first message as the reason.
- `<field>Of` reads a value out of a view or record: `bidderOf`, `sellerOf`,
  `authoritiesOf`.
- `ignoreBidsWith<Reason>` and `ignoreBidsWithout<Reason>` are bid filters of
  type `BidFilter`. They return the kept bids and the ignored bids with a reason.
- `fetch<Thing>` fetches contracts: `fetchAllocations`, `fetchCheckedAny`.


