package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class FetchDiagnosticsR2Test {
 @Test void bodyFailuresPreserveStatusAndMetadata(){
  var headers=HttpHeaders.of(Map.of("retry-after",List.of("5"),"x-ratelimit-remaining",List.of("0")),(a,b)->true);
  for(int status:List.of(403,429))for(int mode:List.of(0,1,2)){
   InputStream input=mode==0?new ByteArrayInputStream(new byte[2*1024*1024+1]):mode==1?new InputStream(){boolean closed;public synchronized int read()throws IOException{while(!closed)try{wait();}catch(InterruptedException e){throw new IOException();}throw new IOException();}public synchronized void close(){closed=true;notifyAll();}}:new InputStream(){public int read()throws IOException{throw new IOException("secret");}};
   var failure=Advisory.GitHub.errorResponse(status,headers,input,Duration.ofMillis(20));String message=failure.getMessage();assertTrue(message.contains("http_"+status));assertTrue(message.contains("retry-after=5"));assertTrue(message.contains("body_read_failed"));assertFalse(message.contains("secret"));
  }
 }
 @Test void bothAgentsPropagateSearchState()throws Exception{
  var fixture=new Advisory.Fixture(1,List.of(new Advisory.Clause("c","a","support","b","negative")));var rule=new Reviewer.EffectiveRule("cid","architecture-review",1,fixture.clauses());
  for(String mode:List.of("failed","returned_0","returned_1")){
   var delegate=new AdvisoryTest().api();Advisory.Api api=path->{if(path.contains("is%3Aissue")){if(mode.equals("failed"))throw new GitHubFetchFailure(GitHubFetchFailure.Code.http_403);if(mode.equals("returned_0"))return Advisory.JSON.createObjectNode().set("items",Advisory.JSON.createArrayNode());}return delegate.get(path);};
   assertEquals(mode,Advisory.pollOnce(api,fixture,new HashSet<>()).path("milestone_search_status").asText());assertEquals(mode,Reviewer.review(()->List.of(rule),api).path("milestone_search_status").asText());
  }
 }
 @Test void hostileMetadataAndInterruption()throws Exception{
  var h=HttpHeaders.of(Map.of("retry-after",List.of("secret","5"),"x-ratelimit-reset",List.of("9".repeat(1000)),"x-ratelimit-remaining",List.of("-1")),(a,b)->true);String text=GitHubFetchFailure.http(403,h,"not-json secret".getBytes()).getMessage();assertFalse(text.contains("secret"));assertFalse(text.contains("999999"));
  var delegate=new AdvisoryTest().api();var calls=new java.util.concurrent.atomic.AtomicInteger();Advisory.Api api=path->{calls.incrementAndGet();if(path.contains("is%3Aissue"))throw new GitHubFetchFailure(GitHubFetchFailure.Code.interrupted);return delegate.get(path);};assertThrows(GitHubFetchFailure.class,()->Advisory.collect(api,false));int count=calls.get();assertTrue(count>0);
 }
}
