package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class LlmPriorityTest {
 @Test void boundedConfiguration(){assertEquals(4,SupplementalAssessment.assessmentLimit(Map.of()));assertEquals(6,SupplementalAssessment.assessmentLimit(Map.of("BABYCDF_LLM_MAX_ASSESSMENTS","6")));for(String bad:List.of("0","33","-1","abc"," 6","1.5"))assertThrows(IllegalArgumentException.class,()->SupplementalAssessment.assessmentLimit(Map.of("BABYCDF_LLM_MAX_ASSESSMENTS",bad)));}
 @Test void priorityUsesBudgetWithoutReorderingOutput()throws Exception{
  var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();byte[] response=SupplementalAssessmentTest.response(SupplementalAssessmentTest.valid("INCONCLUSIVE",""));var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);var session=new SupplementalAssessment("Reviewer",settings,client,null,System.out);var items=Advisory.JSON.createArrayNode();
  for(int n=538;n<=545;n++)items.addObject().put("kind","milestone_issue").put("number",n).put("pinned",false).put("explicit_milestone_subject",n==540).put("_supplemental_text","CAP evidence issue "+n);
  items.addObject().put("kind","proposal_pr").put("number",184).put("pinned",true).put("_supplemental_text","CAP evidence proposal 184");var findings=Advisory.JSON.createArrayNode().addObject().put("status","insufficient_evidence");for(var item:items)((com.fasterxml.jackson.databind.node.ObjectNode)item).set("findings",findings.deepCopy());
  session.assessCycle(items,SupplementalAssessmentTest.rule(1),"review","ledger");assertEquals(4,calls.get());assertEquals(538,items.get(0).path("number").asInt());assertEquals(184,items.get(8).path("number").asInt());
  for(var item:items){int n=item.path("number").asInt();String reason=item.path("supplemental_assessment").path("exact").path("reason").asText("");assertEquals(n==184||n==540||n==538||n==539?"":"run_budget_exhausted",reason);assertEquals("insufficient_evidence",item.path("findings").path("status").asText());assertFalse(item.has("_supplemental_text"));}
 }
}
