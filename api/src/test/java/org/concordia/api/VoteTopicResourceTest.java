// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.concordia.api.Interfaces.*;
import static org.mockito.Mockito.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class VoteTopicResourceTest {
    @InjectMock PqsInterfaces queries;
    static String path(Kind k) { return "/v1/"+k.domain+"/"+k.collection; }
    @BeforeEach void setup() { reset(queries); when(queries.latest()).thenReturn(42L); }
    @Test void allCollectionsAndDetails() {
        for (Kind k : Kind.values()) {
            when(queries.list(k,42,null,21,null)).thenReturn(List.of(new Contract("opaque:id",null)));
            when(queries.find(k,42,"opaque:id")).thenReturn(Optional.of(new Contract("opaque:id",null)));
            given().get(path(k)).then().statusCode(200).body("items[0].contractId",equalTo("opaque:id")).body("snapshot",equalTo("42"));
            given().pathParam("id","opaque:id").get(path(k)+"/{id}").then().statusCode(200);
            given().get(path(k)+"/missing").then().statusCode(404);
            when(queries.list(k,42,null,21,null)).thenReturn(List.of());
            given().get(path(k)).then().statusCode(200).body("items",hasSize(0));
        }
    }
    @Test void paginationAndContext() {
        when(queries.list(Kind.ACTION,42,null,2,null)).thenReturn(List.of(new Contract("a",null),new Contract("b",null)));
        String token = given().queryParam("limit",1).get(path(Kind.ACTION)).then().statusCode(200).extract().path("nextCursor");
        when(queries.latest()).thenReturn(99L);
        when(queries.list(Kind.ACTION,42,"a",2,null)).thenReturn(List.of(new Contract("b",null)));
        given().queryParam("limit",1).queryParam("cursor",token).get(path(Kind.ACTION)).then().statusCode(200).body("snapshot",equalTo("42"));
        given().queryParam("cursor",token).get(path(Kind.EXECUTABLE)).then().statusCode(400);
        verify(queries,times(1)).latest();
    }
    @Test void invalidPaginationAndErrors() {
        for (String n : List.of("0","101","x","-1")) given().queryParam("limit",n).get(path(Kind.ACTION)).then().statusCode(400);
        for (String c : List.of("","bad!")) given().queryParam("cursor",c).get(path(Kind.ACTION)).then().statusCode(400);
        given().get("/v1/core/interfaces").then().statusCode(404);
        when(queries.latest()).thenThrow(new PqsInterfaces.Unavailable(new java.sql.SQLException()));
        for (Kind k : Kind.values()) {
            given().get(path(k)).then().statusCode(503).body("code",equalTo("pqs_unavailable"));
            given().get(path(k)+"/id").then().statusCode(503);
        }
    }
    static VoteTopic topic(String action) { return new VoteTopic(resolution(),"owner",new Reason("url","body"),action,null,null,null); }
    static com.fasterxml.jackson.databind.JsonNode resolution() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"resolver\":{\"authorities\":[],\"id\":\"key\"},\"resolverCid\":null,\"mechanism\":\"m\",\"id\":\"r\"}"); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    void validateActionResponse(String route) {
        String spec = given().queryParam("format","json").get("/q/openapi").then().statusCode(200).extract().asString();
        var validator = com.atlassian.oai.validator.OpenApiInteractionValidator.createForInlineApiSpecification(spec).build();
        var actual = given().get(route).then().statusCode(200).extract().response();
        var response = com.atlassian.oai.validator.model.SimpleResponse.Builder.status(actual.statusCode())
                .withBody(actual.asString()).withHeader("Content-Type",actual.contentType()).build();
        var report = validator.validateResponse(route,com.atlassian.oai.validator.model.Request.Method.GET,response);
        assertFalse(report.hasErrors(), () -> report.getMessages().toString());
    }
    @Test void actionLinks() {
        for (Kind k : List.of(Kind.VOTE_TOPIC,Kind.VOTE_RECORD)) {
            Object view = k==Kind.VOTE_TOPIC ? topic("a") : new VoteRecord(resolution(),null,null,"a",List.of(),null);
            when(queries.find(k,42,"p")).thenReturn(Optional.of(new Contract("p",view)));
            when(queries.find(Kind.ACTION,42,"a")).thenReturn(Optional.of(new Contract("a",new Action(resolution(),resolution(),resolution()))));
            given().get(path(k)+"/p/action").then().statusCode(200).body("status",equalTo("available")).body("target.contractId",equalTo("a"));
            validateActionResponse(path(k)+"/p/action");
            when(queries.find(Kind.ACTION,42,"a")).thenReturn(Optional.empty());
            given().get(path(k)+"/p/action").then().statusCode(200).body("status",equalTo("target_unavailable")).body("target",nullValue());
            validateActionResponse(path(k)+"/p/action");
            given().get(path(k)+"/missing/action").then().statusCode(404);
        }
        when(queries.find(Kind.VOTE_TOPIC,42,"p")).thenReturn(Optional.of(new Contract("p",topic(null))));
        given().get(path(Kind.VOTE_TOPIC)+"/p/action").then().statusCode(200).body("status",equalTo("no_generic_reference")).body("target",nullValue());
        validateActionResponse(path(Kind.VOTE_TOPIC)+"/p/action");
        when(queries.find(Kind.VOTE_RECORD,42,"p")).thenReturn(Optional.of(new Contract("p",new VoteRecord(resolution(),null,null,null,List.of(),null))));
        validateActionResponse(path(Kind.VOTE_RECORD)+"/p/action");
        given().get(path(Kind.ACTION)+"/p/executables").then().statusCode(404);
    }
    @Test void recordsGroupingAndParentCursor() {
        when(queries.find(Kind.VOTE_TOPIC,42,"p")).thenReturn(Optional.of(new Contract("p",topic(null))));
        when(queries.list(Kind.VOTE_RECORD,42,null,2,resolution().toString())).thenReturn(List.of(new Contract("a",null),new Contract("b",null)));
        String p = path(Kind.VOTE_TOPIC)+"/p/vote-records";
        String token = given().queryParam("limit",1).get(p).then().statusCode(200).extract().path("nextCursor");
        when(queries.latest()).thenReturn(99L);
        when(queries.list(Kind.VOTE_RECORD,42,"a",2,resolution().toString())).thenReturn(List.of());
        given().queryParam("limit",1).queryParam("cursor",token).get(p).then().statusCode(200).body("snapshot",equalTo("42")).body("items",hasSize(0));
        given().queryParam("cursor",token).get(path(Kind.VOTE_TOPIC)+"/other/vote-records").then().statusCode(400);
        given().queryParam("cursor",token).get(path(Kind.VOTE_RECORD)).then().statusCode(400);
        when(queries.latest()).thenReturn(42L);
        given().get(path(Kind.VOTE_TOPIC)+"/missing/vote-records").then().statusCode(404);
    }
    @Test void prunedSnapshotAndNestedUnavailable() {
        doThrow(new PqsInterfaces.Unavailable(new java.sql.SQLException("pruned"))).when(queries).validate(42);
        given().get(path(Kind.ACTION)).then().statusCode(503);
        given().get(path(Kind.VOTE_TOPIC)+"/p/vote-records").then().statusCode(503);
        given().get(path(Kind.VOTE_TOPIC)+"/p/action").then().statusCode(503);
        given().get(path(Kind.BID)+"/p/single-lot-quotes").then().statusCode(503);
    }
    @Test void facet() {
        when(queries.find(Kind.BID,42,"p")).thenReturn(Optional.of(new Contract("p",null)));
        when(queries.find(Kind.SINGLE_LOT_QUOTES,42,"p")).thenReturn(Optional.of(new Contract("p",new SingleLotQuotes(null))));
        given().get(path(Kind.BID)+"/p/single-lot-quotes").then().statusCode(200).body("contractId",equalTo("p"));
        when(queries.find(Kind.SINGLE_LOT_QUOTES,42,"p")).thenReturn(Optional.empty());
        given().get(path(Kind.BID)+"/p/single-lot-quotes").then().statusCode(404);
        given().get(path(Kind.BID)+"/missing/single-lot-quotes").then().statusCode(404);
    }
    @Test void servedContract() {
        var json = given().queryParam("format","json").get("/q/openapi").then().statusCode(200).extract().jsonPath();
        for (Kind k : Kind.values()) {
            String p = "paths.'"+path(k)+"'.get";
            assertEquals("integer",json.getString(p+".parameters[0].schema.type"));
            assertEquals(1,json.getInt(p+".parameters[0].schema.minimum"));
            assertEquals(100,json.getInt(p+".parameters[0].schema.maximum"));
            assertEquals(20,json.getInt(p+".parameters[0].schema.default"));
            assertNotNull(json.get(p+".responses.'400'"));
            assertNotNull(json.get(p+".responses.'503'"));
            assertNotNull(json.get("paths.'"+path(k)+"/{contractId}'.get.responses.'404'"));
            assertNotNull(json.get("components.schemas."+k.name()+"View"));
        }
        for (String p : List.of(path(Kind.VOTE_TOPIC)+"/{contractId}/action",path(Kind.VOTE_RECORD)+"/{contractId}/action",path(Kind.VOTE_TOPIC)+"/{contractId}/vote-records",path(Kind.BID)+"/{contractId}/single-lot-quotes"))
            assertNotNull(json.get("paths.'"+p+"'.get.responses.'404'"));
        assertEquals("string",json.getString("components.schemas.Page.properties.snapshot.type"));
        for (Kind k : Kind.values()) {
            var properties = json.getMap("components.schemas."+k.name()+"View.properties");
            for (var component : k.viewType.getRecordComponents()) assertTrue(properties.containsKey(component.getName()));
            assertNotNull(json.get("paths.'"+path(k)+"/{contractId}'.get.responses.'503'"));
        }
    }
}
