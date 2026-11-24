// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.postgresql.ds.PGSimpleDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.concordia.api.Interfaces.*;
import static org.junit.jupiter.api.Assertions.*;

class PqsSqlTest {
    @Test void structuralEqualitySnapshotAndSelectors() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var ds = new PGSimpleDataSource(); ds.setURL(postgres.getJdbcUrl()); ds.setUser(postgres.getUsername()); ds.setPassword(postgres.getPassword());
            var client = new PqsInterfaces(); client.dataSource=ds; client.mapper=new ObjectMapper();
            try (var c=ds.getConnection();var s=c.createStatement()) {
                s.execute("create table fixture(selector text, contract_id text, payload jsonb, created bigint, archived bigint)");
                s.execute("create function active(text,bigint) returns table(contract_id text,payload jsonb,payload_type text) language sql as 'select contract_id,payload,''interface''::text from fixture where selector=$1 and created <= $2 and (archived is null or archived > $2)'");
                s.execute("create function latest_offset() returns bigint language sql as 'select 100::bigint'");
                s.execute("create function set_latest(bigint) returns bigint language sql as 'select $1'");
                s.execute("create function validate_offset_exists(bigint) returns boolean language plpgsql as $$ begin if $1 is null or $1 < 0 or $1 > 100 then raise exception 'offset unavailable'; end if; return true; end $$");
            }
            assertEquals(100,client.latest()); client.validate(42);
            assertThrows(PqsInterfaces.Unavailable.class, () -> client.validate(-1));
            assertThrows(PqsInterfaces.Unavailable.class, () -> client.validate(101));
            for (Kind k:Kind.values()) {
                insert(ds,k,"all-"+k.name(),PqsVoteTopicsTest.fixture(k),1,null);
                assertEquals(1,client.list(k,42,null,21,null).size());
                assertTrue(client.find(k,42,"all-"+k.name()).isPresent());
            }
            String resolution=VoteTopicResourceTest.resolution().toString();
            var payload=client.mapper.readTree(PqsVoteTopicsTest.fixture(Kind.VOTE_RECORD));
            insert(ds,Kind.VOTE_RECORD,"matching",payload.toString(),1,null);
            for (String field:new String[]{"resolverCid","mechanism","id","resolver"}) {
                var altered=payload.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode)altered.get("resolution")).put(field,"different");
                insert(ds,Kind.VOTE_RECORD,"different-"+field,altered.toString(),1,null);
            }
            insert(ds,Kind.VOTE_RECORD,"future",payload.toString(),50,null);
            insert(ds,Kind.VOTE_RECORD,"archived",payload.toString(),1,30L);
            var matches=client.list(Kind.VOTE_RECORD,42,null,100,resolution);
            assertEquals(java.util.List.of("all-VOTE_RECORD","matching"),matches.stream().map(Contract::contractId).toList());
            assertEquals(java.util.List.of("matching"),client.list(Kind.VOTE_RECORD,42,"all-VOTE_RECORD",100,resolution).stream().map(Contract::contractId).toList());
            assertEquals(3,client.list(Kind.VOTE_RECORD,60,null,100,resolution).size());
            assertTrue(client.list(Kind.ACTION,42,"' OR true --",21,null).size()<=1);
        }
    }
    static void insert(PGSimpleDataSource ds,Kind k,String id,String json,long created,Long archived)throws Exception {
        try(var c=ds.getConnection();var s=c.prepareStatement("insert into fixture values(?,?,?::jsonb,?,?)")) {
            s.setString(1,k.selector);s.setString(2,id);s.setString(3,json);s.setLong(4,created);
            if(archived==null)s.setNull(5,java.sql.Types.BIGINT);else s.setLong(5,archived);
            s.executeUpdate();
        }
    }
}
