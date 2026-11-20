<!-- SPDX-License-Identifier: Apache-2.0 -->
# BabyDRO local container demo

From this checkout (all parties are synthetic sandbox identities; the host drives every actor):

```sh
cd examples/governance/babydro
sh demo/run-demo.sh up
sh demo/run-demo.sh step 1
sh demo/run-demo.sh step 2
sh demo/run-demo.sh step 3
sh demo/run-demo.sh step 4
sh demo/run-demo.sh step 5
sh demo/run-demo.sh step 6
# Alternatively, after down/up on a fresh ledger:
# BABYDRO_PAUSE=2 sh demo/run-demo.sh all
# Separate optional regression suite (not the narrative):
sh demo/run-demo.sh test
sh demo/run-demo.sh status
sh demo/run-demo.sh logs
sh demo/run-demo.sh down
```

Prerequisites: Docker daemon with Compose **>=2.24.4** (`!override`),
`dpm` 1.0.22 / Daml SDK 3.5.11, Git, tar, curl, shasum and the local
CIPS object `8e36c20e5106014efb8348f749d9535afbc48921`.
Set `CIPS_REPO` if the adjacent CIPS checkout is elsewhere. No fetch,
checkout, toolchain installation, Java agent or canton-dev-fund checkout
is performed. Existing CAP dependency DARs must already be built as described
in the package README; this runner never rebuilds shared packages.
The missing HoldingV1 DAR may be downloaded from its checksum-pinned source.
Docker may download base images during its build.

`up` prepares local pinned source and credential DARs, builds BabyDRO impl
then demo using fresh isolated multi-package manifests, validates Compose,
and waits at most 180 seconds for Canton readiness by default. On a busy
shared Docker VM use `BABYDRO_READY_TIMEOUT=1800 sh demo/run-demo.sh up`
(positive integer seconds); this changes only the readiness budget. It does not run scripts
automatically. Failed startup leaves its own container available for logs and
explicit `down`; it never tears down another project. Concurrent operations
and a second `up` with existing BabyDRO containers are refused.

`test` uploads the demo DAR and runs **all 19 existing scripts** against the
real ledger using wall-clock time. This includes the complete `journey`
(consortium, admission, conflicting name claims, voting and resolution),
negative authorization/rollback cases and privacy assertions. These scripts
use `getTime`, not `setTime` or `passTime`; no contract semantics or test
assertions were weakened for this runner. Parties are freshly allocated on
each run. Tests add ledger state; this is a demo, not a production service.
Privacy checks model parties on one participant, not multi-participant
network confidentiality or external deployment.

## Narrative steps and implemented scope

| Step | Actors and demonstrated ledger effects |
|---|---|
| 1 | DRO proposes genesis; all three founders accept; consortium finalized; DRO invites each holder to accept a direct voting right. |
| 2 | Holders read their rights; registry-a cannot query registry-b's private right. |
| 3 | registry-a proposes admission, registry-b supports, candidate accepts invitation, member finalizes consent; candidate receives a fresh right. |
| 4 | Registrars issue conflicting demo name credentials; DRO invites voters; a/c vote KeepFirst and b KeepSecond; live ballot visibility is checked; trusted DRO resolves and archives losing claim. |
| 5 | Post-resolution readback: public Ruling visible, ballots already consumed; this ACS check alone is not a proof of tally transaction privacy. |
| 6 | registry-a proposes CurrentMembership, registry-b supports, registry-a enacts; new member reads updated policy. |

Each step uses the same ledger and the preceding JSON result under
`demo/.state/story/`. Run in order. Repeated or attempted steps are refused;
after any failed attempt, use `down` then `up`. Do not delete markers to retry
partially committed work. `all` requires a fresh story and pauses between
steps (`BABYDRO_PAUSE`, non-negative integer seconds, default 2). Journals
are preserved as `story-previous-*` by `up`; old references cannot be reused
on a restarted in-memory ledger. Debug logs are stdout, not state JSON.
`BABYDRO_DAR` optionally selects a prebuilt demo DAR; its default is
`demo/.daml/dist/cap-example-babydro-demo-0.1.0.dar`. Offline runner tests
use temporary empty DAR fixtures and do not require a generated `.daml` tree.

The credential name model is the pinned **draft CIPS demo**: each registry
admin issues via `DemoNameRequest` and `DemoCredentialRegistryFactory`
in `Demo.daml`. This creates actual sandbox ledger contracts, not
external identity verification, production certificates or globally unique
names. Voting entitlements are separately issued by the DRO, with direct
and registered CIPS-compatible routes in `Rights.daml`; the narrative
uses direct rights. Regression `rightRoutes` covers both routes.

Genesis, membership/removal, invitation/consent and policy motions are
implemented in `Registry.daml`. `NameDispute`, `Ballot`, `Ruling` and the
resolver choices in `Dispute.daml` and `Registry.daml` implement disputes,
withdrawal/recast, resolution and loser archive actions. The DRO is a **trusted resolver**, not trustless or
cryptographically verified tallying. Public outcomes and participation can
permit inference in small electorates. There is no external registrar
onboarding service, identity-proofing system, email invitation delivery,
independent tally auditor, global uniqueness service or dispute cancellation
route in this example. `Ruling_Follow` acknowledges a ruling; it does not
perform external enforcement. See the package README for the precise trust
model and frozen-credential deadlock limitation.

## Isolation and data

The fixed Compose project is `babydro-live`, with its own default-scoped
`canton` network and a single Canton service. Only
`127.0.0.1:${BABYDRO_LEDGER_PORT:-41100}` is published; HTTP remains internal.
BabyCDF on port 41000 can stay running. Use `BABYDRO_LEDGER_PORT=41110`
on **every** command if that port is occupied. Do not reuse CDF's port.
Canton defaults to a **2 GiB container limit** and **`-Xms256m -Xmx1g`**; override
`BABYDRO_CANTON_MEM_LIMIT` and `BABYDRO_JAVA_OPTS` deliberately if needed.
The default is not a guarantee of headroom under concurrent load. The only limit validated live is `2g`
(a point observation of approximately 1.88 GiB was reported by the
implementer, not retained as a cited log sample; heap peak not measured).
This observation is not a certified peak or a guarantee of margin. Overrides are not validated alternatives: `BABYDRO_CANTON_MEM_LIMIT=3g`
with the same 1 GiB heap failed readiness in a smoke attempt, cause undetermined;
`BABYDRO_CANTON_MEM_LIMIT=4g` with
`BABYDRO_JAVA_OPTS='-Xms512m -Xmx2g'` failed step 3 under concurrent load.
GC diagnostics can be enabled by appending
`-Xlog:gc*:stdout:time,uptime,level,tags` to those Java options.
The final run's Docker inspect, captured in
`demo/.state/diagnostics/default2g-final-all.log`, reports `Memory=2147483648`
(2 GiB) and `MemorySwap=4294967296` (4 GiB total memory plus swap allowance).
Thus `2g` is the RAM limit, not a hard limit on combined memory and swap:
the container can additionally use swap, which may contribute to slowdowns.
Budget resources for both projects; the runner never stops CDF to free memory.

`down` removes only this project's service containers and networks. It does
**not** use `--volumes` or `--remove-orphans`. The pinned sandbox uses no
persistent volume here: the pinned participant and sequencer use in-memory
storage, so demo ledger state is lost even on container restart.
No pre-existing volume or exported source is deleted. Exports and build
scratch are retained under ignored `.build/`, `demo/compose/.cips/`, and
`demo/.state/`. `BABYDRO_BUILD_ROOT`, if set, must be absolute and already
exist. `BABYDRO_STATE_DIR` can isolate lock storage for offline tests; use the
same value across concurrent real invocations. After a hard kill, inspect
processes before manually clearing a stale empty lifecycle lock directory.

Offline checks (no Docker daemon or network):

```sh
sh -n demo/run-demo.sh
sh -n demo/compose/prepare-cips.sh
python3 demo/test-runner.py
sh test-build-root.sh
```

These contract checks are not a substitute for the real `up` → `test` →
`logs`/`status` → `down` lifecycle.

## Example narrative output

Actual step-3 output from the local Canton run (Daml debug prefixes and
contract IDs omitted here for readability):

```text
STEP 3 | registry-a proposes admitting a new registrar; registry-b supplies second support.
An approved MembershipMotion creates AdmissionInvitation; target accepts; consent is finalized.
READBACK new registrar | member admitted; fresh stint-bound voting right exists.
--- step 3 complete; ledger references saved ---
```

The host transcript includes full party and contract IDs. These logs expose
the synthetic votes to the demo operator deliberately; they are not the
restricted ledger view of an ordinary member.

## Local narrative validation — 2026-11-20

The paired profile **`-Xms256m -Xmx1g`, container limit `2g`** passed
steps 1–6 both with concurrent containers and without concurrent containers.
A further `all` on a clean ledger without concurrent containers was reported
successful by the implementer; `light1g-isolated-all.log` does not record its
RC or duration. For directly recorded RC and timing, use the final
`default2g-final-all.log` confirmation below. The paired profile **`-Xms512m -Xmx2g`,
container limit `4g`** passed steps 1–2 but failed step 3 with
`ABORTED: NOT_SEQUENCED_TIMEOUT` under concurrent load.
Peak used heap was not reliably measured. Sampled container usage around
1.88 GiB was a point observation reported by the implementer, not a cited
retained sample or a certified peak.
These runs do not isolate a single timeout cause: concurrent load varied.
No consensus timeout or contract assertion was changed, and no automatic
submit retry follows potentially partially committed work.
A later smoke attempt with the override pair **`-Xms256m -Xmx1g`,
container limit `3g`** built successfully but did not reach healthy readiness
within the default 180 seconds; step 1 was not executed, cause undetermined.
This override pair has no completed live story validation. The default is
therefore the validated pair `-Xms256m -Xmx1g` with container limit `2g`.
A final clean-ledger confirmation with the restored default pair
`-Xms256m -Xmx1g`, container limit `2g`, completed `all` with **RC 0 in
297.151 seconds**, without a readiness retry. Build, readiness and teardown
also succeeded; the complete operation took 508.289 seconds. The log
`demo/.state/diagnostics/default2g-final-all.log` records RC and timing and
includes the corrected post-resolution step-5 header.
Logs are retained locally under `demo/.state/diagnostics/`, including
`baseline-steps.log`, `baseline-canton.log`, `light1g-steps.log`,
`light1g-isolated-steps.log`, `light1g-isolated-all.log` and
`default2g-final-all.log`.
Do not run Daml build and tests concurrently against the same package database.

## Earlier local regression validation evidence

On 2026-11-20, Docker Compose 5.6.1 built the pinned Canton 3.5.11
container and reached healthy readiness on localhost 41100 while BabyCDF
remained running on 41000. `sh demo/run-demo.sh test` reported **SUCCESS for
all 19 scripts**, including `journey`, `genericInterfaces`,
`ballotContentPrivacy` and `resolverProjection`. The same 19 scripts passed
in-memory `dpm test`. The live suite took approximately 16 minutes on the
shared 8 GiB Docker VM; readiness is bounded, but the full script suite has
no imposed total timeout. These results do not claim multi-participant or
external deployment validation.
