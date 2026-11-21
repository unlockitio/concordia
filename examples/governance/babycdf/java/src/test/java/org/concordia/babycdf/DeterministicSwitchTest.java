package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class DeterministicSwitchTest {
 @Test void findingsDoNotDependOnSwitch()throws Exception{
  for(String kind:List.of("PRESENCE_REQUIRED","CONTRADICTION_OR_SUPPORT"))for(String indicator:List.of("CAP","not present")){
   var clause=new Advisory.Clause("c1","a","CAP","b","negative","criterion",List.of(),List.of(),kind,kind.equals("PRESENCE_REQUIRED")?List.of(indicator):List.of());
   var fixture=new Advisory.Fixture(1,List.of(clause));
   var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
   var client=new LlmClient(settings,r->SupplementalAssessmentTest.reply(500,new byte[0]),System::nanoTime,false);
   var enabled=new SupplementalAssessment("Advisory",settings,client,null,new PrintStream(new ByteArrayOutputStream()));
   var disabled=new SupplementalAssessment("Advisory",LlmSettings.fromEnvironment(Map.of()),null,"disabled",new PrintStream(new ByteArrayOutputStream()));
   var test=new AdvisoryTest();
   var a=Advisory.pollOnce(test.api(),fixture,new HashSet<>(),enabled);
   var b=Advisory.pollOnce(test.api(),fixture,new HashSet<>(),disabled);
   assertEquals(a.path("reviews").size(),b.path("reviews").size());
   for(int i=0;i<a.path("reviews").size();i++)assertEquals(a.path("reviews").get(i).path("findings"),b.path("reviews").get(i).path("findings"));
  }
 }
 @Test void absenceRequiresEligibility(){
  var c=new Advisory.Clause("c","a","support","b","negative","criterion",List.of(),List.of(),"PRESENCE_REQUIRED",List.of("audit"));
  String text="unrelated evidence";
  var bundle=new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f",EvidenceBundle.blob(text),text.length(),text)),null,null,true);
  assertEquals("fail",ClauseCheck.evaluate(c,text,true,bundle,true).path("status").asText());
  assertEquals("absent",ClauseCheck.evaluate(c,text,true,bundle,true).path("detail").asText());
  assertEquals("insufficient_evidence",ClauseCheck.evaluate(c,text,true,bundle,false).path("status").asText());
 }
}
