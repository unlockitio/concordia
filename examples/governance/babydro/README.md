<!-- SPDX-License-Identifier: Apache-2.0 -->

# BabyDRO: canton.name governance walkthrough

The CIPs credentials demo owns name requests, registry-issued credentials and their lifecycle. Concordia owns only (1) a dispute process yielding a ruling between two registries' conflicting canton.name claims and (2) admission and removal of registries entitled to dispute. A ruling does **not** change a name credential. Each affected registry follows by its own action on its own CIPs-owned state; the demo archives the losing registry's credential as that registry, and separately records follow acknowledgements. An acknowledgement alone does not prove the external credential action: the journey asserts both contract states.

`impl/daml/Cap/Examples/BabyDro/Dispute.daml` owns the case, ballot, decision and ruling types. `Registry.daml` owns membership motions and the `Consortium` authority: its single contract still aggregates motions, open cases, ballots and rulings. These are cohesive source boundaries, **not** independently persisted case contracts. The demo script is the consumer-facing end-to-end test. LF 2.2 has no contract keys; a fetched per-case CID does not grant the Consortium signatory's authority to replace that case, and consuming a case leaves its former CID stale. A standalone case lifecycle would need an explicitly authorized controller/authority model and atomic replacement of the authoritative CID inventory. This change does not claim that a cosmetic source split provides that lifecycle. The existing consortium maintains unique case IDs across open and ruled cases, counts only current members' ballots, prevents removal of a registry in an open case or unfollowed ruling, and leaves CIPs credentials under their own registries' control.

The source of the name model is in-flight work on the CIPs branch `cip/canton-network-credentials-standard`, consumed as local DAR dependencies, **not** a verified pinned reproducible SHA. Build the CIPs DARs first from `../cips/cn-credentials-standard/demo` with `dpm build --all`. Then, from this repository root:

```sh
dpm build --all
dpm test --package-root examples/governance/babydro/demo
dpm script --dar examples/governance/babydro/demo/.daml/dist/cap-example-babydro-demo-0.1.0.dar --all --ide-ledger
./scripts/sandbox-test.sh
```

The relative DAR paths assume `cips` and `concordia` are siblings, and cannot resolve in a standalone Concordia checkout. Build the sibling CIPs packages before building Concordia; no CIPs source or DAR is vendored here. For a standalone build, supply the CIPs DARs in the same relative layout (without modifying the sources) or change the local dependency paths deliberately. The sandbox runner uploads the built demo DAR and its transitive dependencies.

The naming proposal is [canton-foundation/cips draft PR #209](https://github.com/canton-foundation/cips/pull/209), which has **no assigned CIP number**. The draft motivates dispute governance; it does not prescribe the voting, membership, or credential follow mechanism implemented here. Demo-local choices: three initial registry operators, two distinct votes from current members for each ruling or membership change (an off-ledger k-of-n analogue, **not cryptographic threshold signing**), party-based entitlement, a case ID and reason supplied by the disputer, and explicit follow acknowledgements. Governance cannot verify the existence or truth of a claimed name in CIPs state when a dispute is opened; the journey demonstrates the intended ordering and checks two conflicting issued credentials and other unaffected credentials. Admission can enlarge the electorate; the threshold remains two as a local simplification. Removing a member with an open case or an unfollowed ruling is rejected.
