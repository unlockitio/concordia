# BabyCDF gov-interfaces baseline

Live-demo continuation is partial: pinned-CIPS Compose and parameterized host bootstrap with verified dedicated-user rights and atomic readback are implemented/tested; agent services, Reviewer interval mode and steps 2–6 remain. See demo/DEMO.md. No governance Daml or Java behavior was changed for this foundation.

Target branch source: `f516b8dfd2f5bd48662302d6d87c26a9ddc2b739`.
SDK 3.5.11, LF 2.3, existing dpm 1.0.22. BabyCDF stays outside root
multi-package.yaml. No live deployment, payment, beneficiary mint or
Foundation authority is implied; Java CouponAllocation stays BLOCKED.

## Adopted contracts

- VoteRecordV1/IsVoteRecord replace BallotV1/IsBallot. OwnerId keys use
  partyAsOwnerId, and tri-state votes retain exact Yes/No AnyValue encoding.
- VoteTopicV1 exposes proposer OwnerId, pinned subject Reason, action and
  voting close/effective times. The generic cast requires the invited ballot
  CID in ExtraArgs.context `babycdf/invited-ballot`. It checks topic/caster,
  then exercises consuming CastAgendaVote on that contract. Missing invitation,
  replay of the consumed CID and recasting the replacement voted record fail.
  This follows BabyDso's pattern of adapting a topic cast to its existing
  consuming typed cast choice rather than creating an independent record.
- VotingRightsV1 exposes unit VotingRight values with the holder as sole
  caster, without weakening credential issuer/holder/consortium/validity,
  registration or one-presented-right-per-participant checks.
- ResolverV1 adapts generic casts through `babycdf/proposal` and the invited
  ballot context. Generic resolve receives a signed AgendaResolverRequest CID
  under `babycdf/request`, containing typed proposal and credential references.
  It delegates to strict ResolveAgenda and archives the request on success.
  Resolution/proposal/action, actors, full electorate, windows and every
  credential remain checked. A request is input transport, not a rights registry.

Helper mapping: consumeBallots -> consumeVoteRecords; votersEligible ->
validVoteRecordVoters; distinctVotersAmongBallots -> distinctVotersAmongVoteRecords;
ballotSignedBy -> validVoteRecordSignatories; consumableBy -> validConsumers;
votesDecodable -> validVotes; presentedTargetsMatchSnapshots ->
validTargetsForSnapshots; fetchWeightsFromOneContract ->
fetchVotingRightsFromOneContract. Manifests depend on governance-types,
vote-record, vote-topic, voting-rights and resolver DARs, not the removed ballot
DAR. Execution retains conflict-safe snapshot merging, exact targets,
authentic checked fetch, unchanged version/baseVersion and affirmative outcome.

Quorum is unchanged:
`Count.decide (Count.atLeastPresent 3) (Count.shareOfVotes 1 2)`.
Proposal blob remains `f591787a772848e4537aba5d8ba02be1764647da`.
Advisers remain excluded. Duplicate contract issuance is not globally prevented:
the typed ledger choices and presented electorate are checked at resolution;
this is not a global uniqueness or completeness registry.

## Dependencies and commands

From Concordia root, using an approved external temporary directory:

```sh
BABYCDF_BUILD_ROOT=<approved-temp> sh examples/governance/babycdf/prepare-dependencies.sh
```

Both pinned commits must already exist locally; the script never fetches or
installs. Credentials source is CIPS
`8e36c20e5106014efb8348f749d9535afbc48921`, project
cn-credentials-standard/demo/interface. Its exported manifest stays SDK
3.5.11/LF 2.2. Metadata DAR SHA-256 is checked against
`455eb160cb5abd4ae9918a6fbb9dad471f721adda39f0e5c76feef08d05637fc`.
Credentials package ID is checked as
`71cd9032bcba882996ea7c36b02d677ae05c4b9b83327e1a5e2f412caddad409`,
not a timestamp-sensitive ZIP digest. Git archive and extraction are separate
checked commands; source exports remain available for inspection.

Splice DAR and the single vendored testing Parameters module originate from
Concordia `ac556495684c0c713f6752763b483110ab167935`. Splice DAR SHA-256:
`bcfcd6a9250172a8384d1326a2990b374c66a367ca7d4120b243aecfd372761e`.
Its package ID is
`fb10433a48c24f30076a7aee03a3e314b7ab02fe9a22e2069e3af92d2b6ac88a`.
The upstream Splice revision is unverified. No external DAR was modified.
The testing module retains its original license. No auction DAR is required.
Direct splice-util 0.1.9 coexists locally with embedded 0.1.7, demonstrated by
demo compilation and coupon script execution, not asserted interchangeable.

Build CAP prerequisites normally; then from outside any aggregate project:

```sh
cd "$BABYCDF_BUILD_ROOT"
DAML_PACKAGE="$CONCORDIA_ROOT/examples/governance/babycdf/impl" dpm build --enable-multi-package=no
DAML_PACKAGE="$CONCORDIA_ROOT/examples/governance/babycdf/demo" dpm build --enable-multi-package=no
DAML_PACKAGE="$CONCORDIA_ROOT/examples/governance/babycdf/demo" dpm test
```

## Validation and limitations

The correction round reruns all six BabyCDF scripts, Java's 21 tests,
governance unit 26, ledger 4 and BabyDso 15, with raw outcomes reported in the
implementation handoff rather than relying on prior-branch counts. Coverage
includes consumed vote records, sequential same-baseVersion agendas with stale
execution rejection, generic cast invitation/repeat checks, generic resolver
negative paths and request consumption/replay. ProposeAgenda arguments and
EffectiveRule payload labels are unchanged. The Java fixture constant is
repinned to current RuleVote bytes:
`dbf9506fb19643a0baa7f028ca348ea662d133a2451212329ae4417a0db698db`, after ReviewClause gained its voted text criterion
(criterion, requiredPhrases, forbiddenPhrases). The digest guard rejected the
stale pin until it was explicitly repinned, which is the intended behaviour.
No historical fixture pin or prior-branch test result is
presented as current proof. Fresh independent review is required before any
separate commit authorization.
