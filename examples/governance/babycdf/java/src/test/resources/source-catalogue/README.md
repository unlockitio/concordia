# Captured source attestation regression

`captured-93.bin.gz` is an offline fixture captured read-only from the paused
BabyCDF acceptance ledger on 2026-11-20 (Canton 3.5.11). It contains the exact
serialized journal request, committed transaction 93, and historical ACS at the
journal BEGIN offset 92. No submission or journal alteration produced this fixture.
The source observations are public canton-foundation/canton-dev-fund GitHub data;
party IDs are ephemeral local demo identities. No credentials are included.

Gzip wraps a DataOutputStream: BEGIN long, request length int + protobuf bytes,
transaction length int + protobuf bytes, ACS response count int, then each ACS
response length int + protobuf bytes. The test enforces bounded lengths and EOF.

Original production code rejected the exact captured transaction at protobuf
choice-argument equality: verbose readback adds record/enum type IDs. After typed
argument comparison, the next rejection was omitted trailing `previousDigest=None`.
Canton normal form intentionally omits trailing Optional None fields:
https://blog.digitalasset.com/developers/release-notes/canton-3.4-release-notes-for-splice-0.5.0

The regression performs zero RPCs and never writes a ledger receipt. It retains
all original observation evidence and verifies all original attestation checks,
including created effects and checkpoint scan fingerprint against historical ACS.
Mutations test changed choice arguments, missing required fields, and an invalid
revision without previousDigest. The immutable original raw captures and the
original failing replay log remain under BabyCDF `.build/attestation-*` locally.
