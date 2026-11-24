<!-- SPDX-License-Identifier: Apache-2.0 -->
# CAP query API

Standalone Java 21 / Quarkus 3.40.1.2 LTS reader for all eleven CAP interfaces.
One shared resource, interface registry, query port and PQS JDBC adapter serve
all interfaces. `api/` is independent of BabyCDF and the Daml build. Core has
no interfaces. No choices or write operations are exposed.

## Setup

From `api/`, with Java 21 and Maven:

```sh
export PQS_JDBC_URL=jdbc:postgresql://localhost:5432/pqs
export PQS_USERNAME=pqs_reader
export PQS_PASSWORD=your-local-secret
mvn test
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

Tests include a disposable PostgreSQL container and require Docker. No host
package installation is required. Database tests use synthetic public-function
fixtures, not a PQS installation. Live PQS integration remains unverified.
Configure the database search path for installed public PQS readers. Use a
restricted reader role and put authentication/authorization and TLS in front
of the service before sharing participant-visible data. The API itself does
not enforce application authorization.

## Complete endpoint matrix

Every row supports `GET <collection>` and `GET <collection>/{contractId}`.
All queries read active interface projections only.

| Interface | Collection | Daml source under `interfaces/` | View |
| --- | --- | --- | --- |
| Action | `/v1/governance/actions` | governance/action/Cap/Governance/ActionV1 | ActionView |
| Executable | `/v1/governance/executables` | governance/executable/Cap/Governance/ExecutableV1 | ExecutableView |
| Resolver | `/v1/governance/resolvers` | governance/resolver/Cap/Governance/ResolverV1 | ResolverView |
| VoteRecord | `/v1/governance/vote-records` | governance/vote-record/Cap/Governance/VoteRecordV1 | VoteRecordView |
| VotingRights | `/v1/governance/voting-rights` | governance/voting-rights/Cap/Governance/VotingRightsV1 | VotingRightsView |
| VoteTopic | `/v1/governance/vote-topics` | governance/vote-topic/Cap/Governance/VoteTopicV1 | VoteTopicView |
| BidProposal | `/v1/auctions/bid-proposals` | auctions/bid/Cap/Auctions/Bid | BidProposalView |
| Bid | `/v1/auctions/bids` | auctions/bid/Cap/Auctions/Bid | BidView |
| SingleLotQuotesBid | `/v1/auctions/single-lot-quotes-bids` | auctions/single-lot-quotes/Cap/Auctions/SingleLotQuotesBid | SingleLotQuotesView |
| Settlement | `/v1/auctions/settlements` | auctions/settlement/Cap/Auctions/Settlement | SettlementView |
| TradeProposal | `/v1/auctions/trade-proposals` | auctions/trade-proposal/Cap/Auctions/TradeProposal | TradeProposalView |

Source paths expand to `cap-<domain>/interfaces/<package>/daml/<module>.daml`.
Exact package/module/interface selectors live in `Interfaces.Kind` and are
independently asserted in adapter tests. Bid and BidProposal share
`cap-auctions-bid`. Each view has an explicit record with all declared fields;
compound shared/external Daml values preserve their JSON encoding. OwnerId is
text and Reason is `{url,body}`. A SingleLotQuotes view contains only quotes;
read its Bid projection separately for inherited Bid data.

## Four supported relationships

| GET route | Cardinality and semantics |
| --- | --- |
| `/v1/governance/vote-topics/{contractId}/action` | Optional direct generic Action reference |
| `/v1/governance/vote-records/{contractId}/action` | Optional direct generic Action reference |
| `/v1/governance/vote-topics/{contractId}/vote-records` | Paginated records sharing complete structural ResolutionInfo |
| `/v1/auctions/bids/{contractId}/single-lot-quotes` | Same-contract SingleLotQuotesBid facet, requires visible active Bid |

Parent and target/children use exactly the same explicit PQS offset. An absent
parent returns 404. A visible topic with no records returns 200 with empty
items; a missing facet returns 404. Action links return an envelope with snapshot,
status and nullable target. Status is `available`, `no_generic_reference`, or
`target_unavailable`. No generic reference does NOT mean there is no
application-specific action. Unavailable means no active visible Action
projection was found at that offset; it does not reveal why.

Topic-to-record grouping compares the entire JSONB `resolution` object,
including resolver `{authorities,id}`, resolverCid, mechanism and id. It does
not join on topic CID or resolution id alone. PostgreSQL structural equality
ignores object key ordering but does not normalize arrays; the same resolution
must use the same Daml encoding. This is a shared-resolution grouping, not a
claim that a topic created those records. No action-to-executables,
proposal-to-bid or trade-to-settlement link is inferred from choice results.

## Pagination, snapshots, errors

Collections use limit 20 by default, range 1..100, C-collated contract-ID ordering,
strict keyset continuation and fetch-plus-one. IDs are opaque strings. Responses:
`{items:[{contractId,view}],snapshot:"42",nextCursor:null}`. Snapshot strings
preserve bigint precision. Detail reads latest available PQS state. Cursors
retain the original snapshot and bind to collection and, for nested records,
parent/filter context. Cross-resource/parent continuation returns 400. The
version-1 prototype cursor format is no longer accepted. Cursors are unsigned,
not secrets or authorization tokens, and must be treated as opaque.

400: invalid pagination/cursor/context. 404: absent active detail/parent/facet.
503: PQS failures, including unavailable/pruned snapshots, with
`{"code":"pqs_unavailable"}`. Start a fresh collection request after pruning.
Active does not imply voting or bidding remains open; times are returned, not
used for additional filtering.

## Contract and evidence boundary

`src/main/resources/META-INF/openapi.yaml` is the sole authoritative API
specification (JSON syntax is valid YAML). It is served at `/q/openapi` with
`mp.openapi.scan.disable=true`. Swagger UI: `mvn quarkus:dev`, `/q/swagger-ui`.
Tests assert every collection/detail and nested path, DTO registration,
pagination constraints, snapshot string type and error responses.

Public readers only: `set_latest(latest_offset())`,
`validate_offset_exists(bigint)`, `active(text,bigint)`. Selectors, IDs, offsets,
limits, keysets and JSONB filters are bound parameters. The CIPs DID reference
notes PQS 3.5.8 returns NULL from `set_latest(NULL)`, hence explicit latest_offset.
No pool session affinity is assumed. Concurrent pruning can invalidate offsets.

Encoding assumptions follow current PQS record/string/null JSON conventions;
older PQS releases may differ. See https://docs.canton.network/appdev/reference/pqs-sql-reference
and https://docs.canton.network/appdev/modules/m4-query-with-pqs.
Synthetic SQL-backed fixtures prove adapter SQL and structural filtering, not
installed PQS function implementations, actual projection availability or a
live participant deployment. No Daml source changes or Daml build are needed.
Existing CI remains Daml-only; Java checks are local in this slice.

The complete patched Quarkus platform manages Netty 4.1.139.Final without
individual overrides. Review TLS and JDBC pool defaults when deploying; see
https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31.
