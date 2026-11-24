package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceOpenerQueueTest {
 @TempDir Path root;final Instant now=Instant.parse("2026-11-23T00:00:00Z");
 SourceEvidenceCollector.Ready ready(long number)throws Exception {
  String sha="a".repeat(40),blob="b".repeat(40);
  var fetch=new SourceEvidenceFetch((u,e)->{String body=u.getPath().endsWith("files")?"[{\"filename\":\"proposals/p\",\"status\":\"added\",\"sha\":\""+blob+"\"}]":u.getPath().contains("trees")?"{\"truncated\":false,\"tree\":[{\"type\":\"blob\",\"path\":\"proposals/p\",\"sha\":\""+blob+"\"}]}":"{\"created_at\":\"2026-01-01T00:00:00Z\",\"head\":{\"sha\":\""+sha+"\"}}";return new SourceEvidenceFetch.Response(200,body.getBytes(),"","",Map.of());},Clock.fixed(now,ZoneOffset.UTC));
  return new SourceEvidenceCollector(fetch).readyProposal(number,now).orElseThrow();
 }
 SourceOpenerQueue.Binding binding(SourceEvidenceCollector.Ready r){return new SourceOpenerQueue.Binding(SourceEvidenceCollector.REPO,r.number(),"proposal","credential",1,"a".repeat(64),SourceObservation.hash(""),r.roundId(),r.eventAt(),r.number(),r.pinJson(),"","");}
 static class Ledger implements SourceOpenerQueue.Ledger {int opens;boolean lose,landed,invalid,active,complete;Map<String,SourceOpenerQueue.Proof> proofs=new HashMap<>();
  public Optional<SourceOpenerQueue.Proof> proof(SourceOpenerQueue.Binding b){var p=proofs.get(b.roundId());return p==null?Optional.empty():Optional.of(new SourceOpenerQueue.Proof(p.roundId(),p.bindingDigest(),complete,p.proposalCid(),p.closesAt()));}
  public boolean anyActiveRound(){return active;}
  public void validate(SourceOpenerQueue.Binding b)throws IOException{if(invalid)throw new IOException("revision/archive");}
  public void openAt(SourceOpenerQueue.Binding b,Instant deadline)throws IOException{assertNotNull(deadline);open(b,120);}
 public void open(SourceOpenerQueue.Binding b,int seconds)throws IOException{assertEquals(120,seconds);opens++;if(landed){proofs.put(b.roundId(),new SourceOpenerQueue.Proof(b.roundId(),SourceOpenerQueue.digest(b),false));active=true;}if(lose)throw new IOException("lostack");}
 }
 @Test void uncertainSubjectDoesNotBlockIndependentSubject()throws Exception {
  var first=ready(1);var second=ready(2);var ledger=new Ledger();ledger.lose=true;
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger)){
   queue.enqueue(first,binding(first));queue.enqueue(second,binding(second));
   assertThrows(IOException.class,queue::tick);assertEquals(1,ledger.opens);
   ledger.lose=false;ledger.landed=true;
   assertEquals("opened",queue.tick());assertEquals(2,ledger.opens);
   assertEquals(SourceOpenerQueue.State.submission_uncertain,queue.snapshot().getFirst().state());
   assertEquals("proof-pending",queue.tick());assertEquals(2,ledger.opens);
  }
 }
 @Test void authoritativeDeadlineConflictCannotUpgradeWindow()throws Exception {
  var evidence=ready(1);var binding=binding(evidence);var ledger=new Ledger();ledger.lose=true;
  var now=Instant.parse("2026-11-23T00:00:00Z");
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofDays(21),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC))){
   queue.enqueue(evidence,binding);assertThrows(IOException.class,queue::tick);
  }
  ledger.proofs.put(binding.roundId(),new SourceOpenerQueue.Proof(binding.roundId(),SourceOpenerQueue.digest(binding),false,"proposal-cid",now.plusSeconds(7200).toString()));
  var logs=new java.util.ArrayList<String>();
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofHours(2),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),logs::add)){
   assertThrows(IOException.class,queue::tick);assertEquals(SourceOpenerQueue.State.submission_uncertain,queue.snapshot().getFirst().state());
  }
  assertEquals(1,ledger.opens);assertTrue(logs.isEmpty());
 }
 @Test void legacyUncertainJournalNeverReceivesReplacementDeadline()throws Exception {
  var evidence=ready(1);var binding=binding(evidence);var ledger=new Ledger();ledger.lose=true;
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger)){queue.enqueue(evidence,binding);assertThrows(IOException.class,queue::tick);}
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var path=root.resolve("source-opener-v1.json");
  var document=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(java.nio.file.Files.readAllBytes(path));
  document.set(0,json.getNodeFactory().textNode("babycdf-opener/v2"));document.remove(3);
  java.nio.file.Files.write(path,json.writeValueAsBytes(document));
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofHours(2),java.time.Clock.systemUTC())){
   queue.enqueue(evidence,binding);assertEquals("proof-pending",queue.tick());assertEquals(1,ledger.opens);
  }
  var retained=json.readTree(java.nio.file.Files.readAllBytes(path));
  assertEquals("babycdf-opener/v2",retained.get(0).asText());assertEquals(3,retained.size());
 }
 @Test void legacyPendingDeadlineRecoveredOnlyFromProof()throws Exception {
  var evidence=ready(1);var binding=binding(evidence);var ledger=new Ledger();ledger.landed=true;
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger)){queue.enqueue(evidence,binding);assertEquals("opened",queue.tick());}
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var path=root.resolve("source-opener-v1.json");
  var document=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(java.nio.file.Files.readAllBytes(path));
  document.set(0,json.getNodeFactory().textNode("babycdf-opener/v2"));document.remove(3);
  java.nio.file.Files.write(path,json.writeValueAsBytes(document));
  String actual="2026-11-23T02:00:00Z";
  ledger.proofs.put(binding.roundId(),new SourceOpenerQueue.Proof(binding.roundId(),SourceOpenerQueue.digest(binding),false,"authoritative-proposal",actual));
  var logs=new java.util.ArrayList<String>();
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofDays(21),java.time.Clock.systemUTC(),logs::add)){
   assertEquals("proven-open",queue.tick());assertEquals(1,ledger.opens);
  }
  assertTrue(logs.isEmpty());
  var restored=json.readTree(java.nio.file.Files.readAllBytes(path));
  assertEquals("babycdf-opener/v3",restored.get(0).asText());assertEquals(actual,restored.get(3).get(binding.roundId()).asText());
 }
 @Test void legacyQueuedRowReceivesOnlyItsFirstDeadline()throws Exception {
  var evidence=ready(1);var binding=binding(evidence);var ledger=new Ledger();ledger.landed=true;
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger)){queue.enqueue(evidence,binding);}
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var path=root.resolve("source-opener-v1.json");
  var document=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(java.nio.file.Files.readAllBytes(path));
  document.set(0,json.getNodeFactory().textNode("babycdf-opener/v2"));document.remove(3);
  java.nio.file.Files.write(path,json.writeValueAsBytes(document));
  var now=Instant.parse("2026-11-23T00:00:00.123456789Z");
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofHours(2),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC))){
   queue.enqueue(evidence,binding);assertEquals("opened",queue.tick());assertEquals(1,ledger.opens);
  }
  assertEquals(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(7200).toString(),json.readTree(java.nio.file.Files.readAllBytes(path)).get(3).get(binding.roundId()).asText());
 }
 @Test void danglingDeadlineIsRejected()throws Exception {
  var evidence=ready(1);var ledger=new Ledger();
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger)){queue.enqueue(evidence,binding(evidence));}
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var path=root.resolve("source-opener-v1.json");
  var document=json.readTree(java.nio.file.Files.readAllBytes(path));
  ((com.fasterxml.jackson.databind.node.ObjectNode)document.get(3)).put("unknown-round","2026-12-14T00:00:00Z");
  java.nio.file.Files.write(path,json.writeValueAsBytes(document));
  assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),ledger));assertEquals(0,ledger.opens);
 }
 @Test void deadlinePersistsAcrossUncertaintyAndConfigurationChanges()throws Exception {
  var evidence=ready(1);var binding=binding(evidence);var ledger=new Ledger();ledger.lose=true;
  var now=Instant.parse("2026-11-23T00:00:00.123456789Z");
  var journal=root.toRealPath().resolve("source-opener-v1.json");
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofDays(21),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC))){
   queue.enqueue(evidence,binding);assertThrows(IOException.class,queue::tick);assertEquals(1,ledger.opens);
  }
  var json=new com.fasterxml.jackson.databind.ObjectMapper();
  var original=json.readTree(java.nio.file.Files.readAllBytes(journal));
  String deadline=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS).plus(java.time.Duration.ofDays(21)).toString();
  assertEquals(deadline,original.get(3).get(binding.roundId()).asText());
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofHours(2),java.time.Clock.fixed(now.plusSeconds(100),java.time.ZoneOffset.UTC))){
   queue.enqueue(evidence,binding);assertEquals("proof-pending",queue.tick());assertEquals(1,ledger.opens);
  }
  assertEquals(deadline,json.readTree(java.nio.file.Files.readAllBytes(journal)).get(3).get(binding.roundId()).asText());
  ledger.proofs.put(binding.roundId(),new SourceOpenerQueue.Proof(binding.roundId(),SourceOpenerQueue.digest(binding),false,"proposal-cid",deadline));
  var logs=new java.util.ArrayList<String>();
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofDays(7),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),logs::add)){
   assertEquals("proven-open",queue.tick());assertEquals("proven-open",queue.tick());
  }
  assertEquals(1,logs.size());
  try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger,java.time.Duration.ofHours(2),java.time.Clock.fixed(now.plusSeconds(86400*30L),java.time.ZoneOffset.UTC),logs::add)){
   assertEquals("proven-open",queue.tick());assertEquals(1,ledger.opens);
  }
  assertEquals(1,logs.size());
  assertEquals(deadline,json.readTree(java.nio.file.Files.readAllBytes(journal)).get(3).get(binding.roundId()).asText());
 }
 @Test void deterministicIndependentSubjectsAndRestartProof()throws Exception {var a=ready(2);var b=ready(1);var l=new Ledger();l.landed=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(a,binding(a));q.enqueue(b,binding(b));assertEquals(1,q.snapshot().getFirst().binding().number());assertEquals("opened",q.tick());assertEquals(1,l.opens);q.close();try(var restarted=new SourceOpenerQueue(root.toRealPath(),l)){restarted.enqueue(a,binding(a));restarted.enqueue(b,binding(b));assertEquals("opened",restarted.tick());assertEquals(2,l.opens);assertEquals("proven-open",restarted.tick());assertEquals(2,l.opens);}}
 @Test void uncertainNeverReplaysAcrossRestart()throws Exception{var r=ready(1);var l=new Ledger();l.lose=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertThrows(IOException.class,q::tick);assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),l).tick());assertEquals(1,l.opens);}
 @Test void committedBeforeAckAndJournalLossUseReceipt()throws Exception{var r=ready(1);var l=new Ledger();l.lose=true;l.landed=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertThrows(IOException.class,q::tick);q.close();assertEquals("proven-open",new SourceOpenerQueue(root.toRealPath(),l).tick());Path fresh=Files.createDirectory(root.resolve("lostjournal"));var recovered=new SourceOpenerQueue(fresh.toRealPath(),l);recovered.enqueue(r,binding(r));assertEquals("proven-open",recovered.tick());assertEquals(1,l.opens);}
 @Test void revisionAndMismatchedCapabilityBlock()throws Exception{var r=ready(1);var l=new Ledger();l.invalid=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertEquals("blocked",q.tick());assertEquals(0,l.opens);assertThrows(IOException.class,()->q.enqueue(ready(2),binding(r)));}
 @Test void blockedRowsNeverUpgradeAndPersistNoRawBody()throws Exception{var r=ready(1);var l=new Ledger();var q=new SourceOpenerQueue(root.toRealPath(),l);q.retainBlocked(binding(r),"unresolved");q.enqueue(r,binding(r));assertEquals("idle",q.tick());assertEquals(0,l.opens);String saved=Files.readString(root.resolve("source-opener-v1.json"));assertFalse(saved.contains("raw body"));assertFalse(saved.contains("Bearer"));}
 @Test void completedReceiptResetBlocksAndQueuedDeserializationCannotExecute()throws Exception{var r=ready(1);var l=new Ledger();var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),l).tick());l.landed=true;l.complete=true;q.tick();l.proofs.clear();assertThrows(IOException.class,q::tick);assertEquals(1,l.opens);}
 @Test void malformedLegacyAndConflictsRejected()throws Exception{Files.writeString(root.resolve("source-opener-v1.json"),"{\"legacy\":true}");assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),new Ledger()));}
}
