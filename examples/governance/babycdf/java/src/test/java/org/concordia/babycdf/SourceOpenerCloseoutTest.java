package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import com.daml.ledger.api.v2.*;
import static org.junit.jupiter.api.Assertions.*;import static org.concordia.babycdf.SourceCatalogueCodec.*;
class SourceOpenerCloseoutTest {
 @TempDir Path root;
 @Test void everyBlockedBatchDurableImmutableAndNeverAdmitted()throws Exception{
  try(var queue=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){
   for(String category:List.of("blocked_pin","unresolved","incomplete","unproven_amendment")){var batch=new SourceEvidenceCollector.Batch(List.of(new SourceEvidence.Entry(Instant.EPOCH,"result",SourceEvidenceCollector.REPO,540,1,category,"",Map.of("status",category))),List.of(category),Instant.EPOCH,Instant.EPOCH);queue.retainEvidence(batch);queue.retainEvidence(batch);}
   try(var paths=Files.list(root)){assertEquals(4,paths.filter(p->p.getFileName().toString().startsWith("evidence-")).count());}assertTrue(queue.snapshot().isEmpty());assertEquals("idle",queue.tick());
  }
 }
 @Test void milestoneHeadForeignOrDanglingBlocksAdapter()throws Exception{
  String pkg="a".repeat(64);var head=EventOuterClass.CreatedEvent.newBuilder().setContractId("head").setCreateArguments(record("number",integer(540),"latest",text("previous")).getRecord()).build();
  var rpc=new SourceOpenerLedger.Rpc(){public Instant now(){return Instant.EPOCH;}public List<EventOuterClass.CreatedEvent> active(String m,String e){return e.equals("SourceRoundHead")?List.of(head):List.of();}public void submit(CommandServiceOuterClass.SubmitAndWaitRequest r){fail("must not submit");}};
  var proof=new IntakeLedger("opener",pkg,pkg,"gov","user",root.toRealPath(),r->{},(p,m,e)->List.of(),false);
  try(var ledger=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proof)){assertThrows(IOException.class,()->ledger.retainMilestoneEvidence(new SourceEvidenceCollector.Batch(List.of(),List.of(),Instant.EPOCH,Instant.EPOCH)));}
 }
 @Test void automaticInventoryComesFromCredentialAcs()throws Exception{
  var sample=SourceCatalogueCodecTest.sample();var credential=EventOuterClass.CreatedEvent.newBuilder().setCreateArguments(record("observation",observation(sample)).getRecord()).build();String pkg="a".repeat(64);
  var rpc=new SourceOpenerLedger.Rpc(){public Instant now(){return Instant.EPOCH;}public List<EventOuterClass.CreatedEvent> active(String m,String e){return e.equals("SourceObservationCredential")?List.of(credential):List.of();}public void submit(CommandServiceOuterClass.SubmitAndWaitRequest r){fail();}};
  var proof=new IntakeLedger("opener",pkg,pkg,"gov","user",root.toRealPath(),r->{},(p,m,e)->List.of(),false);
  try(var ledger=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proof)){assertEquals(List.of(7L),ledger.inventory("milestone"));assertTrue(ledger.inventory("proposal").isEmpty());}
 }
}
