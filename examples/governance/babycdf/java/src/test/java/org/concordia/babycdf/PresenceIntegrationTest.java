package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
class PresenceIntegrationTest {
    Advisory.Clause rule(String kind) {return new Advisory.Clause("c1","a","supporting evidence","b","negative","criterion",List.of("supporting evidence"),List.of(),kind,kind.equals("PRESENCE_REQUIRED")?List.of("supporting evidence"):List.of());}
    @Test void mockProviderMatrix() throws Exception {
        for(boolean complete:List.of(false,true)) for(String verdict:List.of("PASS","FAIL")) for(boolean verified:List.of(false,true)) {
            var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var logs=new ByteArrayOutputStream();
            String quote=verified?"supporting evidence":"invented evidence";
            String content="{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\""+verdict+"\",\"rationale\":\"candidate\",\"quotes\":[\""+quote+"\"]}]}";
            byte[] response=SupplementalAssessmentTest.response(content);
            var client=new LlmClient(settings,r->SupplementalAssessmentTest.reply(200,response),System::nanoTime,false);
            var item=SupplementalAssessmentTest.item();
            var text="supporting evidence";
            item.set("evidence_bundle",Advisory.JSON.valueToTree(new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f",EvidenceBundle.blob(text),text.length(),text)),null,null,complete)));
            var session=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs));
            session.assess(item,text,new Advisory.Fixture(1,List.of(rule("PRESENCE_REQUIRED"))),"review");
            String expected=verdict.equals("FAIL")&&verified?"INCONCLUSIVE":"PASS";
            assertEquals(expected,item.path("supplemental_assessment").path("exact").path("overall").asText());
            if(!verified) assertTrue(item.path("supplemental_assessment").path("exact").path("verdicts").get(0).path("rationale").asText().contains("code_indicator"));
            if(verified&&verdict.equals("FAIL")) assertEquals("verified_supporting_non_authoritative",item.path("supplemental_assessment").path("exact").path("verdicts").get(0).path("conflict_quote_role").asText());
        }
    }
    @Test void mixedKindsCompleteAndIncomplete() throws Exception {
        for(boolean complete:List.of(true,false)) {
            var contradiction=new Advisory.Clause("c1","a","positive evidence","b","explicit contradiction","criterion",List.of("positive evidence"),List.of("explicit contradiction"),"CONTRADICTION_OR_SUPPORT");
            var presence=new Advisory.Clause("c2","a","independent audit","b","negative","criterion",List.of("independent audit"),List.of(),"PRESENCE_REQUIRED",List.of("independent audit"));
            var rules=new Advisory.Fixture(1,List.of(contradiction,presence));
            String text="There is an explicit contradiction in this evidence.";
            var bundle=new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f",EvidenceBundle.blob(text),text.length(),text)),null,null,complete);
            String content="{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\"FAIL\",\"rationale\":\"contradicted\",\"quotes\":[\"explicit contradiction\"]},{\"id\":\"c2\",\"verdict\":\"INCONCLUSIVE\",\"rationale\":\"no support found\",\"quotes\":[]}]}";
            byte[] response=SupplementalAssessmentTest.response(content);
            var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
            var client=new LlmClient(settings,r->SupplementalAssessmentTest.reply(200,response),System::nanoTime,false);
            var item=SupplementalAssessmentTest.item().put("kind","proposal_pr").put("head_commit","a".repeat(40)).put("supplemental_valid",true).put("head_consistent",true);item.set("evidence_bundle",Advisory.JSON.valueToTree(bundle));
            new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(new ByteArrayOutputStream())).assess(item,text,rules,"review");
            var exact=item.path("supplemental_assessment").path("exact");
            assertEquals("FAIL",exact.path("overall").asText());
            assertEquals("FAIL",exact.path("verdicts").get(0).path("verdict").asText());
            assertEquals(complete?"FAIL":"INCONCLUSIVE",exact.path("verdicts").get(1).path("verdict").asText());
            assertEquals(complete?"absent":"incomplete_evidence",exact.path("verdicts").get(1).path("rationale").asText());
        }
    }
    @Test void forgedPresenceCacheCannotBypass(@TempDir Path root) throws Exception {
        var rules=List.of(rule("PRESENCE_REQUIRED"));String context=Advisory.JSON.writeValueAsString(new Advisory.Fixture(1,rules));
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var session=new SupplementalAssessment("Reviewer",settings,null,null,System.out);
        for(boolean complete:List.of(false,true)) {
            var bundle=new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f",EvidenceBundle.blob("other evidence"),14,"other evidence")),null,null,complete);
            for(var forged:List.of(new RuleVerdict.Exact(RuleVerdict.Verdict.PASS,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.PASS,"claimed",List.of())),null),
                    new RuleVerdict.Exact(RuleVerdict.Verdict.FAIL,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.FAIL,"absent",List.of())),null),
                    new RuleVerdict.Exact(RuleVerdict.Verdict.PASS,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.INCONCLUSIVE,"incomplete_evidence",List.of())),null))) {
                if(complete&&forged.overall()==RuleVerdict.Verdict.FAIL)continue;
                var entry=new RuleVerdictCache.Entry(new RuleVerdict.Output(forged,""),"model");
                assertFalse(session.trusted(entry,context,bundle.evidenceText(),bundle));
            }
        }
        assertNotEquals(RuleVerdictCache.key("v","m",context,"complete=true"),RuleVerdictCache.key("v","m",context.replace("PRESENCE_REQUIRED","CONTRADICTION_OR_SUPPORT"),"complete=true"));
        assertNotEquals(RuleVerdictCache.key("v","m",context,"complete=true"),RuleVerdictCache.key("v","m",context,"complete=false"));
    }
}
