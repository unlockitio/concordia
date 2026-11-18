# Live demo probe record

**Probe record, superseded operationally by [../DEMO.md](../DEMO.md).**
The implementation is a partial network/bootstrap foundation, not the complete
six-step live presenter journey. No probe CIDs or host-specific temp paths are
operational configuration.

## Pinned base and verified workaround

CIPS commit: `8e36c20e5106014efb8348f749d9535afbc48921`.
Source paths: cn-credentials-standard/demo/compose.yaml,
Dockerfile.canton, demo/canton/sandbox.conf and bootstrap.canton.
One Canton process runs an in-memory participant, BFT sequencer and mediator.
The service has no prerequisite services; Postgres/PQS/seed are downstream.

Default 3 GiB container / observed 768 MiB JVM heap failed participant activation
(`verify active` remained false; connect_local topology wait expired). Sequencer
ordering, mediator activation and topology persistence had succeeded. No OOM
kill or architecture mismatch was observed. A 4 GiB limit with
JAVA_TOOL_OPTIONS=-Xms512m -Xmx2g reached health twice, and again through the
BabyCDF wrapper. This is a verified resource workaround, not an isolated proof
of heap as unique cause. Verified on one arm64 Docker Desktop host only: 8 CPUs,
about 8 GiB VM memory, Docker 29.9.2 / Desktop 4.97.0 / Compose v5.6.1.
Canton build reports 3.5.11, Daml libraries 3.5.2, protocols 34/35.

## Networking

Upstream published Ledger 41000->6865 and HTTP 41001 -> 7575 on all interfaces.
BabyCDF MUST replace those with ports !override and loopback-only Ledger API;
HTTP is not published. Internal healthcheck uses HTTP 7575 connected-synchronizers
and requires alias credential-synchronizer. Sequencer public/admin 6858/6859,
mediator admin 6860 and participant admin 6866 remain internal.

## Verified compatibility and narration

Live SDK3.5.11/LF2.3 BabyCDF DAR upload, party allocation and governor creation
passed using bootstrapAdvisoryGovernor with wall-clock time and upload-dar true.
Live Daml debug prints on stdout without a log-level flag. Input -> Script Output
is skipped by dpm test; parameterized presenter steps need explicit integration
execution. Existing static-time tests cannot be substituted for live steps.

Full CIPS stack build failed independently because credentials Dockerfile.daml
staged metadata but omitted splice-api-token-holding-v1-1.0.0.dar needed by its
model. BabyCDF reuses only the verified Canton service, not that full app graph.

## Images and packaging plan

Already resolved by CIPS probes: eclipse-temurin:21-jre, eclipse-temurin:21-jdk,
maven:3.9.9-eclipse-temurin-21, maven:3.9-eclipse-temurin-21, and
busybox:1.37.0-musl@sha256:fc6dddc4c44b1bfe37f41cae8e67d1693828e8f42a91862816d7953e2c9d3f23.
Current foundation uses Temurin21-jre only, with checksum-pinned Canton tarball
from the CIPS Dockerfile. No Dozzle needed. Planned agents use Maven builder,
copy-dependencies and slim JRE, shared Canton network namespace to preserve
Java loopback-only transport, and prefixed Compose logs.

## Remaining six steps

01_bootstrap: parties, credentials, governor, rights/user verification and state.
02_rule_vote: v1 -> v2, sequential same-baseVersion stale execution rejected.
03_proposal_vote: pinned PR184 proposal, votes and ApprovedGrantProposal.
04_milestone: insufficient evidence blocked, then illustrative approved path.
05_coupon: sandbox-local issuance/allocation, no payment or beneficiary mint.
06_blocked_paths: invalid right, no quorum, repeat vote and non-member.

Use short parameterized wall-clock windows and explicit waits, never weaken
production time/quorum/credential checks. Advisory currently hardcodes one-hour
proposal windows; it needs a reviewed demo duration or preview-only orchestration.
Reviewer is currently one-shot. Agents, user provisioning and numbered steps
remain unimplemented. Every run's in-memory CIDs expire at down; atomic bootstrap
publication avoids replacing successful JSON with a partial result, but old JSON
must never be interpreted as proof of a currently running ledger.
