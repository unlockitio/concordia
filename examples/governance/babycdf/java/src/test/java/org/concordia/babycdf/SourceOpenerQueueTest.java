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
  public Optional<SourceOpenerQueue.Proof> proof(SourceOpenerQueue.Binding b){var p=proofs.get(b.roundId());return p==null?Optional.empty():Optional.of(new SourceOpenerQueue.Proof(p.roundId(),p.bindingDigest(),complete));}
  public boolean anyActiveRound(){return active;}
  public void validate(SourceOpenerQueue.Binding b)throws IOException{if(invalid)throw new IOException("revision/archive");}
  public void open(SourceOpenerQueue.Binding b,int seconds)throws IOException{assertEquals(120,seconds);opens++;if(landed){proofs.put(b.roundId(),new SourceOpenerQueue.Proof(b.roundId(),SourceOpenerQueue.digest(b),false));active=true;}if(lose)throw new IOException("lostack");}
 }
 @Test void deterministicOneActiveAndRestartProof()throws Exception {var a=ready(2);var b=ready(1);var l=new Ledger();l.landed=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(a,binding(a));q.enqueue(b,binding(b));assertEquals(1,q.snapshot().getFirst().binding().number());assertEquals("opened",q.tick());assertEquals(1,l.opens);q.close();new SourceOpenerQueue(root.toRealPath(),l).tick();assertEquals(1,l.opens);}
 @Test void uncertainNeverReplaysAcrossRestart()throws Exception{var r=ready(1);var l=new Ledger();l.lose=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertThrows(IOException.class,q::tick);assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),l).tick());assertEquals(1,l.opens);}
 @Test void committedBeforeAckAndJournalLossUseReceipt()throws Exception{var r=ready(1);var l=new Ledger();l.lose=true;l.landed=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertThrows(IOException.class,q::tick);q.close();assertEquals("proven-open",new SourceOpenerQueue(root.toRealPath(),l).tick());Path fresh=Files.createDirectory(root.resolve("lostjournal"));var recovered=new SourceOpenerQueue(fresh.toRealPath(),l);recovered.enqueue(r,binding(r));assertEquals("proven-open",recovered.tick());assertEquals(1,l.opens);}
 @Test void revisionAndMismatchedCapabilityBlock()throws Exception{var r=ready(1);var l=new Ledger();l.invalid=true;var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertEquals("blocked",q.tick());assertEquals(0,l.opens);assertThrows(IOException.class,()->q.enqueue(ready(2),binding(r)));}
 @Test void blockedRowsNeverUpgradeAndPersistNoRawBody()throws Exception{var r=ready(1);var l=new Ledger();var q=new SourceOpenerQueue(root.toRealPath(),l);q.retainBlocked(binding(r),"unresolved");q.enqueue(r,binding(r));assertEquals("idle",q.tick());assertEquals(0,l.opens);String saved=Files.readString(root.resolve("source-opener-v1.json"));assertFalse(saved.contains("raw body"));assertFalse(saved.contains("Bearer"));}
 @Test void completedReceiptResetBlocksAndQueuedDeserializationCannotExecute()throws Exception{var r=ready(1);var l=new Ledger();var q=new SourceOpenerQueue(root.toRealPath(),l);q.enqueue(r,binding(r));assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),l).tick());l.landed=true;l.complete=true;q.tick();l.proofs.clear();assertThrows(IOException.class,q::tick);assertEquals(1,l.opens);}
 @Test void malformedLegacyAndConflictsRejected()throws Exception{Files.writeString(root.resolve("source-opener-v1.json"),"{\"legacy\":true}");assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),new Ledger()));}
}
