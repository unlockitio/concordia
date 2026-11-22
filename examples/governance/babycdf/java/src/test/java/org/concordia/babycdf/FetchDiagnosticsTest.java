package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class FetchDiagnosticsTest {
 @Test void statusesAndAllowlistedMetadata(){
  for(int status:List.of(401,403,404,410,422,429,500,503,302)){
   var headers=HttpHeaders.of(Map.of("x-ratelimit-remaining",List.of("0"),"x-ratelimit-reset",List.of("1700000000"),"retry-after",List.of("5"),"x-github-sso",List.of("secret-token"),"authorization",List.of("Bearer secret-token")),(a,b)->true);
   var failure=GitHubFetchFailure.http(status,headers,"{\"message\":\"Resource not accessible by personal access token secret-token\"}".getBytes());String text=failure.toString();assertFalse(text.contains("secret-token"));assertFalse(text.contains("Bearer"));assertTrue(text.contains("pat_resource_not_accessible"));assertTrue(text.contains(status>=500?"http_5xx":status==302?"http_other":"http_"+status));if(status==403||status==429){assertTrue(text.contains("x-github-sso-present=true"));assertTrue(text.contains("retry-after=5"));}
  }
 }
 @Test void everyCategoryAndTransportSanitization()throws Exception{
  for(var code:GitHubFetchFailure.Code.values())assertEquals(code.name(),GitHubFetchFailure.safeSummary(new GitHubFetchFailure(code)));
  var api=new Advisory.GitHub(path->{throw new IOException("Bearer sentinel https://secret?token=x");});assertEquals("transport",assertThrows(GitHubFetchFailure.class,()->api.get("test")).getMessage());
  var timeout=new Advisory.GitHub(path->{throw new HttpTimeoutException("sentinel");});assertEquals("deadline",assertThrows(GitHubFetchFailure.class,()->timeout.get("test")).getMessage());
  var interrupted=new Advisory.GitHub(path->{throw new InterruptedException("sentinel");});assertEquals("interrupted",assertThrows(GitHubFetchFailure.class,()->interrupted.get("test")).getMessage());assertTrue(Thread.interrupted());
 }
 @Test void searchFailureDiffersFromZeroAndModeIsSafe()throws Exception{
  var delegate=new AdvisoryTest().api();Advisory.Api failed=path->{if(path.contains("is%3Aissue"))throw new GitHubFetchFailure(GitHubFetchFailure.Code.http_403);return delegate.get(path);};var failure=Advisory.collect(failed,false);assertTrue(failure.path("errors").toString().contains("milestone search failed (http_403)"));
  Advisory.Api empty=path->{if(path.contains("is%3Aissue"))return Advisory.JSON.createObjectNode().set("items",Advisory.JSON.createArrayNode());return delegate.get(path);};assertFalse(Advisory.collect(empty,false).path("errors").toString().contains("search failed"));
  var log=new ByteArrayOutputStream();new Advisory.GitHub(Map.of()).logMode("Reviewer",new PrintStream(log));assertEquals("[Reviewer] GitHub mode=anonymous\n",log.toString());
 }
}
