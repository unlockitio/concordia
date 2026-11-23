package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.time.*;import java.util.*;import java.io.*;
import com.fasterxml.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceEvidenceR2Test {
 final Instant now=Instant.parse("2026-11-23T00:00:00Z");
 @Test void reachableBackdatedBranchNeverPinsAndExplicitProofMustMatchKey(){
  var t=new SourceEvidence.Trigger("r","repo",1,1,now,"command","a".repeat(64),"observed_command");String sha="b".repeat(40);
  assertEquals("blocked_pin",SourceEvidence.delivery(t,List.of(),Map.of(),Map.of("main",new SourceEvidence.CommitProof(sha,now.minusSeconds(1),true))).status());
  var c=new SourceEvidence.Comment(2,sha,now.minusSeconds(1),now.minusSeconds(1),false);
  assertEquals("blocked_pin",SourceEvidence.delivery(t,List.of(c),Map.of(sha,new SourceEvidence.CommitProof("c".repeat(40),now,true)),Map.of()).status());
  assertEquals("pinned",SourceEvidence.delivery(t,List.of(c),Map.of(sha,new SourceEvidence.CommitProof(sha,now,true)),Map.of()).status());
 }
 @Test void fenceTypesLengthsAndContainers(){
  for(String text:List.of("````\n```\n/vote\n````","~~~\n```\n/vote\n~~~","> > /vote","- /vote","1. /vote","    /vote"))assertFalse(SourceEvidence.voteCommand(text),text);
  assertTrue(SourceEvidence.voteCommand("```\nquoted\n```\n/vote"));
 }
 @Test void strictAmendmentCapabilityRejectsArbitraryWrongSourceAndPin()throws Exception {
  var p=new SourceEvidence.Proposal(2,"a".repeat(40),List.of(new SourceEvidence.FilePin("proposals/p","modified","a".repeat(40),"")),true,"ready");var o=new SourceEvidence.Proposal(1,"b".repeat(40),List.of(new SourceEvidence.FilePin("proposals/o","added","b".repeat(40),"")),true,"ready");var map=Map.of(1L,o,2L,p);
  String good="{\"scheme\":\"babycdf-amendments/v1\",\"amendments\":[{\"repo\":\"canton-foundation/canton-dev-fund\",\"evidenceId\":123,\"original\":{\"number\":1,\"pin\":\""+VerifiedAmendment.pin(o)+"\"},\"amending\":{\"number\":2,\"pin\":\""+VerifiedAmendment.pin(p)+"\"},\"declaration\":\"amends:1\"}]}";
  assertEquals(1,VerifiedAmendment.parse(good.getBytes(),map,Set.of(123L)).size());
  assertThrows(IOException.class,()->VerifiedAmendment.parse("arbitrary bytes".getBytes(),map,Set.of(123L)));
  assertThrows(IOException.class,()->VerifiedAmendment.parse(good.getBytes(),map,Set.of(124L)));
  assertThrows(IOException.class,()->VerifiedAmendment.parse(good.replace(VerifiedAmendment.pin(p),"0".repeat(64)).getBytes(),map,Set.of(123L)));
 }
 @Test void concurrentHeadRereadIsUncachedAndBlocks()throws Exception {
  var calls=new java.util.concurrent.atomic.AtomicInteger();var fetch=new SourceEvidenceFetch((uri,etag)->{
   String body;if(uri.getPath().contains("/pulls/1")&&!uri.getPath().endsWith("files")){int n=calls.incrementAndGet();assertEquals("",etag);body="{\"head\":{\"sha\":\""+(n==1?"a":"b").repeat(40)+"\"},\"created_at\":\"2026-01-01T00:00:00Z\"}";}
   else if(uri.getPath().endsWith("files"))body="[]";else body="{\"truncated\":false,\"tree\":[]}";
   return new SourceEvidenceFetch.Response(200,body.getBytes(),"tag","",Map.of());},Clock.fixed(now,ZoneOffset.UTC));
  assertEquals("partial_files",new SourceEvidenceCollector(fetch).proposal(1,now).entries().getFirst().payload().get("pin_status"));assertEquals(2,calls.get());
 }
 @Test void editedCommandNeverDefinitiveAndChangedNextReplacesCache()throws Exception {
  var mapper=new ObjectMapper();var root=java.nio.file.Path.of("../demo/fixtures/concordia");
  var issue=mapper.readTree(java.nio.file.Files.readString(root.resolve("issue-541.json"))).path("data");
  var comments=mapper.readTree(java.nio.file.Files.readString(root.resolve("issue-541-comments.json"))).path("data").deepCopy();
  for(var row:comments)if(row.path("body").asText().equals("/vote"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("updated_at","2026-11-23T00:00:00Z");
  var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((u,e)->{throw new AssertionError();},Clock.fixed(now,ZoneOffset.UTC)));
  var batch=collector.milestone(541,now,new SourceEvidenceCollector.Capture(issue,now,true,"complete"),new SourceEvidenceCollector.Capture(comments,now,true,"complete"),new SourceEvidenceCollector.Capture(mapper.createArrayNode(),now,true,"complete"),Map.of(),Map.of());
  assertEquals("unresolved",((List<?>)batch.entries().getFirst().payload().get("result")).getFirst());assertEquals("blocked_pin",batch.entries().getFirst().payload().get("pin_status"));
  var count=new java.util.concurrent.atomic.AtomicInteger();String uri="https://api.github.com/repos/canton-foundation/canton-dev-fund/issues/1";
  var fetch=new SourceEvidenceFetch((u,e)->new SourceEvidenceFetch.Response(200,"[]".getBytes(),"v"+count.incrementAndGet(),u.toString().contains("page")?"":uri+"?page="+count.get(),Map.of()),Clock.fixed(now,ZoneOffset.UTC));
  assertEquals(uri+"?page=1",fetch.pages(uri,1,1,Duration.ofSeconds(2)).resume());assertEquals(uri+"?page=2",fetch.pages(uri,1,1,Duration.ofSeconds(2)).resume());
 }
 @Test void fixtureSanitizerChecksAllFiles()throws Exception {
  var process=new ProcessBuilder("python3","../demo/fixtures/concordia/check-fixtures.py").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertEquals(0,process.waitFor());
 }
}
