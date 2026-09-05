# Demos — `examples/governance/private-majority-vote`

Demos showing a majority vote where votes remain private until 
the end of resolution. The action is authorized by a decentrlized party, hosted by all voters.
An operator is trusted with confidentiality of the votes. 


## The format


`VoteResolver` is signed by the `dso`,
observed by the operator and the electorate, publishing one
procedure, `"majority"`, resolvable by `[[operator]]`. Any member raises a proposal
on it with `VoteResolver_Propose`. A`Proposal` is `dso`-signed, observed by the operator and the whole
electorate, it carries the `ProposalTerms` it decides under, the `electorate`, and
the `roll` of voters taking part. 

The `dso` and the operator jointly issue one `BallotInvitation` per entitled voter. 
The voter commits against the live proposal, wiht a
transaction that puts them on the roll, through `Proposal_Join`, and creates
their empty `PrivateBallot`, signed by the operator and themselves. 

Ballots name the body by its `ResolverKey` — `{dso, resolverId}` — not by
contract id. Only the `dso` can create a `VoteResolver`, so the key identifies
the body on its own.
The `dso`is not a signatory of an empty ballot or of a cast one, so it never reads a vote.

The presented ballots must cover the roll, so the
operator has to produce every ballot that exists and cannot mint a replacement
for one becasue it needs the voter's signature binded to this proposal. 
The outcome is issued against the action pinned in the `dso`-signed terms, and every
ballot must carry those same terms.

`Config`, `SetConfigAction`, `ConfigUpdate` are
the governed target and its action. `Config` is signed by the `dso` and
publishes its `setting` as `AuthenticTarget` state, opening at `"sync-a"`.
`SetConfigAction` mints the execution. `ConfigUpdate` re-checks that
bind under the `unchanged` drift policy before exercising `Config_Set`
to `"sync-b"`.

### The flow

1. A member raises a `Proposal` on the Resolver's contract (signed by the dso), fixing the terms and the
   action to be decided.
2. The `dso` and the operator jointly invite each entitled voter.
3. Each voter accepts their invitation, which in one transaction joins them to
   the proposal's **roll** and hands them an empty ballot signed by operator and voter (dso is not an observer). 
4. When voting opens. Each voter casts an option on their own ballot; only the
   operator can read it.
5. After `votingClosesAt` the operator resolves, presenting the proposal and
   every ballot on its roll. The resolve goes through only if every ballot carries that proposal's mechanism and its terms and the voters presented must be exactly the same that joined Proposal.
6. The execution applies the action, moving the config to `sync-b`.


## Security claims

Each demo carries one claim. This table is the dispatcher: what is claimed, and
the script that states it.

| Test | Security claim |
| --- | --- |
| [`whoSeesWhat`](#1-whoseeswhat) | No voter learns another voter's vote before resolution. Asserted as each party's complete visible set after every phase, so an unanticipated leak fails it |
| [`theCountCoversTheElectorate`](#2-thecountcoverstheelectorate) | The resolution cannot omit a voter who took a ballot — not by presenting a subset, and not by fabricating a ballot to stand in for one that was cast |
| [`theOperatorCannotSkipResolve`](#3-theoperatorcannotskipresolve) | The operator cannot create the outcome without resolution |


## Method

The demos are Daml Script tests over five parties: the `dso`, the `operator`,
and an electorate of `alice`, `bob` and `carol`. Each demo allocates its own
party namespace through `setupAs`, so no state crosses between scripts.

Visibility claims take two forms. Single claims are `sees` / `cannotSee`
predicates over a party and one contract, so each note in the diagrams below is
one line of test code. These checks cannot catch is divulgence. 
That can be checked in Daml Studio. The other two claims are proved with assertions.


```bash
dpm test --package-root examples/governance/private-majority-vote/test
```

## Fixture

`setupAs` allocates the five parties and creates three contracts as the `dso`: a
`Config` opening at `"sync-a"`, a `SetConfigAction` proposing `"sync-b"`, and a
`VoteResolver` for body `"body-1"` that expires in ten days. It then builds the
`ProposalTerms` every ballot carries — `{dso}` as authority, accept and reject as
the options, that action, one binding pinning the config's setting as seen at
submission, and a three-day timeline: `entryClosesAt` at day 1,
`votingClosesAt` at day 2, `expiresAt` at day 3. Alice then raises the round on
those terms with `VoteResolver_Propose`, and the `dso` and the operator together
issue one `BallotInvitation` per entitled voter. The `mechanism` names proposal
`"proposal-1"` under the `"majority"` procedure and names the body by key.

No ballot exists yet, only the proposal and the invitations. `seat` is a voter
accepting their invitation, which joins them to the roll and hands them an empty ballot in
one transaction. A demo calls `cast` to record an option and `runResolve` to resolve,
which presents the proposal beside the ballots; `executionOf` takes the single
`Executable` the resolve mints, and `runExecute` applies it to the config.


## 1. `whoSeesWhat`

One proposal carried end to end, three seats, two accepts and one reject, an
execution, and the config moved from `sync-a` to `sync-b`. 

```mermaid
sequenceDiagram
    participant D as DSO
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol

    Note over D,C: Phase 1 — the body, the target, the action, then the seats
    D->>D: Config (sync-a), SetConfigAction (→ sync-b), VoteResolver
    A->>A: VoteResolver_Propose — a Proposal on the body's terms
    D->>A: BallotInvitation — dso and operator together
    A->>A: Accept — joins the roll and takes an empty ballot, signed with the operator
    Note over B,C: the same for Bob and Carol
    Note over D: the invitations are spent — sees the proposal and the public contracts
    Note over A,C: each sees exactly its own seat, the proposal, and the public contracts
    Note over O: sees all three seats, the proposal, and the public contracts

    Note over D,C: Phase 2 — voting, after entryClosesAt
    A->>O: Ballot_Cast accept — Alice already signed the blank, this records the option
    B->>O: Ballot_Cast accept
    C->>O: Ballot_Cast reject
    Note over B,C: neither can see Alice's cast ballot
    Note over D: ✗ nor can the DSO — it is not a stakeholder of it
    Note over O: sees ballots
    Note over D: visible set unchanged by the entire voting phase

    Note over D,C: Phase 3 — resolve, after votingClosesAt
    O->>O: Resolver_Resolve — presents the proposal and all three ballots
    O->>D: Action_IssueExecution → one ConfigUpdate
    O-->>A: every ballot consumed with its vote, and the proposal with them
    Note over D,C: ✓ one execution, visible to all
    Note over D: sees the execution, never the tally that produced it

    Note over D,C: Phase 4 — execute
    O->>D: Executable_Execute → Config_Set sync-b
    Note over D,C: ✓ everyone sees the new Config — nobody ever saw a vote
```



## 2. `theCountCoversTheElectorate`

The demo drops one voter at a time and shows the resolve failing, then presents everyone and
shows it passing.

```mermaid
sequenceDiagram
    participant O as Operator
    participant A as Alice
    participant B as Bob
    participant C as Carol

    A->>O: accept
    B->>O: accept
    C->>O: reject
    Note over A,C: voting is closed — the tally is frozen

    O->>O: present Alice + Carol — drop an accept
    Note over O: ✗ the presented ballots cover the electorate
    O->>O: present Alice + Bob — drop the reject, keep a clean 2–0
    Note over O: ✗ the presented ballots cover the electorate
    O->>O: mint a blank ballot for Alice, to hide her accept
    Note over O: ✗ a ballot carries its voter's signature
    O->>O: the same, with the dso co-signing
    Note over O: ✗ still — the voter signs, and neither of them is Alice
    Note over O,C: and there is no roll to shape — each voter joined it themselves

    O->>O: present all three
    Note over O: ✓ V_Accepted — 2 accepts against an electorate of 3
```


## 3. `theOperatorCannotSkipResolve`

The operator submits every transaction in this format. It seats the voters, it
resolves, and tries to executes. The demo shows the two ways it could reach the outcome
without a vote, and that both are closed by the Daml Ledger Model.

```mermaid
sequenceDiagram
    participant D as DSO
    participant O as Operator
    participant A as Alice

    Note over D,A: the config reads sync-a, and nobody has voted

    O->>O: Action_IssueExecution with a hand-built execution
    Note over O: ✗ controlled by the authorities the execution names — the DSO's

    O->>O: create a VoteResolver of its own, electorate = [Alice]
    Note over O: ✗ a resolver is signed by the DSO

    Note over D,A: the config still reads sync-a
```
