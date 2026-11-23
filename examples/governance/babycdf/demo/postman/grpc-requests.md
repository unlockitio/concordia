# Host ledger queries: unauthenticated local demo only

Canton must be running after the next explicit down/up so the new HTTP mapping applies. Never bind host ports to 0.0.0.0. Defaults: HTTP `127.0.0.1:41001`, gRPC `localhost:41000`; override LEDGER_HTTP_PORT / LEDGER_PORT before starting. No token or Authorization header is needed for this demo.

## HTTP / curl / Postman
Import `babycdf-ledger-http.postman_collection.json` (v2.1). Send Parties first: its test selects the demo-opener party. The collection pre-request script refreshes the ledger-end offset before each request. Set party manually if your allocation prefix differs. Active contracts are a snapshot at that offset; updates are bounded from 0 to it. Package-name references use implementation `#cap-example-babycdf` and source-catalogue/demo `#cap-example-babycdf-demo`.

```sh
curl --fail http://127.0.0.1:41001/v2/version
curl --fail http://127.0.0.1:41001/v2/state/ledger-end
curl --fail http://127.0.0.1:41001/v2/parties
BABYCDF_HTTP_VIA=host ./ledger.sh http contracts
BABYCDF_HTTP_VIA=host ./ledger.sh rule
```
Use the collection's raw JSON bodies with `curl --fail -H 'Content-Type: application/json' --data-binary @request.json http://127.0.0.1:41001/v2/state/active-contracts`.

## gRPC in Postman
New → gRPC Request, address `localhost:41000`, plaintext (TLS off), select server reflection. gRPC requests cannot be imported as a Postman HTTP collection. Methods:

- `com.daml.ledger.api.v2.StateService/GetLedgerEnd`: `{}`
- `com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties`: `{}`
- `com.daml.ledger.api.v2.StateService/GetActiveContracts` (replace PARTY and OFFSET):
```json
{"active_at_offset":0,"event_format":{"filters_by_party":{"PARTY":{"cumulative":[{"wildcard_filter":{}}]}},"verbose":true}}
```
Set active_at_offset to GetLedgerEnd.offset, not zero, for current contracts.

Template-filtered request (replace OFFSET):
```json
{"active_at_offset":0,"event_format":{"filters_for_any_party":{"cumulative":[{"template_filter":{"template_id":{"package_id":"#cap-example-babycdf","module_name":"Cap.Examples.BabyCdf.RuleVote","entity_name":"EffectiveRule"}}}]} ,"verbose":true}}
```
For source credentials use package `#cap-example-babycdf`, module `Cap.Examples.BabyCdf.SourceObservationCredential`, entity `SourceObservationCredential`. Checkpoints remain in package `#cap-example-babycdf-demo`, module `Cap.Examples.BabyCdf.SourceCatalogue`, entity `SourceCheckpoint`. Other implementation entities: AgendaProposal, AgendaBallot, ApprovedGrantProposal, ApprovedMilestone.

- `com.daml.ledger.api.v2.UpdateService/GetUpdates` (replace PARTY and end_inclusive with the captured OFFSET):
```json
{"begin_exclusive":0,"end_inclusive":1,"update_format":{"include_transactions":{"transaction_shape":"TRANSACTION_SHAPE_ACS_DELTA","event_format":{"filters_by_party":{"PARTY":{"cumulative":[{"wildcard_filter":{}}]}},"verbose":true}}}}
```

## grpcurl from the host
The executable is not assumed installed. If installed:
```sh
grpcurl -plaintext -d '{}' localhost:41000 com.daml.ledger.api.v2.StateService/GetLedgerEnd
grpcurl -plaintext -d '{}' localhost:41000 com.daml.ledger.api.v2.admin.PartyManagementService/ListKnownParties
grpcurl -plaintext -d @ localhost:41000 com.daml.ledger.api.v2.StateService/GetActiveContracts < grpc-acs.json
grpcurl -plaintext -d @ localhost:41000 com.daml.ledger.api.v2.UpdateService/GetUpdates < grpc-updates.json
```
Alternatively use the pinned client image from the host (Docker Desktop):
```sh
docker run --rm -i fullstorydev/grpcurl@sha256:085e183ca334eb4e81ca81ee12cbb2b2737505d1d77f5e33dabc5d066593d998 -plaintext -d '{}' host.docker.internal:41000 com.daml.ledger.api.v2.StateService/GetLedgerEnd
```
For other methods replace the method/body as above. This creates only a short-lived client, not a ledger container; it was not run during offline validation. On Linux arrange host networking explicitly. `ledger.sh` keeps its existing exec-based HTTP default for compatibility; `BABYCDF_HTTP_VIA=host` opts into direct host curl. All viewer allowlists remain enforced.
