// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceEvidenceAcceptanceTest {
 static final Instant NOW=Instant.parse("2026-11-23T12:00:00Z");
 final ObjectMapper json=new ObjectMapper();
 SourceEvidenceCollector collector(){return new SourceEvidenceCollector(new SourceEvidenceFetch((u,e)->{throw new AssertionError("offline");},Clock.fixed(NOW,ZoneOffset.UTC)));}
 SourceEvidenceCollector.Capture capture(String file)throws Exception {var n=json.readTree(Files.readString(Path.of("../demo/fixtures/concordia",file+".json")));return new SourceEvidenceCollector.Capture(n.path("data"),NOW,true,"complete");}
 @Test void allFiveProposalFixturesFullHeadTreeAndIndependentRounds()throws Exception {
  var ids=new HashSet<String>();for(long n:new long[]{184,473,474,475,1028}) {
   var batch=collector().proposal(n,NOW,capture("pr-"+n),capture("pr-"+n+"-files"),capture("pr-"+n+"-head-tree"));
   assertEquals(1,batch.entries().size());assertTrue(ids.add(batch.entries().getFirst().roundId()));
   assertEquals("unresolved",batch.entries().getFirst().payload().get("vote_result"));assertEquals("unproven",batch.entries().getFirst().payload().get("amendment"));
  }
 }
 @Test void captured540TwoCorrelatedResultsAnd541ExplicitOpen()throws Exception {
  var batch=collector().milestone(540,NOW,capture("issue-540"),capture("issue-540-comments"),capture("issue-540-events"),Map.of(),Map.of());
  assertEquals(2,batch.entries().size());
  assertEquals("failed",((List<?>)batch.entries().get(0).payload().get("result")).getFirst());
  assertEquals("passed",((List<?>)batch.entries().get(1).payload().get("result")).getFirst());
  assertTrue(batch.entries().stream().allMatch(e->e.payload().get("pin_status").equals("blocked_pin")));
  var open=collector().milestone(541,NOW,capture("issue-541"),capture("issue-541-comments"),capture("issue-541-events"),Map.of(),Map.of());
  assertEquals("open",((List<?>)open.entries().getFirst().payload().get("result")).getFirst());
 }
 @Test void spoofedBotForeignSourceEditedConflictAndShuffleFailClosed()throws Exception {
  var issue=capture("issue-540");var comments=capture("issue-540-comments");var events=capture("issue-540-events");
  ArrayNode shuffled=json.createArrayNode();var nodes=new ArrayList<JsonNode>();comments.data().forEach(nodes::add);Collections.reverse(nodes);nodes.forEach(shuffled::add);
  assertArrayEquals(collector().milestone(540,NOW,issue,comments,events,Map.of(),Map.of()).manifest(),collector().milestone(540,NOW,issue,new SourceEvidenceCollector.Capture(shuffled,NOW,true,"complete"),events,Map.of(),Map.of()).manifest());
  ArrayNode spoof=comments.data().deepCopy();for(var row:spoof)if(row.path("user").path("type").asText().equals("Bot"))((ObjectNode)row.path("user")).put("id",1);
  var unknown=collector().milestone(540,NOW,issue,new SourceEvidenceCollector.Capture(spoof,NOW,true,"complete"),events,Map.of(),Map.of());
  assertTrue(unknown.entries().stream().allMatch(e->((List<?>)e.payload().get("result")).getFirst().equals("unresolved")));
  ArrayNode conflict=comments.data().deepCopy();JsonNode close=java.util.stream.StreamSupport.stream(conflict.spliterator(),false).filter(c->c.path("body").asText().startsWith("## Vote closed")&&c.path("body").asText().contains("**passed**")).findFirst().orElseThrow().deepCopy();((ObjectNode)close).put("id",999999).put("html_url","https://github.com/canton-foundation/canton-dev-fund/issues/540#issuecomment-999999");conflict.add(close);
  var ambiguous=collector().milestone(540,NOW,issue,new SourceEvidenceCollector.Capture(conflict,NOW,true,"complete"),events,Map.of(),Map.of());
  assertEquals("unresolved",((List<?>)ambiguous.entries().getLast().payload().get("result")).getFirst());
 }
 @Test void amendmentRequiresExplicitCheckedManifestNotTitleAndCorrectionsAppend() {
  var proposal=new SourceEvidence.Proposal(2,"a".repeat(40),List.of(),true,"ready");var original=new SourceEvidence.Proposal(1,"b".repeat(40),List.of(),true,"ready");byte[] manifest="checked manifest".getBytes();
  assertTrue(SourceEvidenceCollector.amendment(new SourceEvidenceCollector.Amendment(2,1,SourceEvidence.digest(manifest),123,"amends:1"),Map.of(1L,original,2L,proposal),manifest));
  assertFalse(SourceEvidenceCollector.amendment(new SourceEvidenceCollector.Amendment(2,1,SourceEvidence.digest(manifest),123,"Amendment in title"),Map.of(1L,original,2L,proposal),manifest));
  var first=new SourceEvidence.Entry(NOW,"command","repo",540,1,"same","",Map.of("result","open"));String digest=SourceEvidenceCollector.entryDigest(first);
  var correction=new SourceEvidence.Entry(NOW.plusSeconds(1),"correction","repo",540,2,"same",digest,Map.of("result","passed"));
  assertEquals(2,SourceEvidenceCollector.appendCorrection(List.of(first),correction,digest).size());
  assertThrows(IllegalArgumentException.class,()->SourceEvidenceCollector.appendCorrection(List.of(first),correction,"0".repeat(64)));
 }
 @Test void paginationBudgetResumesCache304RetainsNextAndRejectsUnsafe()throws Exception {
  String root="https://api.github.com/repos/canton-foundation/canton-dev-fund/issues/540/comments?page=";
  var fetch=new SourceEvidenceFetch((u,e)->new SourceEvidenceFetch.Response(e.isEmpty()?200:304,"[]".getBytes(),"tag",u.toString().endsWith("1")?root+"2":"",Map.of()),Clock.fixed(NOW,ZoneOffset.UTC));
  var partial=fetch.pages(root+"1",1,2,Duration.ofSeconds(10));assertEquals("partial",partial.status());assertEquals(root+"2",partial.resume());
  assertEquals("complete",fetch.pages(partial.resume(),1,2,Duration.ofSeconds(10)).status());assertEquals(2,fetch.pages(root+"1",2,2,Duration.ofSeconds(10)).pages().size());
  assertThrows(java.io.IOException.class,()->fetch.pages("https://evil.example",1,1,Duration.ofSeconds(1)));
  var cacheless=new SourceEvidenceFetch((u,e)->new SourceEvidenceFetch.Response(304,new byte[0],"","",Map.of()),Clock.fixed(NOW,ZoneOffset.UTC));
  assertThrows(java.io.IOException.class,()->cacheless.pages(root+"1",1,1,Duration.ofSeconds(1)));
 }
}
