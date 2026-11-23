# Source-bound agendas (S1b)

Source grant/milestone agendas require an explicit SourceObservationCredential CID,
expected fingerprint/version and evidence pin. The opener-controlled Intake bridge
checks governor.opener; the proposer-controlled governor supplies opener authority.
Both Credential_PublicFetch and RegisteredCredential_PublicFetch are exercised as
opener. Concrete profile/registration mapping, source issuer/opener, repository,
number/kind/URL, fingerprint/version and pin shape are checked. Member callers
supply opener read visibility; the credential remains opener-only. PublicFetch is
not a visibility bypass. The issuer is the trust anchor, not factory provenance.

AgendaAction and AgendaProposal copy the observation, source fingerprint/version,
body evidence digest, source CID, pin, round identity, original proposal number and
previous-round link. Members and the read-only opener Reviewer can read this copy
without reading the credential; later revision/archive cannot mutate it. No body
is copied. ProposalPin separates head commit from file paths/Git blob IDs.
DeliveryPin carries delivery commit, issue digest, vote comment ID and time.
Missing delivery is explicitly BlockedPin/blocked_pin, never executable approval.

A keyed AgendaRoundReceipt binds opener, proposer, round, action CID, base version,
agenda constructor/full subject and copied snapshot/pin. It remains after proposal
or action archival and prevents accidental reopen after client journal loss WHILE
the receipt remains active. Built-in Archive is an authorized opener reset and
enables reopening that round. S3 scheduler MUST NEVER archive receipts. Like
all opener-signed demo state, a malicious opener can directly assert or archive
state; this is not a provenance guarantee against the issuer itself. Amendments
may branch from existing proposal-kind receipts with the same original proposal
identity. Milestones use a keyed per-subject head: first round omits previous,
subsequent rounds must reference the latest active receipt for the same repository,
number and kind. Cross-kind or another milestone links fail. Receipt/head reset by
the issuer is outside scheduler behavior and is not malicious-issuer protection.

One canonical agenda/snapshot predicate binds URL, fingerprint, kind, opener and
pin type at governor creation, action creation/authorization/execution and approval
creation. Delivery issueDigest equals the observation bodySha256; voteCommentAt
(trigger time) must be at least observation.updatedAt, so a vote on an older issue
revision cannot authorize the copied newer observation. This verifies consistency,
not provider authenticity. Proposal tree membership/file-to-head verification is
S2 GitHub evidence work, not a claim of S1b.

Java production planning reads the signed IntakeRequest agenda/source rather than
a curated selector. It does not promote an asserted digest into verified bytes.
Receipt proof requires the linked action as well; after action archival it uses
bounded retained transaction evidence (10000 responses/20MB/15-second deadline).
Missing/pruned evidence requires operator reconciliation, never blind retry or
absent-ACS-as-noncommit reasoning. Legacy test planning/old-journal seams
remain explicitly separate and cannot satisfy the new Daml source-bound trigger.

Architecture/RuleRef, five permanent plus two alternate members and the exact
atLeastPresent3/shareOfVotes1:2 formula are unchanged. Opener does not gain a vote.
Step3 uses the real PR184 head c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521 and
proposals/concordia.md blob f591787a772848e4537aba5d8ba02be1764647da. Its local
source observation is explicitly a demo issuer assertion, not live discovery.
Step4 #540 has no real delivery pin: it records blocked_pin with no executable
approval. Synthetic complete delivery tests are labeled synthetic, not #540 proof.
Coupon API test is separate sandbox API behavior and claims no milestone approval.

No live upload, stack, provider or network acceptance is implied by offline tests.
