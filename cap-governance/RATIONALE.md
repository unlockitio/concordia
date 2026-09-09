<!-- SPDX-License-Identifier: Apache-2.0 -->

# Rationale — `cap-governance`

The rational behind each interface.
**Governance does not depend on the things it governs.** A target and the
governance body both depend on `cap-governance`, and neither depends on the
other. 


## `AuthenticTarget` and `Bind`

*For naming the contract a decision acts on, and what must still be true of it*

**State is published by the target, type-erased as `AnyValue`.**
The target publishes what it wants compared, and a `DriftPolicy` decodes
with `typed` at the point of comparison.

**A bind is data; the fixed bodies in the interfaces do not act it.** 
Comparing a pin would make impossible to implement some formats on CAP.

**Trust is scoped to one bind.** Published state is worth exactly the trust
placed in the target's signatories, and the bind is where that trust is granted. 
A target whose signatories are not trusted should be bind by contract id alone.

## `Executable`

*For standing between the resolution and the write, meaning: this was approved, and it
will happen.*

**The effect is a separate step, not part of the resolution.** Executing inside
the resolution fails the cases that need a timelock. Atomicity is not necessarly lost,
as creating the `Executable` and exercising `Executable_Execute` in the same
transaction is still possible.

**It carries pre-committed authority.** 
The write should not need the executer's signature from
the authorities.

**Its fixed body checks the window and the executor set, and nothing else.**
Checking the bindings and how to write the targets differ across use cases. 

## `Action`

*For letting the effect come from a package the governance body never imported.*

**The mandate it carries is standing.** `authorizers` is the same admission field
as `availableActions` and `resolvers`, an action can be used as one contract for many decisions,
where an `Executable` is one contract for one effect,
spent by using it. It can also be used as a way to delegate authority.

**It is optional.** 
A resolver may create an `Executable` itself.


## `Ballot`

*For the input a resolver counts.*

**Enrolment and voting are defined by the implementations.** `BallotView` carries
`opensAt`, `closesAt` and `expiresAt`, and `Ballot_Cast`, `Ballot_Withdraw` and
`Ballot_Expire` enforce that window. Who may enrol, and what a round decides
between, is the implementation's.

**Votes and options are `AnyValue`.** The same erasure as target state, for the
same reason: the core cannot name a format's vote type.

