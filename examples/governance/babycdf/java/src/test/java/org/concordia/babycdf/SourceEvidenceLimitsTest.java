// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class SourceEvidenceLimitsTest {
 static final Instant NOW=Instant.parse("2026-11-23T00:00:00Z");
 static final String URI="https://api.github.com/repos/canton-foundation/canton-dev-fund/issues/540";
 @Test void responseAndCacheAggregateLimits()throws Exception {
  var tooLarge=new SourceEvidenceFetch((u,e)->new SourceEvidenceFetch.Response(200,new byte[2097153],"","",Map.of()),Clock.fixed(NOW,ZoneOffset.UTC));
  assertThrows(IOException.class,()->tooLarge.pages(URI,1,1,Duration.ofSeconds(10)));
  var cache=new SourceEvidenceFetch((u,e)->new SourceEvidenceFetch.Response(200,new byte[2097152],"","",Map.of()),Clock.fixed(NOW,ZoneOffset.UTC));
  for(int i=0;i<8;i++)assertEquals("complete",cache.pages(URI+"?page="+i,1,1,Duration.ofSeconds(10)).status());
  assertThrows(IOException.class,()->cache.pages(URI+"?page=9",1,1,Duration.ofSeconds(10)));
 }
 @Test void deadlineAndUnknown403SuspendWithoutRetry()throws Exception {
  var time=new java.util.concurrent.atomic.AtomicReference<>(NOW);
  Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
  var deadline=new SourceEvidenceFetch((u,e)->{time.set(NOW.plusSeconds(20));return new SourceEvidenceFetch.Response(200,"[]".getBytes(),"","",Map.of());},clock);
  assertEquals("deadline",deadline.pages(URI,1,1,Duration.ofSeconds(10)).status());
  var calls=new java.util.concurrent.atomic.AtomicInteger();var unknown=new SourceEvidenceFetch((u,e)->{calls.incrementAndGet();return new SourceEvidenceFetch.Response(403,new byte[0],"","",Map.of());},clock);
  assertEquals("http_403",unknown.pages(URI,1,1,Duration.ofSeconds(10)).status());assertEquals("backoff",unknown.pages(URI,1,1,Duration.ofSeconds(10)).status());assertEquals(1,calls.get());
 }
 @Test void shuffledLabelsEditedDeletedAndQuotationRemainConservative() {
  assertFalse(SourceEvidence.voteCommand("    /vote"));assertFalse(SourceEvidence.voteCommand("\t/vote"));
  var edited=new SourceEvidence.Comment(1,"/vote",NOW,NOW.plusSeconds(1),false);
  var rounds=SourceEvidence.triggers("repo",1,"body",NOW,List.of(edited),List.of(),NOW.plusSeconds(2));
  assertEquals("historical_command_unknown",rounds.getFirst().status());
  assertTrue(SourceEvidence.triggers("repo",1,"body",NOW,List.of(new SourceEvidence.Comment(1,"/vote",NOW,NOW,true)),List.of(),NOW).isEmpty());
 }
 @Test void canonicalTieOrdersNumericIdsAndPayloads() {
  var two=new SourceEvidence.Entry(NOW,"command","repo",1,2,"round2","",Map.of());var ten=new SourceEvidence.Entry(NOW,"command","repo",1,10,"round10","",Map.of());
  String manifest=new String(SourceEvidence.manifest(NOW,NOW,List.of(ten,two)),java.nio.charset.StandardCharsets.UTF_8);assertTrue(manifest.indexOf("round2")<manifest.indexOf("round10"));
  var a=new SourceEvidence.Entry(NOW,"result","repo",1,1,"r","",Map.of("a",1));var b=new SourceEvidence.Entry(NOW,"result","repo",1,1,"r","",Map.of("a",2));
  assertArrayEquals(SourceEvidence.manifest(NOW,NOW,List.of(a,b)),SourceEvidence.manifest(NOW,NOW,List.of(b,a)));
 }
}
