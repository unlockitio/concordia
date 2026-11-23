// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceEvidenceTest {
 static final Instant NOW=Instant.parse("2026-11-23T00:00:00Z");
 @Test void anyEligibleFileAndHeadTreeMembership()throws Exception {
  var files=new ObjectMapper().readTree("[{\"filename\":\"README.md\",\"status\":\"modified\",\"sha\":\""+"a".repeat(40)+"\"},{\"filename\":\"rfps/work.md\",\"status\":\"added\",\"sha\":\""+"b".repeat(40)+"\"}]");
  var result=SourceEvidence.proposal(184,"c".repeat(40),files,Map.of("README.md","a".repeat(40),"rfps/work.md","b".repeat(40)),true);
  assertTrue(result.eligible());assertEquals("ready",result.status());assertEquals(2,result.files().size());
  assertEquals("blocked_pin",SourceEvidence.proposal(184,"c".repeat(40),files,Map.of(),true).status());
  assertEquals("partial_files",SourceEvidence.proposal(184,"c".repeat(40),files,Map.of(),false).status());
 }
 @Test void removedAndRenamedAreExplicit()throws Exception {
  var mapper=new ObjectMapper();
  var removed=mapper.readTree("[{\"filename\":\"proposals/x\",\"status\":\"removed\",\"sha\":\""+"a".repeat(40)+"\"}]");
  assertEquals("blocked_pin",SourceEvidence.proposal(1,"b".repeat(40),removed,Map.of(),true).status());
  var renamed=mapper.readTree("[{\"filename\":\"archive/x\",\"previous_filename\":\"proposals/x\",\"status\":\"renamed\",\"sha\":\""+"a".repeat(40)+"\"}]");
  assertTrue(SourceEvidence.proposal(1,"b".repeat(40),renamed,Map.of("archive/x","a".repeat(40)),true).eligible());
 }
 @Test void standaloneCommandsAndHistoricalUnknowns() {
  assertTrue(SourceEvidence.voteCommand("text\n/vote\n"));assertFalse(SourceEvidence.voteCommand("> /vote\n```\n/vote\n```"));assertFalse(SourceEvidence.voteCommand("please /vote"));
  var a=new SourceEvidence.Comment(2,"/vote",NOW.minusSeconds(20),NOW.minusSeconds(20),false);
  var b=new SourceEvidence.Comment(3,"/vote",NOW.minusSeconds(10),NOW.minusSeconds(5),false);
  var rounds=SourceEvidence.triggers("repo",540,"body",NOW,List.of(b,a),List.of(),NOW);
  assertEquals(List.of(2L,3L),rounds.stream().map(SourceEvidence.Trigger::id).toList());assertEquals("historical_issue_unknown",rounds.getFirst().status());
  assertEquals(2,rounds.stream().map(SourceEvidence.Trigger::roundId).distinct().count());
 }
 @Test void labelFallbackUnambiguousAndNoDeletedCommandReplacement() {
  var labels=List.of(new SourceEvidence.LabelEvent(1,"vote",NOW));
  assertEquals(1,SourceEvidence.triggers("repo",541,"body",NOW,List.of(),labels,NOW).size());
  assertTrue(SourceEvidence.triggers("repo",541,"body",NOW,List.of(),List.of(labels.getFirst(),labels.getFirst()),NOW).isEmpty());
  assertTrue(SourceEvidence.triggers("repo",541,"body",NOW,List.of(new SourceEvidence.Comment(4,"/vote",NOW,NOW,true)),labels,NOW).isEmpty());
 }
 @Test void deliveryNeverSubstitutesCurrentHead() {
  var trigger=new SourceEvidence.Trigger("round","repo",540,1,NOW,"command","a".repeat(64),"observed_command");
  var sha="b".repeat(40);var c=new SourceEvidence.Comment(1,"delivery "+sha,NOW.minusSeconds(1),NOW.minusSeconds(1),false);
  assertEquals("pinned",SourceEvidence.delivery(trigger,List.of(c),Map.of(sha,new SourceEvidence.CommitProof(sha,NOW,true)),Map.of()).status());
  assertEquals("blocked_pin",SourceEvidence.delivery(trigger,List.of(c),Map.of(sha,new SourceEvidence.CommitProof(sha,NOW.plusSeconds(1),true)),Map.of()).status());
  assertEquals("blocked_pin",SourceEvidence.delivery(trigger,List.of(),Map.of(),Map.of("main",new SourceEvidence.CommitProof(sha,NOW,false))).status());
 }
 @Test void resultsDoNotInferMergedVoteAndConflictsRemainUnresolved() {
  var r=new SourceEvidence.Result("passed",3,1,0,4,"75",NOW,1);
  assertEquals(r,SourceEvidence.correlate(2,List.of(new SourceEvidence.ResultEvidence(2,r))));
  assertEquals("unresolved",SourceEvidence.correlate(3,List.of(new SourceEvidence.ResultEvidence(2,r))).status());
  assertEquals("unresolved",SourceEvidence.correlate(2,List.of(new SourceEvidence.ResultEvidence(2,r),new SourceEvidence.ResultEvidence(2,new SourceEvidence.Result("failed",1,3,0,4,"25",NOW,2)))).status());
 }
 @Test void manifestStableOrderingNumericIdsAndCorrections() {
  var a=new SourceEvidence.Entry(NOW,"command","repo",540,2,"round","",Map.of("z",1,"a",2));
  var b=new SourceEvidence.Entry(NOW,"command","repo",540,10,"round2","",Map.of());
  assertArrayEquals(SourceEvidence.manifest(NOW,NOW,List.of(a,b)),SourceEvidence.manifest(NOW,NOW,List.of(b,a)));
  assertEquals(64,SourceEvidence.digest(SourceEvidence.manifest(NOW,NOW,List.of(a,b))).length());
 }
 @Test void captured540HasTwoRoundsAnd541Open()throws Exception {
  var mapper=new ObjectMapper();var root=java.nio.file.Path.of("../demo/fixtures/concordia");
  var comments=new ArrayList<SourceEvidence.Comment>();
  for(var row:mapper.readTree(java.nio.file.Files.readString(root.resolve("issue-540-comments.json"))).path("data"))
   comments.add(new SourceEvidence.Comment(row.path("id").asLong(),row.path("body").asText(),Instant.parse(row.path("created_at").asText()),Instant.parse(row.path("updated_at").asText()),false));
  assertEquals(2,comments.stream().filter(c->SourceEvidence.voteCommand(c.body())).count());
  var results=SourceEvidence.gitVoteResults(comments);
  assertTrue(results.stream().anyMatch(r->r.triggerId()==6606684361L && r.result().status().equals("passed") && r.result().favor()==3));
  assertTrue(mapper.readTree(java.nio.file.Files.readString(root.resolve("issue-541-comments.json"))).path("data").toString().contains("7048515679"));
 }
 @Test void boundedFetchCachesAndResumes()throws Exception {
  var calls=new java.util.concurrent.atomic.AtomicInteger();String first="https://api.github.com/repos/canton-foundation/canton-dev-fund/issues/540";
  var fetch=new SourceEvidenceFetch((uri,etag)->new SourceEvidenceFetch.Response(calls.incrementAndGet()==1?200:304,"{}".getBytes(),"tag","",Map.of()),Clock.fixed(NOW,ZoneOffset.UTC));
  assertEquals("complete",fetch.pages(first,2,2,Duration.ofSeconds(30)).status());assertEquals("complete",fetch.pages(first,2,2,Duration.ofSeconds(30)).status());
  var limited=new SourceEvidenceFetch((uri,etag)->new SourceEvidenceFetch.Response(429,new byte[0],"","",Map.of("Retry-After",List.of("120"))),Clock.fixed(NOW,ZoneOffset.UTC));
  assertEquals("http_429",limited.pages(first,2,2,Duration.ofSeconds(30)).status());assertEquals("backoff",limited.pages(first,2,2,Duration.ofSeconds(30)).status());
 }
}
