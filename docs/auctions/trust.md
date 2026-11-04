<!-- SPDX-License-Identifier: Apache-2.0 -->

# Trust in the executors

In some auctions the principal and the bidders must trust the executors with
their assets. This depends on how their allocations are specified. This
document describes what an allocation lets the executors do, and how that
trust can be reduced. It uses the terms defined in
[intro.md](intro.md#11-terms).

## 1. Background: what an allocation lets the executors do

An account owner creates an allocation for one settlement. The settlement is
identified by its executors and an id chosen by the executors. Only the
executors, together with the registry, can settle or cancel the allocation.

The owner specifies an allocation in one of two ways:

- With fixed transfers. The owner lists the transfers when it creates the
  allocation. The executors can settle exactly these transfers.
- With open transfers. The owner reserves an amount and leaves the transfers
  to the executors, who choose them when they settle (iterated settlement).

Most auction formats need open transfers, because the transfers depend on the outcome.
When a bidder allocates, it does not know whether it will win, how much of the
lot it will receive or at what price.

When the executors settle, the registry checks that:

- every allocation in the batch is for the same settlement;
- every transfer is approved by an allocation of the sender and an allocation
  of the receiver;
- the transfers cover exactly what the allocations approve. With fixed
  transfers this is what the owner listed. With open transfers the executors
  add the approvals themselves when they settle.
- The registry does not move more than what an allocation reserves.

## 2. What the executors can do

In the examples below, Alice allocates 30 P to buy 6 X at a price of 5 P, and
the principal allocates the X.

- With fixed transfers, the executors can:
  - Settle the listed transfers, at any time before the settlement deadline.
  - Settle the transfers at one registry and not at another. Each registry
    settles its own batch, and the Token Standard leaves the atomicity across
    registries to the executors. For example, they can settle Alice's payment
    of 30 P and never deliver her 6 X.
  - Cancel the allocation.
  - Neither settle nor cancel, so the assets stay locked until settlement deadline.

- With open transfers, the executors can settle almost however they want.
  Besides the above, within the amount each allocation reserves, they can
  also:
  - Choose any amount and any price. For example, they can take Alice's 30 P
    and give her 1 X.
  - Choose any counterparty that has an allocation for the same settlement.
    Any party can create an allocation for a settlement, including the
    executors. They can send Alice's 30 P to an account of their own.


The executors cannot move more than an allocation reserves, cannot move funds from
accounts that did not allocate for the settlement, and cannot settle after the
settlement deadline.


## 3. Reducing the trust in the executors

The trust in the executors can be reduced by making them a decentralized
party. Adding the principal and the bidders to the executors is not a very good idea,
as explained in [Section 3.2](#32-the-principal-and-the-bidders-as-executors-not-a-good-idea).

### 3.1. A decentralized party as executor

A decentralized party is one party hosted on n participant
nodes run by different operators, with a confirmation threshold k. A transaction 
of the party needs k of the n nodes to confirm it, so settling outside the 
outcome needs k operators to collude, and the settlement needs k nodes to be available.

The operators agree on what the party does through a governance flow. Each
operator submits its input, for example its approval of the outcome, and the
settlement runs with the party's authority once enough of them agree.
cap-governance provides the parts of such a flow. An auction can use it to
coordinate a decentralized executor. See [cap-governance](../governance/intro.md).

This does not remove the trust. It replaces the trust in one executor with
trust that fewer than k operators collude. Every hosting node sees every
transfer of the settlement.

### 3.2. The principal and the bidders as executors (not a good idea)

Adding the principal and the bidders to the executors stops the other
executors from settling their allocations without them. They can give their
authority in advance, through a contract that only settles the outcome the
resolver computes, so they do not have to sign at settlement.

This does not remove the trust. Each of them is still a stakeholder of the
settlement, and its node must confirm the transaction. A party can withhold
the confirmation and block the settlement for everyone, for example a winner
that no longer wants to pay. Which gives a veto to every winner.
