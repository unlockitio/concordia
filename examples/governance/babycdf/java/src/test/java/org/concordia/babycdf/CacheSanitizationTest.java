package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class CacheSanitizationTest {
 @Test void longRationaleHitsAndSecretsAreRedacted(@TempDir Path root)throws Exception{
  for(boolean secrets:List.of(false,true)){
   var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();var logs=new ByteArrayOutputStream();
   String evidence=secrets?"CAP evidence Bearer sentinel token=private github_pat_hidden":"CAP evidence";
   String rationale=secrets?"Bearer sentinel token=private github_pat_hidden":"x".repeat(500);
   byte[] response=SupplementalAssessmentTest.response(Advisory.JSON.writeValueAsString(Map.of("verdicts",List.of(Map.of("id","c1","verdict","PASS","rationale",rationale,"quotes",List.of(evidence))))));
   var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
   Path dir=root.resolve(Boolean.toString(secrets));
   for(int i=0;i<2;i++){
    var item=SupplementalAssessmentTest.item();
    new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs),dir).assess(item,evidence,SupplementalAssessmentTest.rule(1),"review");
    String displayed=item.path("supplemental_assessment").toString();
    assertFalse(displayed.contains("sentinel"));assertFalse(displayed.contains("token=private"));assertFalse(displayed.contains("github_pat_hidden"));
    if(secrets)assertTrue(item.path("supplemental_assessment").path("exact").path("verdicts").get(0).path("quotes").get(0).asText().contains("[REDACTED]"));
   }
   assertEquals(1,calls.get());assertTrue(logs.toString().contains("cached=true"));
   assertFalse(logs.toString().contains("sentinel"));assertFalse(logs.toString().contains("token=private"));assertFalse(logs.toString().contains("github_pat_hidden"));
   try(var files=Files.list(dir)){for(Path file:files.toList()){String data=Files.readString(file);assertFalse(data.contains("sentinel"));assertFalse(data.contains("token=private"));assertFalse(data.contains("github_pat_hidden"));}}
  }
 }
}
