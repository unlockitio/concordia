package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceOpenerR2Test {
 @TempDir Path root;
 public static void main(String[] args)throws Exception {try(var q=new SourceOpenerQueue(Path.of(args[0]),new SourceOpenerQueueTest.Ledger())){System.exit(2);}catch(IOException blocked){System.exit(0);}}
 @Test void childJvmWriterLockExcluded()throws Exception {
  try(var q=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){
   var p=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),getClass().getName(),root.toRealPath().toString()).redirectErrorStream(true).start();boolean finished=p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS);if(!finished){p.destroyForcibly();p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}assertTrue(finished,"child JVM lock test timed out");String output=new String(p.getInputStream().readAllBytes());assertEquals(0,p.exitValue(),output);
  }
 }
 @Test void exactAmendmentCommitmentPersistsAndRejectsRestoreMutation()throws Exception {
  var c=new SourceOpenerQueue.AmendmentCommitment(SourceEvidenceCollector.REPO,184,475,"original","a".repeat(64),"b".repeat(64),"c".repeat(64),123);
  try(var q=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){q.commitAmendment("round",c);}
  try(var q=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){assertEquals(c,q.commitments().get("round"));for(var bad:List.of(new SourceOpenerQueue.AmendmentCommitment(c.repo(),184,475,"different",c.originalPin(),c.amendingPin(),c.manifestDigest(),123),new SourceOpenerQueue.AmendmentCommitment(c.repo(),184,475,"original",c.amendingPin(),c.originalPin(),c.manifestDigest(),123),new SourceOpenerQueue.AmendmentCommitment(c.repo(),184,475,"original",c.originalPin(),c.amendingPin(),"d".repeat(64),124)))assertThrows(IOException.class,()->q.commitAmendment("round",bad));}
 }
 @Test void collectorMilestonesSingleImmutableObservation()throws Exception {
  Instant now=Instant.parse("2026-11-23T00:00:00Z");var calls=new java.util.concurrent.atomic.AtomicInteger();
  var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((uri,etag)->{calls.incrementAndGet();String body=uri.getPath().endsWith("comments")||uri.getPath().endsWith("events")?"[]":"{\"body\":\"observation-"+calls.get()+"\",\"updated_at\":\"2026-11-22T00:00:00Z\"}";return new SourceEvidenceFetch.Response(200,body.getBytes(),"","",Map.of());},Clock.fixed(now,ZoneOffset.UTC)));
  var collection=collector.collectMilestones(540,now);assertEquals(3,calls.get());assertTrue(collection.ready().isEmpty());
  try(var q=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){q.retainEvidence(collection.batch());String hash=SourceEvidence.digest(collection.batch().manifest());assertArrayEquals(collection.batch().manifest(),Files.readAllBytes(root.resolve("evidence-"+hash+".json")));}assertEquals(3,calls.get());
 }
}
