package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.*;
import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceEvidenceR3Test {
 final Instant now=Instant.parse("2026-11-23T00:00:00Z");
 @Test void freshRateLimitsShareBackoff()throws Exception {
  for(int status:new int[]{403,429}){var calls=new java.util.concurrent.atomic.AtomicInteger();
   var f=new SourceEvidenceFetch((u,e)->{calls.incrementAndGet();return new SourceEvidenceFetch.Response(status,new byte[0],"","",Map.of("retry-after",List.of("120")));},Clock.fixed(now,ZoneOffset.UTC));
   var error=assertThrows(GitHubFetchFailure.class,()->f.fresh("https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls/1"));
   assertEquals("http_"+status,error.code.name());assertEquals("backoff",f.pages("https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls/1",1,1,Duration.ofSeconds(1)).status());assertEquals(1,calls.get());}
 }
 @Test void partialEventsCannotPinEvenExplicitSha()throws Exception {
  var mapper=new ObjectMapper();String sha="a".repeat(40),url="https://api.github.com/repos/canton-foundation/canton-dev-fund/issues/1";
  var issue=mapper.readTree("{\"body\":\"body\",\"updated_at\":\"2026-11-22T00:00:00Z\"}");
  var rows=mapper.createArrayNode();for(int i=1;i<=2;i++)rows.add(mapper.createObjectNode().put("id",i).put("issue_url",url).put("html_url","https://github.com/canton-foundation/canton-dev-fund/issues/1#issuecomment-"+i).put("body",i==1?sha:"/vote").put("created_at",i==1?"2026-11-22T01:00:00Z":"2026-11-22T02:00:00Z").put("updated_at",i==1?"2026-11-22T01:00:00Z":"2026-11-22T02:00:00Z"));
  var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((u,e)->{throw new AssertionError();},Clock.fixed(now,ZoneOffset.UTC)));
  var batch=collector.milestone(1,now,new SourceEvidenceCollector.Capture(issue,now,true,"complete"),new SourceEvidenceCollector.Capture(rows,now,true,"complete"),new SourceEvidenceCollector.Capture(mapper.createArrayNode(),now,false,"partial"),Map.of(sha,new SourceEvidence.CommitProof(sha,now.minusSeconds(86400),true)),Map.of());
  assertEquals(false,batch.entries().getFirst().payload().get("enumeration_complete"));assertEquals("blocked_pin",batch.entries().getFirst().payload().get("pin_status"));
 }
 @Test void amendmentBlockedPinsAndDuplicateKeysRejected()throws Exception {
  var file=new SourceEvidence.FilePin("proposals/p","added","a".repeat(40),"");var a=new SourceEvidence.Proposal(1,"a".repeat(40),List.of(file),true,"ready");var b=new SourceEvidence.Proposal(2,"b".repeat(40),List.of(file),true,"ready");
  String text="{\"scheme\":\"babycdf-amendments/v1\",\"amendments\":[{\"repo\":\"canton-foundation/canton-dev-fund\",\"evidenceId\":1,\"original\":{\"number\":1,\"pin\":\""+VerifiedAmendment.pin(a)+"\"},\"amending\":{\"number\":2,\"pin\":\""+VerifiedAmendment.pin(b)+"\"},\"declaration\":\"amends:1\"}]}";
  assertEquals(1,VerifiedAmendment.parse(text.getBytes(),Map.of(1L,a,2L,b),Set.of(1L)).size());
  for(boolean original:new boolean[]{true,false}) {var blocked=new SourceEvidence.Proposal(original?1:2,original?a.head():b.head(),List.of(file),true,"blocked_pin");assertThrows(IOException.class,()->VerifiedAmendment.parse(text.getBytes(),original?Map.of(1L,blocked,2L,b):Map.of(1L,a,2L,blocked),Set.of(1L)));}
  assertThrows(IOException.class,()->VerifiedAmendment.parse(text.replace("\"evidenceId\":1","\"evidenceId\":1,\"evidenceId\":1").getBytes(),Map.of(1L,a,2L,b),Set.of(1L)));
 }
 @Test void fixtureAdversarialSuite()throws Exception {var p=new ProcessBuilder("python3","../demo/fixtures/concordia/test-check-fixtures.py").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertEquals(0,p.waitFor());}
}
