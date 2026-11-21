package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class CanonicalQuoteCacheTest {
 @Test void memoryAndDiskCanonicalQuotes(@TempDir Path root)throws Exception{
  for(boolean disk:List.of(false,true))for(boolean unsafe:List.of(false,true)){
   String quote="verified\t evidence\n  with\u00a0unicode\u2003spaces"+(unsafe?" token=private":" safe support");String normalized=RuleVerdict.normalize(quote);
   var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();var logs=new ByteArrayOutputStream();byte[] response=SupplementalAssessmentTest.response(Advisory.JSON.writeValueAsString(Map.of("verdicts",List.of(Map.of("id","c1","verdict","FAIL","rationale","verified","quotes",List.of(quote))))));
   var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);Path dir=disk?root.resolve("d"+unsafe):null;
   var session=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs),dir);com.fasterxml.jackson.databind.JsonNode first=null;
   for(int i=0;i<2;i++){
    var item=SupplementalAssessmentTest.item().put("number",184+i);item.set("evidence_bundle",Advisory.JSON.valueToTree(new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("token=private.md",EvidenceBundle.blob(quote),quote.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,quote)),null,null,true)));
    if(disk)session=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs),dir);
    session.assess(item,quote,SupplementalAssessmentTest.rule(1),"review");var exact=item.path("supplemental_assessment").path("exact");if(first==null)first=exact;else assertEquals(first,exact);
   }
   assertEquals(1,calls.get());assertTrue(logs.toString().contains("cached=true"));
   if(disk)try(var files=Files.list(dir)){for(var file:files.toList()){String data=Files.readString(file);assertFalse(data.contains("token=private"));assertFalse(data.contains(Advisory.sha256(normalized)));assertFalse(data.contains("sha256"));}}
  }
 }
 @Test void forgedBoundsAndStaleIdentityRejected()throws Exception{
  var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var session=new SupplementalAssessment("Reviewer",settings,null,null,System.out);String evidence="verified evidence support";var raw=new RuleVerdict.Exact(RuleVerdict.Verdict.FAIL,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.FAIL,"verified",List.of())),null);var output=new RuleVerdict.Output(new RuleVerdict.Exact(RuleVerdict.Verdict.FAIL,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.FAIL,"verified",List.of(evidence))),null),"");String context=Advisory.JSON.writeValueAsString(SupplementalAssessmentTest.rule(1));
  for(var ref:List.of(new RuleVerdictCache.QuoteRef(1,0,8),new RuleVerdictCache.QuoteRef(-1,0,8),new RuleVerdictCache.QuoteRef(0,-1,8),new RuleVerdictCache.QuoteRef(0,0,999)))assertFalse(session.trusted(new RuleVerdictCache.Entry(output,"model",raw,Map.of("c1",List.of(ref))),context,evidence,null));
  assertNotEquals(RuleVerdictCache.key("v","m",context,evidence),RuleVerdictCache.key("v","m",context,evidence+" changed"));
 }
}
