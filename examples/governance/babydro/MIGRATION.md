# BabyDRO gov-interfaces migration

Source: Concordia `dev` commit `ac556495684c0c713f6752763b483110ab167935`.
Destination baseline: `gov-interfaces` at `5fd0c9d82d831298a1c9d368a71bdddfb30445a1`.
The source's complete Rights, Dispute, Registry, Demo and PrivacyProof modules
are retained. No shared CAP library, aggregate manifest or BabyCDF file changes.
`governance-unmet` at `c66f8a11dc851ef1194863abb701987c7dffafa1` does not contain
this BabyDRO directory; a read-only Git tree path scan also found no DRO/dispute-named paths there. It was not used as an alternative implementation source.

## Current interfaces

SDK 3.5.11, LF 2.3, direct splice-util 0.1.9. Owner keys use `partyAsOwnerId`.
Registered and direct rights expose VotingRightsV1 with unit weight and the
member as sole caster, retaining issuer, holder, validity, revocation and stint
checks. IsVoteRecord replaces IsBallot; VoteRecordV1 exposes the immutable
ResolutionInfo, snapshots, optional action and DRO consumer. Private consuming
slot lineage, identity-only public enrollment roll and complete-roll checks
remain intact.

Each private Ballot also implements VoteTopicV1. Generic cast checks caster
and owner, delegates to consuming Ballot_Cast/Ballot_Withdraw and returns the
successor CID. `Absent` withdraws; `Abstained` and undecodable decisions abort.
The topic has no fixed action because the selected action depends on the vote.

DisputeResolver implements ResolverV1. Cast requires the existing private slot
CID in ExtraArgs.context `babydro/ballot`; the supplied resolution, action,
case and slot identity must match. `arg.action` must equal `actionOf` the
**pre-cast** ballot, not the action selected by the new vote. A fresh/withdrawn
slot therefore requires `Some firstAction` even when casting KeepSecond;
a client must read the current slot before casting. The successor record then
exposes the action selected by that new vote. Resolve requires a DRO-signed
DisputeResolverRequest CID under `babydro/request`, transporting the consortium,
case, decision and full typed slot map. Generic voteRecordCids must equal that
map's records. It delegates to the original strict typed resolver and spends the
request on success. The result reports RS_Decided and the encoded decision;
executions is empty because the nested publishing child already executes them
atomically. Request transport is not a new rights registry or trustless tally.

Helper mapping: fetchWeightsFromOneContract -> fetchVotingRightsFromOneContract;
consumeBallots -> consumeVoteRecords; agreedAction/agreedSnapshots -> pure
expectUniformAction/expectUniformSnapshots; castsOf -> casts with equalWeights
(after exact voter/slot validation); ActionView.targets -> snapshots;
mergeSnapshots -> mergeSnapshotsWithoutConflicts; requireSameKeys ->
requireMet(validTargetsForSnapshots ...); checkTarget -> fetchTargetMatchingSnapshot.

## Dependencies and isolation

`prepare-dependencies.sh` archives CIPS commit
`8e36c20e5106014efb8348f749d9535afbc48921` already present locally. No checkout
switch, Git fetch or host tool installation. The full interface and full model
are compiled, without removing the independent token-holding module. Interface
package identity is checked as
`71cd9032bcba882996ea7c36b02d677ae05c4b9b83327e1a5e2f412caddad409`.
Metadata DAR SHA-256:
`455eb160cb5abd4ae9918a6fbb9dad471f721adda39f0e5c76feef08d05637fc`.

HoldingV1 is absent from the baseline's lib (HoldingV2 is not a substitute).
The preparation script downloads the exact official artifact from Splice
commit `a710980b66875547e1104e41750cea385620a3ff`, path
`daml/dars/splice-api-token-holding-v1-1.0.0.dar`.
Verified Git blob: `cb12fcf29b0e8a6991cfc95bcd36e945225c9af2`;
SHA-256: `ef75f8eb41a65810221784fdb78bb9dfac7cb22245aba14fa7cb7f69c34e0175`.
The script verifies the checksum even for an existing local artifact.
No upstream Splice source is modified.

BABYDRO_BUILD_ROOT unset defaults to the ignored `.build/`; an explicit root
must be absolute and already exist. Per-run manifests are unique and do not
overwrite caller manifests. CIPS builds run from their isolated manifest CWD.
BabyDRO builds/tests run from outside the repository with DAML_PACKAGE pointing
to impl/demo, builds with `--enable-multi-package=no`. No root build is invoked.

## Validation and remaining trust limits

Both isolated packages build. All original 18 scripts plus genericInterfaces
pass (19 total). The shell contract test is hermetic and retains ignored
fixtures for inspection. Transaction HTML is generated separately under
`.build/privacy` for projection inspection; ACS visibility tests alone are not
proof of transaction privacy. Inspection of the generated projections confirmed:
ballotContentPrivacy enrol #25:4 is all-member, sibling Ballot create #25:6
is voter+DRO; casts/withdrawal TX26–28 remain voter+DRO; TX32 fetches #32:3/4
and consumes #32:10/11 are each voter+DRO, while publish #32:13 is all-member.
Generic resolution TX33 likewise has voter+DRO fetches #33:5/6 and consumes
#33:12/13, with all-member publish #33:15. The receipt's aggregate roll/outcome
is divulged on publish; no individual ballot is divulged to another member in
these inspected journeys. No sandbox/live network deployment was performed.
The demo package retains its original four topology-control templates alongside
Daml Script and therefore emits the template-depends-on-daml-script warning.

The DRO remains trusted for counting. No global name/case uniqueness, proof of
honest tally, cancellation route or automatic latest-slot discovery is added.
The original rollback, stale credential, removed/re-admitted member and
inference limitations remain. Product requirements/provenance KB alignment is
a separate governed slice; this file documents implementation APIs, not accepted
product KB facts. Fresh independent review is required before any separate
commit authorization. No commit or push is part of this implementation task.
