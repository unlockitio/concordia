<!-- SPDX-License-Identifier: Apache-2.0 -->

# Naming

Naming conventions for the Daml packages in this repository.

## General

- Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for managing
  proposals and requests.
- Use only the term `amount`, never `quantity` or `number`.
- Use `sender`/`receiver`, never `payer`/`payee`.
- Name flags `enableXXX`, never `disableXXX`.

## Interfaces and choices

- Name an interface with a PascalCase noun.
- Name its view type `<Interface>View`.
- Name its choices `<Interface>_<Verb>`.
- Name the record a choice returns `<Interface>_<Verb>Result`. Tests and
  `*_Fetch` choices are exempt.
- Name the implementation method behind a choice `<interface>_<verb>Impl`, with
  the interface name in camelCase.

## Available actions

- Name the action type of an interface `<Interface>Action`.
- Name its constructors `<Initials>_<Verb>`, where the initials abbreviate the
  interface name, and the verb is the verb of the choice the constructor
  entitles.
- Name the constructor for implementation-specific actions
  `<Initials>_Custom with id : Text`.
- Name the field holding the entitlements
  `availableActions : Map <Interface>Action [[Party]]`.

## Choice arguments

- Name the controller of a choice that requires entitlement `actors : [Party]`.
- Name the controller of a choice any single party may exercise
  `actor : Party`.
- Name the argument carrying context from the caller `extraArgs : ExtraArgs`.

## Fields

- Name the set of parties that sign a contract or key `authorities : Set Party`.
- Name the metadata field on views and results `meta : Metadata`.
- End a field holding a contract id in `Cid`, and a collection of contract ids
  in `Cids`. Name a field `cid` only when the record has no other contract id.
- End a field holding a point in time in `At`.
- Name a field holding one end of a window `<verb>After` or `<verb>Before`.
- Use a plural name for collection fields.
- Name a `Map` keyed by party for its values, not its keys.
- In Protobuf messages, end fields that store contract ids in `contract_id`
  and fields that store party ids in `party_id`.

## Checks

- Name a check, a function returning `Unmet`, for the condition that holds
  when it passes.
- Name a type of check `<Thing>Check`, for what it checks.
- Name a function that aborts when a single condition fails
  `require<Condition>`.
- Name a function that returns a `Bool` for a condition `is<Condition>`.
- Name a function that does something and aborts when a condition fails for
  both: `<action><Thing>Matching<Condition>` or
  `<action><Thing>Without<Condition>`. Do not use a `Checked` suffix; 

## Filters and fetches

- Name a contract filter `ignore<What it ignores>`.
- Name a function that fetches contracts `fetch<Thing>`.

## Optional results

- End the name of a pure function that may have no result in `Optional`.
- Do not add the suffix to a lookup, named `lookup<Thing>`, or to a
  conversion, named `from<Representation>` or `decode<Thing>`.
- Name a function that returns the value or fails `expect<Thing>`.

## Conversions

- Name a type-class conversion between any type and a generic representation
  `to<Representation>` / `from<Representation>`, for the representation.
- Name the two sides of an agreed format for one kind of value
  `encode<Thing>` / `decode<Thing>`, for the value.
- Name a function argument that takes a decoder `decode<Thing>`.

## Reading values

- Name a function that reads a value out of a view or record `<field>Of`.
