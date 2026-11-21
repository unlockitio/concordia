# Demo identity provisioning

Bootstrap provisions two distinct participant-local users and checks the complete
rights returned by `listUserRights`, including cardinality:

- The configured reader user (normally `babycdf-agent`) has exactly
  `[CanReadAs opener]`, with opener as primary party. There is no member read
  grant and no actAs grant.
- `<reader-user>-intake` has exactly `[CanActAs opener]`, with opener as primary
  party. CanActAs includes reads, so no redundant explicit CanReadAs is granted.

`readback` and `readback-intake` each retain five fields: ledger port, opener
party, deployed implementation package ID, governor contract ID, user ID.
The Intake file is provisioning plumbing only: no Intake ledger adapter, main,
step trigger or submission is implemented by this slice.

Reviewer and the demo Advisory entrypoint use the opener party filter for
StateService reads of EffectiveRule and AgendaProposal. Opener signs both
these templates; reading does not exercise the member-controlled ApplyRule
choice. Advisory's `--read-only-sandbox port party package-id` reuses the
state-query gateway, not the command-submitting SandboxBallots adapter. Its
ledger counts are textual readback, not voted-rule authority for its pinned
GitHub fixture review. The existing explicit submission CLI is not reachable
through this read-only mode or the demo entrypoint.

Provisioned rights are not proof of endpoint authorization enforcement. In an
unauthenticated sandbox, user rights readback establishes configuration, not an
API security boundary. This slice does not enable authentication or change
Canton deployment configuration. The readers construct no actAs requests in
their read-only mode; only an authenticated, rights-enforcing deployment can
turn the user grants into a caller authorization boundary.
