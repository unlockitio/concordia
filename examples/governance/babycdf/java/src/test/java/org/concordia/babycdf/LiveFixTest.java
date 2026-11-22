package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class LiveFixTest {
 @Test void nonLiteralFailHasVisibleDiagnostic()throws Exception{
  var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var logs=new ByteArrayOutputStream();byte[] response=SupplementalAssessmentTest.response("{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\"FAIL\",\"rationale\":\"model text\",\"quotes\":[\"non literal quotation invented\"]}]}");
  var client=new LlmClient(settings,r->SupplementalAssessmentTest.reply(200,response),System::nanoTime,false);var item=SupplementalAssessmentTest.item();
  new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs)).assess(item,"CAP evidence",SupplementalAssessmentTest.rule(1),"review");
  assertTrue(logs.toString().contains("c1=INCONCLUSIVE(unverified_quote)"));var diagnostic=item.path("supplemental_assessment").path("clause_diagnostics").get(0);assertEquals("FAIL",diagnostic.path("model_verdict").asText());assertEquals(1,diagnostic.path("model_quote_count").asInt());assertEquals("not_in_evidence",diagnostic.path("quotes").get(0).path("verification").asText());
 }
 @Test void explicitMilestoneDoesNotNeedTitleSearchAndDeduplicates()throws Exception{
  var delegate=new AdvisoryTest().api();var calls=new AtomicInteger();Advisory.Api api=path->{if(path.contains("is%3Aissue"))return Advisory.JSON.createObjectNode().set("items",Advisory.JSON.createArrayNode());if(path.endsWith("/issues/540")){calls.incrementAndGet();return ((com.fasterxml.jackson.databind.node.ObjectNode)delegate.get(path)).put("title","Governance expansion");}return delegate.get(path);};
  var collected=Advisory.collect(api,false,List.of(540,540));assertEquals(1,calls.get());assertEquals(1,collected.path("inputs").findValuesAsText("kind").stream().filter("milestone_issue"::equals).count());
  assertEquals(List.of(777),Advisory.milestoneNumbers(List.of(new Reviewer.OpenAgenda("cid","round","https://github.com/"+Advisory.REPO+"/issues/777","",""))));
  var fixture=new Advisory.Fixture(1,List.of(new Advisory.Clause("c","a","support","b","negative")));
  var ledger=new Reviewer.LedgerRuleGateway(){public List<Reviewer.EffectiveRule> activeRules(){return List.of(new Reviewer.EffectiveRule("rule","architecture-review",1,fixture.clauses()));}public List<Reviewer.OpenAgenda> openAgendas(){return List.of(new Reviewer.OpenAgenda("agenda","round","https://github.com/"+Advisory.REPO+"/issues/540","",""));}};
  var result=Advisory.pollWithLedger(api,fixture,new HashSet<>(),null,ledger);assertEquals(2,result.path("reviews").size());assertEquals(2,calls.get());
 }
 @Test void milestoneAssessmentRunsAndStaysInconclusive()throws Exception{
  var rule=RuleDocument.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../rules/rule-v2.json")));var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();var verdicts=new ArrayList<Map<String,Object>>();for(var c:rule.clauses())verdicts.add(Map.of("id",c.id(),"verdict","INCONCLUSIVE","rationale","incomplete_evidence","quotes",List.of()));byte[] response=SupplementalAssessmentTest.response(Advisory.JSON.writeValueAsString(Map.of("verdicts",verdicts)));
  var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);var item=Advisory.JSON.createObjectNode().put("kind","milestone_issue").put("number",540).put("pinned",false);item.set("evidence_bundle",Advisory.JSON.valueToTree(EvidenceBundle.issue("url","Governance","milestone implementation")));
  new SupplementalAssessment("Reviewer",settings,client,null,System.out).assess(item,"milestone implementation",new Advisory.Fixture(2,rule.clauses()),"review");assertEquals(1,calls.get());assertEquals("INCONCLUSIVE",item.path("supplemental_assessment").path("exact").path("overall").asText());assertEquals("incomplete_evidence",item.path("supplemental_assessment").path("exact").path("verdicts").get(2).path("rationale").asText());
 }
}
