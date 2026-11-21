package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class PresenceCacheRevisionTest {
 @Test void allFiveResolutionsHit(@TempDir Path root)throws Exception {
  for(String mode:List.of("indicator","conflict","semantic","absent","incomplete")){
   Path dir=root.resolve(mode);var calls=new AtomicInteger();var logs=new ByteArrayOutputStream();
   var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
   String text=Set.of("indicator","conflict").contains(mode)?"security audit evidence":"unrelated evidence statement";
   String verdict=mode.equals("conflict")?"FAIL":mode.equals("absent")||mode.equals("incomplete")?"INCONCLUSIVE":"PASS";
   List<String> quotes=Set.of("absent","incomplete").contains(mode)?List.of():List.of(text);
   String content=Advisory.JSON.writeValueAsString(Map.of("verdicts",List.of(Map.of("id","c1","verdict",verdict,"rationale","model","quotes",quotes))));
   byte[] response=SupplementalAssessmentTest.response(content);
   var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
   var clause=new Advisory.Clause("c1","a","support","b","negative","criterion",List.of(),List.of(),"PRESENCE_REQUIRED",List.of("security audit"));
   var rule=new Advisory.Fixture(1,List.of(clause));
   for(int i=0;i<2;i++){
    var item=SupplementalAssessmentTest.item().put("kind","proposal_pr").put("head_commit","a".repeat(40)).put("head_consistent",true).put("supplemental_valid",true);
    item.set("evidence_bundle",Advisory.JSON.valueToTree(new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f",EvidenceBundle.blob(text),text.length(),text)),null,null,!mode.equals("incomplete"))));
    new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs),dir).assess(item,text,rule,"review");
   }
   assertEquals(1,calls.get(),mode+logs);assertTrue(logs.toString().contains("cached=true"),mode+logs);
  }
 }
 @Test void invalidResponseIsProcessLocal(@TempDir Path root)throws Exception {
  var calls=new AtomicInteger();var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));byte[] response=SupplementalAssessmentTest.response("{bad");
  var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
  var session=new SupplementalAssessment("Reviewer",settings,client,null,System.out,root);
  for(int i=0;i<2;i++)session.assess(SupplementalAssessmentTest.item(),"CAP evidence",SupplementalAssessmentTest.rule(1),"review");
  assertEquals(1,calls.get());try(var files=Files.list(root)){assertFalse(files.anyMatch(p->p.toString().endsWith(".json")));}
  new SupplementalAssessment("Reviewer",settings,client,null,System.out,root).assess(SupplementalAssessmentTest.item(),"CAP evidence",SupplementalAssessmentTest.rule(1),"review");assertEquals(2,calls.get());
 }
}
