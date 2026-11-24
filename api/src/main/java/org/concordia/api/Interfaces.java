// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class Interfaces {
    private Interfaces() {}
    public record Action(JsonNode authorizers, JsonNode snapshots, JsonNode meta) {}
    public record Executable(JsonNode executionCore, JsonNode snapshots, JsonNode outcome) {}
    public record Resolver(String keyId, List<String> mechanisms, JsonNode meta) {}
    public record VoteRecord(JsonNode resolution, JsonNode votes, JsonNode snapshots, String action, List<String> consumers, JsonNode meta) {}
    public record VotingRights(String keyId, JsonNode rights, String validUntil, JsonNode meta) {}
    public record VoteTopic(JsonNode resolution, String proposer, Reason subject, String action, String voteBefore, String effectiveAt, JsonNode meta) {}
    public record Reason(String url, String body) {}
    public record BidProposal(JsonNode authorities, JsonNode resolutionInfo, JsonNode terms, JsonNode availableActions, JsonNode meta) {}
    public record Bid(JsonNode resolutionInfo, JsonNode terms, JsonNode schedule, JsonNode fundings, JsonNode availableActions, JsonNode meta) {}
    public record SingleLotQuotes(JsonNode quotes) {}
    public record Settlement(JsonNode executionCore, JsonNode resolutionInfo, JsonNode executors, JsonNode batches, JsonNode fundings, JsonNode meta) {}
    public record TradeProposal(JsonNode resolutionInfo, JsonNode terms, JsonNode schedule, String acceptBefore, JsonNode availableActions, JsonNode meta) {}
    public record Contract(String contractId, Object view) {}
    public record Page(List<Contract> items, String snapshot, String nextCursor) {}
    public record ActionLink(String status, Contract target, String snapshot) {}

    public enum Kind {
        ACTION("governance", "actions", "cap-governance-action-v1:Cap.Governance.ActionV1:Action", Action.class),
        EXECUTABLE("governance", "executables", "cap-governance-executable-v1:Cap.Governance.ExecutableV1:Executable", Executable.class),
        RESOLVER("governance", "resolvers", "cap-governance-resolver-v1:Cap.Governance.ResolverV1:Resolver", Resolver.class),
        VOTE_RECORD("governance", "vote-records", "cap-governance-vote-record-v1:Cap.Governance.VoteRecordV1:VoteRecord", VoteRecord.class),
        VOTING_RIGHTS("governance", "voting-rights", "cap-governance-voting-rights-v1:Cap.Governance.VotingRightsV1:VotingRights", VotingRights.class),
        VOTE_TOPIC("governance", "vote-topics", "cap-governance-vote-topic-v1:Cap.Governance.VoteTopicV1:VoteTopic", VoteTopic.class),
        BID_PROPOSAL("auctions", "bid-proposals", "cap-auctions-bid:Cap.Auctions.Bid:BidProposal", BidProposal.class),
        BID("auctions", "bids", "cap-auctions-bid:Cap.Auctions.Bid:Bid", Bid.class),
        SINGLE_LOT_QUOTES("auctions", "single-lot-quotes-bids", "cap-auctions-single-lot-quotes:Cap.Auctions.SingleLotQuotesBid:SingleLotQuotesBid", SingleLotQuotes.class),
        SETTLEMENT("auctions", "settlements", "cap-auctions-settlement:Cap.Auctions.Settlement:Settlement", Settlement.class),
        TRADE_PROPOSAL("auctions", "trade-proposals", "cap-auctions-trade-proposal:Cap.Auctions.TradeProposal:TradeProposal", TradeProposal.class);
        public final String domain;
        public final String collection;
        public final String selector;
        public final Class<?> viewType;
        Kind(String domain, String collection, String selector, Class<?> viewType) {
            this.domain = domain; this.collection = collection; this.selector = selector; this.viewType = viewType;
        }
        public static Kind resolve(String domain, String collection) {
            return Arrays.stream(values()).filter(k -> k.domain.equals(domain) && k.collection.equals(collection)).findFirst()
                    .orElseThrow(jakarta.ws.rs.NotFoundException::new);
        }
    }
}
