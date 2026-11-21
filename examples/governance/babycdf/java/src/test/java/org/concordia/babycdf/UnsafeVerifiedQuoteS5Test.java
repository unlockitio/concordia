package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class UnsafeVerifiedQuoteS5Test {
 @Test void verifiedUnsafeQuotesKeepMeaningAndCache(@TempDir Path root)throws Exception{
  for(String kind:List.of("PRESENCE_REQUIRED","CONTRADICTION_OR_SUPPORT")){
   String text="verified evidence Bearer hidden token=private github_pat_sentinel";
   var c=new Advisory.Clause("c","a","support","b","negative","criterion",List.of(),List.of(),kind,kind.equals("PRESENCE_REQUIRED")?List.of("independent audit"):List.of());
   byte[] response=SupplementalAssessmentTest.response(Advisory.JSON.writeValueAsString(Map.of("verdicts",List.of(Map.of("id","c","verdict",kind.equals("PRESENCE_REQUIRED")?"PASS":"FAIL","rationale","verified","quotes",List.of(text))))));
   var calls=new AtomicInteger();var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var logs=new ByteArrayOutputStream();
   var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
   Path dir=root.resolve(kind);
   for(int i=0;i<2;i++){
    var item=SupplementalAssessmentTest.item().put("kind","proposal_pr").put("head_commit","a".repeat(40)).put("supplemental_valid",true).put("head_consistent",true);
    item.set("evidence_bundle",Advisory.JSON.valueToTree(new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("file.md",EvidenceBundle.blob(text),text.length(),text)),null,null,true)));
    new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs),dir).assess(item,text,new Advisory.Fixture(1,List.of(c)),"review");
    var exact=item.path("supplemental_assessment").path("exact");assertEquals(kind.equals("PRESENCE_REQUIRED")?"INCONCLUSIVE":"FAIL",exact.path("overall").asText());
    if(kind.equals("PRESENCE_REQUIRED"))assertEquals("semantic_support_unconfirmed",exact.path("verdicts").get(0).path("rationale").asText());
    assertTrue(exact.path("verdicts").get(0).path("quotes").get(0).asText().contains("[REDACTED]"));assertFalse(exact.toString().contains("sentinel"));
   }
   assertEquals(1,calls.get());assertTrue(logs.toString().contains("cached=true"));
   try(var files=Files.list(dir)){for(var file:files.toList()){String data=Files.readString(file);assertFalse(data.contains("hidden"));assertFalse(data.contains("private"));assertFalse(data.contains("sentinel"));}}
  }
 }
}
