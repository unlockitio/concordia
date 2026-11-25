package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneAdmissionFailureTest {
 @Test void issueAndCommentCancellationPropagate()throws Exception {
  for(String endpoint:List.of("issue","comments")) {
   var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((uri,etag)->{
    if(endpoint.equals("issue")||uri.getPath().endsWith("comments")){Thread.currentThread().interrupt();throw new InterruptedIOException("cancelled source observation");}
    return response(200,"{\"body\":\"current\",\"updated_at\":\"2026-11-23T00:00:00Z\"}");
   },Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"),ZoneOffset.UTC)));
   try{assertThrows(InterruptedIOException.class,()->collector.collectMilestones(540,Instant.parse("2026-11-24T00:00:00Z")));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
  }
 }
 @Test void failedIssueOrCommentsNeverIssueCapability()throws Exception {
  for(boolean issueFails:List.of(true,false)) {
   var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((uri,etag)->{
    if(issueFails||uri.getPath().endsWith("comments"))return response(404,"");
    return response(200,uri.getPath().endsWith("events")?"[]":"{\"body\":\"current\",\"updated_at\":\"2026-11-23T00:00:00Z\"}");
   },Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"),ZoneOffset.UTC)));
   assertTrue(collector.collectMilestones(540,Instant.parse("2026-11-24T00:00:00Z")).ready().isEmpty());
  }
 }
 @Test void rateLimitDoesNotHammerTransport()throws Exception {
  int[] calls={0};var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((uri,etag)->{calls[0]++;return new SourceEvidenceFetch.Response(429,new byte[0],"","",Map.of("Retry-After",List.of("60")));},Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"),ZoneOffset.UTC)));
  for(int i=0;i<2;i++)assertTrue(collector.collectMilestones(540,Instant.parse("2026-11-24T00:00:00Z")).ready().isEmpty());
  assertEquals(1,calls[0]);
 }
 static SourceEvidenceFetch.Response response(int status,String body){return new SourceEvidenceFetch.Response(status,body.getBytes(java.nio.charset.StandardCharsets.UTF_8),"","",Map.of());}
}
