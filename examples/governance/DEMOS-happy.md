# Sequence diagrams — `examples/governance`

These diagrams show the happy path of the two governance examples: `babydso` and
`private-majority-vote`. Each example has a short version with the main steps
and a long version that follows the setup in `Demo/Fixture.daml` and one
successful demo choice by choice. Failure paths and the checks that reject them
are not shown.

Parties and contracts are both drawn as participants. An arrow from a party is a
command that party submits. An arrow from a contract is a choice exercised or a
contract created inside that command's transaction.


## BabyDso

`babydso` models a small DSO that changes two fees in one execution. The
transfer fee lives on `AmuletConfig`, signed by `amuletAuthority`, and the entry
fee lives on `AnsRules`, signed by `dso`. `SetFeesAction` binds both contracts,
so an execution updates both fees or neither.

The SVs vote on one `SharedBallot` with weights read from `SvWeightSource`
contracts. `DsoGovernor_OpenVote` snapshots the weights and pins the state of
both targets when the vote opens. `DsoGovernor_ResolveVote` requires two thirds
of the snapshot weight and takes the weighted median of each fee field. The
governor then calls `Action_AuthorizeExecution`, which creates a `FeeUpdate`
that any single SV can execute after `executionDelay`.

SVs submit with `readAs dso` because the `DsoGovernor`, `AnsRules` and the
weight sources are signed by `dso`.

In the fixture SV1 holds two weight sources (`sv1-a` = 3.0, `sv1-b` = 2.0), SV2
holds `sv2-a` = 3.0 and SV3 holds `sv3-a` = 1.0. The total weight is 9.0, so the
quorum is 6.0. The opening fees are a transfer fee of 0.03 and an entry fee of
2.0.

### Short version

The short version covers the four steps of the vote: open, cast, resolve and execute.

```mermaid
sequenceDiagram
    participant SV1
    participant SV2
    participant SV3
    participant R as DsoGovernor
    participant B as SharedBallot
    participant X as FeeUpdate
    participant T as AmuletConfig + AnsRules

    SV1->>R: DsoGovernor_OpenVote — (0.05, 2.0)
    R->>B: create — weight snapshot, pinned target state
    SV2->>B: SharedBallot_Cast — (0.06, 2.5)
    SV3->>B: SharedBallot_Cast — (0.09, 4.0)
    SV1->>R: DsoGovernor_ResolveVote
    Note over R: two thirds reached, weighted median (0.05, 2.0)
    R->>X: Action_AuthorizeExecution creates FeeUpdate
    SV2->>X: Executable_Execute, after executionDelay
    X->>T: check drift, set transferFee 0.05 and entryFee 2.0
```

### Long version

The long version adds the setup, the weight snapshot and target pinning, the quorum and median rule, and the drift checks at execution.

```mermaid
sequenceDiagram
    box Parties
        participant AA as amuletAuthority
        participant DSO as dso
        participant SV1
        participant SV2
        participant SV3
    end
    box Contracts
        participant R as DsoGovernor
        participant B as SharedBallot
        participant ACT as SetFeesAction
        participant X as FeeUpdate
        participant CFG as AmuletConfig
        participant ANS as AnsRules
    end

    Note over AA,ANS: Setup
    AA->>CFG: create AmuletConfig — transferFee 0.03, epoch 3
    AA->>ACT: create SetFeesAction — authorizers [[dso]], binds config and ANS
    DSO->>ANS: create AnsRules — entryFee 2.0
    DSO->>DSO: create SvWeightSource × 4 — sv1-a 3.0, sv1-b 2.0, sv2-a 3.0, sv3-a 1.0
    DSO->>R: create DsoGovernor — weightKeys, executionDelay 2h, executionWindow 1d

    Note over AA,ANS: Open the vote
    SV1->>R: DsoGovernor_OpenVote — vote (0.05, 2.0), weightSources, targets
    R->>R: requireMechanism — procedure "weighted-vote"
    R->>R: readWeights — SV1 5.0, SV2 3.0, SV3 1.0
    R->>ACT: fetch, then pinStatesWith action_readStateImpl
    ACT->>CFG: readState — pins transferFee and epoch
    ACT->>ANS: readIdentity — pins the contract id
    R->>B: create SharedBallot — weights, binds, votes {SV1}

    Note over AA,ANS: Cast, before votingClosesAt
    SV2->>B: SharedBallot_Cast — (0.06, 2.5)
    B->>B: archive and recreate with SV2's vote
    SV3->>B: SharedBallot_Cast — (0.09, 4.0)
    B->>B: archive and recreate with SV3's vote

    Note over AA,ANS: Resolve, after votingClosesAt
    SV1->>R: DsoGovernor_ResolveVote — mechanism, [ballotCid]
    R->>B: fetchGroup, requireClosed, weighBallots
    R->>B: consumeBallots — archive
    R->>R: rule twoThirdsOf weightedMedianByField — 9.0 cast of 6.0 needed
    Note over R: Decided (transferFee 0.05, entryFee 2.0)
    R->>ACT: Action_AuthorizeExecution — actors [dso], outcome, binds from the ballot
    ACT->>X: create FeeUpdate — opensAt now + 2h, executors = each SV alone
    R-->>SV1: ResolveResult — verdict, [FeeUpdate]

    Note over AA,ANS: Execute, after opensAt
    SV2->>X: Executable_Execute — actors [SV2], targets
    X->>X: requireOpen, entitled EA_Execute, requireSameKeys
    X->>CFG: checkTarget sameTransferFee — the transferFee field is unchanged
    X->>ANS: checkIdentity — the contract id is unchanged
    X->>CFG: AmuletConfig_SetFee 0.05 — epoch 4
    X->>ANS: AnsRules_SetFee 2.0
    Note over CFG,ANS: transferFee 0.05, entryFee 2.0
```


## Private majority vote

`private-majority-vote` changes one `Config` setting from `"sync-a"` to
`"sync-b"` by a simple majority of a fixed electorate: Alice, Bob and Carol. It
keeps each vote private from `dso` and from the other voters. The `operator`
runs the round and is trusted to see every vote.

A member of the electorate opens a `Proposal` through `Governor_Propose`.
The operator invites each voter, and each voter accepts the invitation, which
adds the voter to the proposal's `roll` and creates a `PrivateBallot` signed by
the operator and the voter. No other party observes a `PrivateBallot`.

After the voting window closes, the operator presents every ballot on the roll
to `Governor_ResolveMajority`. A `yay` wins when its weight is more than half
of the electorate. On a `yay` the governor calls `Action_AuthorizeExecution` on
`SetConfigAction`, which creates a `ConfigUpdate` that any single voter can
execute. The execution requires the setting to still be `"sync-a"`.

### Short version

The short version covers the steps of the round: propose, invite and join, cast, resolve and execute.

```mermaid
sequenceDiagram
    participant OP as operator
    participant V as Voters
    participant R as Governor
    participant BL as PrivateBallot
    participant X as ConfigUpdate
    participant CFG as Config

    V->>R: Governor_Propose (Alice) — creates Proposal
    OP->>V: Proposal_Invite — one BallotInvitation each
    V->>BL: BallotInvitation_Accept — join the roll, create PrivateBallot
    V->>BL: PrivateBallot_Cast — yay, yay, nay
    Note over OP,BL: votes are visible to the operator, not to dso or other voters
    OP->>R: Governor_ResolveMajority
    Note over R: ballots cover the roll, 2 of 3 yay
    R->>X: Action_AuthorizeExecution creates ConfigUpdate
    V->>X: Executable_Execute (Alice)
    X->>CFG: setting is still "sync-a", set "sync-b"
```

### Long version

The long version adds the setup, the per-voter invitation and roll entry, the ballot checks at resolution, and the state check at execution.

```mermaid
sequenceDiagram
    box Parties
        participant DSO as dso
        participant OP as operator
        participant A as Alice
        participant Bo as Bob
        participant C as Carol
    end
    box Contracts
        participant R as Governor
        participant P as Proposal
        participant I as BallotInvitation
        participant BL as PrivateBallot
        participant ACT as SetConfigAction
        participant X as ConfigUpdate
        participant CFG as Config
    end

    Note over DSO,CFG: Setup
    DSO->>CFG: create Config — setting "sync-a"
    DSO->>ACT: create SetConfigAction — authorizers [[dso]], baseSetting "sync-a", newSetting "sync-b"
    DSO->>R: create Governor — electorate {Alice, Bob, Carol}

    Note over DSO,CFG: Propose and invite, before entryClosesAt
    A->>R: Governor_Propose — proposalId, terms
    R->>P: create Proposal — procedure "majority", roll []
    OP->>P: Proposal_Invite
    P->>I: create BallotInvitation × 3 — signed by dso and operator

    Note over DSO,CFG: Join the roll, before entryClosesAt
    A->>I: BallotInvitation_Accept — proposalCid
    I->>P: Proposal_Join — controller Alice and dso
    P->>P: archive and recreate with Alice on the roll
    I->>BL: create PrivateBallot — signed by operator and Alice, vote None
    Note over Bo,C: Bob and Carol accept the same way

    Note over DSO,CFG: Cast, between entryClosesAt and votingClosesAt
    A->>BL: PrivateBallot_Cast — yay
    Bo->>BL: PrivateBallot_Cast — yay
    C->>BL: PrivateBallot_Cast — nay
    Note over OP,BL: each ballot is visible to its voter and the operator only

    Note over DSO,CFG: Resolve, after votingClosesAt
    OP->>R: Governor_ResolveMajority — proposalCid, three ballots
    R->>P: fetch, requireMechanism
    R->>BL: fetchGroup, requireClosed, check the ballot windows and actionCid
    R->>R: weighBallotsEqually — the ballots cover the roll
    R->>BL: consumeBallots — PrivateBallot_Consume
    R->>R: rule majorityOf — 2 yay of 3
    Note over R: Decided yay
    R->>ACT: Action_AuthorizeExecution — actors [dso], outcome yay
    ACT->>X: create ConfigUpdate — binds Config at "sync-a", executors = each voter alone
    R->>P: archive
    R-->>OP: ResolveResult — verdict, [ConfigUpdate]

    Note over DSO,CFG: Execute, after votingClosesAt
    A->>X: Executable_Execute — actors [Alice], targets {config}
    X->>X: requireOpen, entitled EA_Execute, requireSameKeys
    X->>CFG: checkTarget (==) — setting is still "sync-a"
    X->>CFG: Config_Set "sync-b"
    Note over CFG: setting "sync-b"
```
