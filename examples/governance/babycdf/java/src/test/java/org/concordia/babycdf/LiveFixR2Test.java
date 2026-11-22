package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class LiveFixR2Test {
 @Test void failedExplicitSubjectsRemainVisible()throws Exception{
  var delegate=new AdvisoryTest().api();var calls=new AtomicInteger();Advisory.Api api=path->{if(path.endsWith("/issues/541")){calls.incrementAndGet();throw new IOException("secret exception detail");}return delegate.get(path);};
  for(int i=0;i<2;i++){var result=Advisory.collect(api,false,List.of(541,540));assertTrue(result.path("errors").toString().contains("milestone issue #541 unavailable (fetch_failed:transport)"));assertFalse(result.toString().contains("secret exception detail"));assertEquals(2,result.path("inputs").size());}assertEquals(2,calls.get());
  Advisory.Api missing=path->{if(path.endsWith("/issues/540"))throw new IOException("private");return delegate.get(path);};assertTrue(Advisory.collect(missing,false).path("errors").toString().contains("milestone issue #540 unavailable (fetch_failed:transport)"));
 }
 @Test void diagnosticSentinelsAreBoundedAndRedacted()throws Exception{
  var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","configured-sentinel"));var session=new SupplementalAssessment("Reviewer",settings,null,null,System.out);var root=Advisory.JSON.createObjectNode();var clauses=root.putArray("verdicts");clauses.add(4);var c=clauses.addObject().put("id","c1").put("verdict","FAIL");var quotes=c.putArray("quotes");quotes.add(42);for(int i=0;i<20;i++)quotes.add("configured-sentinel Bearer hidden github_pat_private token=secret\u0001\u202e"+"x".repeat(1000));var result=session.quoteDiagnostics(root.toString(),"evidence",null);var qs=result.get(0).path("quotes");assertEquals(8,qs.size());String text=result.toString();for(String secret:List.of("configured-sentinel","hidden","github_pat_private","token=secret"))assertFalse(text.contains(secret));for(var q:qs)assertTrue(q.path("text").asText().length()<=240);
 }
 @Test void actualCacheSeamBoundsRepeatedSubjects()throws Exception{
  var delegate=new AdvisoryTest().api();var requests=new HashMap<String,Integer>();var api=new Advisory.GitHub(path->{requests.merge(path,1,Integer::sum);return delegate.get(path);});
  for(int i=0;i<2;i++)Advisory.collect(api,false,List.of(540,540));
  assertEquals(1,requests.get(Advisory.PREFIX+"/issues/540"));
  var many=new ArrayList<Integer>();for(int n=540;n<550;n++)many.add(n);assertTrue(Advisory.collect(api,false,many).path("errors").toString().contains("limit_8"));
 }
 @Test void freshOnlyDiagnosticsAndClearedClient(@TempDir Path root)throws Exception{
  for(boolean disk:List.of(false,true)){
   var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();byte[] response=SupplementalAssessmentTest.response(SupplementalAssessmentTest.valid("FAIL",""));var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);Path dir=disk?root:null;var session=new SupplementalAssessment("Reviewer",settings,client,null,System.out,dir);com.fasterxml.jackson.databind.JsonNode exact=null;
   for(int i=0;i<2;i++){if(disk)session=new SupplementalAssessment("Reviewer",settings,client,null,System.out,dir);var item=SupplementalAssessmentTest.item().put("number",184+i);session.assess(item,"CAP evidence",SupplementalAssessmentTest.rule(1),"review");var stored=item.path("supplemental_assessment");if(i==0){assertTrue(stored.has("clause_diagnostics"));exact=stored.path("exact");}else{assertEquals("unavailable_cached",stored.path("diagnostics").asText());assertFalse(stored.has("clause_diagnostics"));assertEquals(exact,stored.path("exact"));}assertNull(client.consumeRuleResponseContent());}assertEquals(1,calls.get());
  }
 }
}
