package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class Fix4CoherenceTest {
    @Test void twoPollHeadChangeRefetchesFiles() throws Exception {
        var api=new EvidencePollingTest.Api() {
            String head="a".repeat(40); int lists;
            public com.fasterxml.jackson.databind.JsonNode get(String path) throws java.io.IOException {
                var result=super.get(path);
                if(path.endsWith("/pulls/184")) ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("head")).put("sha",head);
                if(path.contains("/files?")) lists++;
                return result;
            }
            public com.fasterxml.jackson.databind.JsonNode fresh(String path) throws java.io.IOException {
                if(path.endsWith("/pulls/184")) { head="b".repeat(40);text="CAP evidence support H2 distinct"; }
                return get(path);
            }
        };
        var first=Advisory.collect(api,true).path("inputs").get(0);
        assertFalse(first.path("supplemental_valid").asBoolean());assertTrue(first.path("pinned").asBoolean());
        var second=Advisory.collect(api,true).path("inputs").get(0);
        assertEquals(2,api.lists);assertEquals("b".repeat(40),second.path("evidence_bundle").path("headSha").asText());
        assertTrue(second.path("supplemental_valid").asBoolean());
        assertEquals("CAP evidence support H2 distinct",second.path("evidence_bundle").path("files").get(0).path("content").asText());
    }
    @Test void excludedExtraFileDoesNotChangeDeterministicFindings() throws Exception {
        var api=new EvidencePollingTest.Api() {
            public com.fasterxml.jackson.databind.JsonNode get(String path) throws java.io.IOException {
                var result=super.get(path);
                if(path.contains("/files?")) ((com.fasterxml.jackson.databind.node.ArrayNode)result).addObject().put("filename","binary").put("sha","f".repeat(40));
                if(path.endsWith("f".repeat(40))) return Advisory.JSON.createObjectNode().put("sha","f".repeat(40)).put("encoding","base64").put("content","AA==");
                return result;
            }
        };
        var fixture=new Advisory.Fixture(1,List.of(new Advisory.Clause("c1","a","CAP evidence","b","wrong")));
        var baseline=Advisory.pollOnce(api,fixture,new HashSet<>());
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        var session=new SupplementalAssessment("Advisory",settings,new LlmClient(settings,r->{throw new AssertionError("incomplete bundle must not call provider");},System::nanoTime,false),null,System.out);
        var enabled=Advisory.pollOnce(api,fixture,new HashSet<>(),session);
        assertEquals(baseline.path("reviews").get(0).path("findings"),enabled.path("reviews").get(0).path("findings"));
        assertTrue(enabled.path("reviews").get(0).path("pinned").asBoolean());
    }
    @Test void titleOnlyIssueIsEvaluated() throws Exception {
        var bundle=EvidenceBundle.issue("https://github.com/canton-foundation/canton-dev-fund/issues/540","Public milestone title","");
        var item=SupplementalAssessmentTest.item().put("kind","milestone_issue").put("pinned",false);item.set("evidence_bundle",Advisory.JSON.valueToTree(bundle));
        var calls=new java.util.concurrent.atomic.AtomicInteger();var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        byte[] response=SupplementalAssessmentTest.response("{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\"INCONCLUSIVE\",\"rationale\":\"unknown\",\"quotes\":[]}]}");
        var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
        new SupplementalAssessment("Reviewer",settings,client,null,System.out).assess(item,"",SupplementalAssessmentTest.rule(1),"review");assertEquals(1,calls.get());
    }
}
