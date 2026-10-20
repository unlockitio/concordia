<!-- SPDX-License-Identifier: Apache-2.0 -->

# BabyDRO: name dispute walkthrough

This standalone Daml example models one designated canonical `NameRegistry`. An allowlisted registrar registers a name and can challenge an existing record with a reason. Three registry operators cast individual on-ledger votes; two distinct approvals for the same outcome permit an operator to resolve. Deliberation and evidence exchange happen off ledger. Uphold removes the contested name and preserves the dispute's reason in the audit history; dismiss removes only the selected dispute and leaves the registration and other disputes intact. A separate registry instance cannot exercise choices on the canonical registry or change its record.

The naming CIP draft in [PR #209](https://github.com/canton-foundation/cips/pull/209) motivates the naming dispute demonstration. The fixed three-operator electorate, two-of-three threshold, allowlist, vote representation, registry selection, and audit layout are **local demonstration assumptions**, not assertions that the draft mandates these details. This is a ledger-state voting analogue, not cryptographic threshold signing or inter-registry arbitration. An integrator must designate and retain the canonical registry contract ID across consuming choices; this example does not implement a global discovery service or enforce unique registry creation across independent parties. The owner signs registry replacements in the exercise transaction under Daml authorization semantics.

From the repository root:

```sh
dpm build --all
dpm script --dar examples/governance/babydro/demo/.daml/dist/cap-example-babydro-demo-0.1.0.dar --all --ide-ledger
```

The scripts cover uphold, dismiss with another live dispute, one-vote insufficiency, duplicate/outsider votes, unauthorized registration/challenge, and independence of a second registry. They are the consumer-facing ledger workflow tests for this example. The built DAR runs with `dpm script --ide-ledger` without recompiling data-dependency interfaces. On this SDK installation `dpm test --package-root examples/governance/babydro/demo` fails during interface compilation for `daml-prim` / `daml-stdlib`; the existing private-majority-vote demo fails in the same way. The IDE ledger run executes the built scripts and their assertions, but does not establish behavior on a Canton sandbox.
