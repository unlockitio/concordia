// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import static org.concordia.api.Interfaces.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PqsVoteTopicsTest {
    PqsInterfaces client;
    Connection connection;
    PreparedStatement statement;
    ResultSet rows;
    static String fixture(Kind k) {
        return switch(k) {
            case ACTION -> "{\"authorizers\":[[\"party\"]],\"snapshots\":[],\"meta\":{}}";
            case EXECUTABLE -> "{\"executionCore\":{},\"snapshots\":[],\"outcome\":null}";
            case RESOLVER -> "{\"keyId\":\"key\",\"mechanisms\":[\"m\"],\"meta\":{}}";
            case VOTE_RECORD -> "{\"resolution\":"+VoteTopicResourceTest.resolution()+",\"votes\":[],\"snapshots\":[],\"action\":null,\"consumers\":[],\"meta\":{}}";
            case VOTING_RIGHTS -> "{\"keyId\":\"key\",\"rights\":[],\"validUntil\":null,\"meta\":{}}";
            case VOTE_TOPIC -> "{\"resolution\":"+VoteTopicResourceTest.resolution()+",\"proposer\":\"owner\",\"subject\":{\"url\":\"u\",\"body\":\"b\"},\"action\":null,\"voteBefore\":null,\"effectiveAt\":null,\"meta\":{}}";
            case BID_PROPOSAL -> "{\"authorities\":[],\"resolutionInfo\":null,\"terms\":{},\"availableActions\":[],\"meta\":{}}";
            case BID -> "{\"resolutionInfo\":{},\"terms\":{},\"schedule\":{},\"fundings\":[],\"availableActions\":[],\"meta\":{}}";
            case SINGLE_LOT_QUOTES -> "{\"quotes\":[]}";
            case SETTLEMENT -> "{\"executionCore\":{},\"resolutionInfo\":{},\"executors\":null,\"batches\":[],\"fundings\":[],\"meta\":{}}";
            case TRADE_PROPOSAL -> "{\"resolutionInfo\":{},\"terms\":{},\"schedule\":{},\"acceptBefore\":\"2026-12-01T00:00:00Z\",\"availableActions\":[],\"meta\":{}}";
        };
    }
    @BeforeEach void setup() throws Exception {
        client = new PqsInterfaces(); client.mapper = new ObjectMapper(); client.dataSource = mock(DataSource.class);
        connection = mock(Connection.class); statement = mock(PreparedStatement.class); rows = mock(ResultSet.class);
        when(client.dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows);
    }
    @Test void allSelectorsAndEveryViewField() throws Exception {
        String[] expected = {"cap-governance-action-v1:Cap.Governance.ActionV1:Action","cap-governance-executable-v1:Cap.Governance.ExecutableV1:Executable","cap-governance-resolver-v1:Cap.Governance.ResolverV1:Resolver","cap-governance-vote-record-v1:Cap.Governance.VoteRecordV1:VoteRecord","cap-governance-voting-rights-v1:Cap.Governance.VotingRightsV1:VotingRights","cap-governance-vote-topic-v1:Cap.Governance.VoteTopicV1:VoteTopic","cap-auctions-bid:Cap.Auctions.Bid:BidProposal","cap-auctions-bid:Cap.Auctions.Bid:Bid","cap-auctions-single-lot-quotes:Cap.Auctions.SingleLotQuotesBid:SingleLotQuotesBid","cap-auctions-settlement:Cap.Auctions.Settlement:Settlement","cap-auctions-trade-proposal:Cap.Auctions.TradeProposal:TradeProposal"};
        for (Kind k : Kind.values()) {
            clearInvocations(statement);
            when(rows.next()).thenReturn(true,false); when(rows.getString("contract_id")).thenReturn("id"); when(rows.getString("payload")).thenReturn(fixture(k));
            var result = client.list(k,5000000000L,"' OR true --",21,null);
            assertEquals(expected[k.ordinal()],k.selector);
            assertEquals(client.mapper.readTree(fixture(k)),client.mapper.valueToTree(result.getFirst().view()));
            verify(statement).setString(1,expected[k.ordinal()]); verify(statement).setLong(2,5000000000L);
            verify(statement).setString(3,"' OR true --"); verify(statement).setString(4,"' OR true --"); verify(statement).setInt(7,21);
        }
    }
    @Test void fullResolutionBindingAndDetail() throws Exception {
        client.list(Kind.VOTE_RECORD,42,null,21,VoteTopicResourceTest.resolution().toString());
        verify(statement).setString(5,VoteTopicResourceTest.resolution().toString());
        verify(statement).setString(6,VoteTopicResourceTest.resolution().toString());
        assertTrue(PqsInterfaces.LIST.contains("payload -> 'resolution' = ?::jsonb"));
        assertTrue(client.find(Kind.BID,42,"opaque").isEmpty()); verify(statement).setString(3,"opaque");
    }
    @Test void snapshot() throws Exception {
        when(rows.next()).thenReturn(true); when(rows.getLong(1)).thenReturn(5000000000L);
        assertEquals(5000000000L,client.latest()); client.validate(5000000000L);
        verify(connection).prepareStatement("select set_latest(latest_offset())"); verify(statement).setLong(1,5000000000L);
    }
    @Test void unavailable() throws Exception {
        when(client.dataSource.getConnection()).thenThrow(new SQLException());
        assertThrows(PqsInterfaces.Unavailable.class,client::latest);
        assertThrows(PqsInterfaces.Unavailable.class,()->client.validate(42));
        assertThrows(PqsInterfaces.Unavailable.class,()->client.list(Kind.ACTION,42,null,21,null));
    }
}
