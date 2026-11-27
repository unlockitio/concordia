# Possible extensions

- **Member status report:** a contract each member's DecOperator service updates at a fixed interval, so members see which participant nodes are live before they plan a vote or a threshold change.
  Splice does this with `SvStatusReport` (`splice-dso-governance/daml/Splice/DSO/SvState.daml`), signed by the DSO party and submitted through `DsoRules_SubmitStatusReport`.

- **Public membership credential:** a CIP-0204 credential, issued by the decentralized party to an acting party of each Active member, that attests to outsiders that the member's identity belongs to the operator; revoked on removal.
  Splice issues comparable DSO-signed attestations (`ValidatorLicense`, `FeaturedAppRight`); this one waits for CIP-0204 (canton-foundation/cips#204, draft) to ship.

- **Membership application:** a contract the applicant signs with its acting parties, participant node, beneficiaries and service, which `DOA_AddMember` must match, so the applicant consents on the ledger before the vote.
  Splice gets this consent off the ledger: the candidate signs an `SvOnboardingToken` that the sponsor puts in `SvOnboardingRequest` (`splice-dso-governance/daml/Splice/SvOnboarding.daml`).
