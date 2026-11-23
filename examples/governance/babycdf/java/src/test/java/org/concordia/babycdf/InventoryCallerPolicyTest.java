package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import com.daml.ledger.api.v2.*;import java.nio.file.*;import java.time.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class InventoryCallerPolicyTest {
 @TempDir Path root;
 @Test void actualCallerPolicyGenericEnrichesNonallowlistedDemoDoesNot()throws Exception{
  assertEquals(SourceOpenerScheduler.Scope.GENERIC,SourceOpenerScheduler.callerScope("--automatic-source"));assertEquals(SourceOpenerScheduler.Scope.STATIC_DEMO,SourceOpenerScheduler.callerScope("--integrated-demo"));
  for(String caller:List.of("--automatic-source","--integrated-demo")){
   Path journal=Files.createDirectory(root.resolve(caller.substring(2))).toRealPath();var credential=DemoInventoryScaleTest.credential(2000,true);var calls=new java.util.concurrent.atomic.AtomicInteger();String pkg="a".repeat(64);
   var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((uri,etag)->{calls.incrementAndGet();String body=uri.getPath().endsWith("files")?"[]":uri.getPath().contains("trees")?"{\"truncated\":false,\"tree\":[]}":"{\"created_at\":\"2026-01-01T00:00:00Z\",\"head\":{\"sha\":\""+"b".repeat(40)+"\"}}";return new SourceEvidenceFetch.Response(200,body.getBytes(),"","",Map.of());},Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)));
   var rpc=new SourceOpenerLedger.Rpc(){public Instant now(){return Instant.EPOCH;}public List<EventOuterClass.CreatedEvent> active(String m,String e){return e.equals("SourceObservationCredential")?List.of(credential):List.of();}public void submit(CommandServiceOuterClass.SubmitAndWaitRequest r){fail("ineligible files must not open");}};
   var proof=new IntakeLedger("opener",pkg,pkg,"gov","user",journal,r->{},(p,m,e)->List.of(),false);
   try(var ledger=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proof);var queue=new SourceOpenerQueue(journal,ledger)){assertEquals("idle",new SourceOpenerScheduler(collector,ledger,queue,Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC),SourceOpenerScheduler.callerScope(caller)).automaticCycle(null));}
   assertEquals(caller.equals("--automatic-source")?4:0,calls.get());
  }
  String intake=Files.readString(Path.of("src/main/java/org/concordia/babycdf/Intake.java"));assertEquals(2,intake.split("SourceOpenerScheduler.callerScope\\(args\\[0\\]\\)",-1).length-1);
 }
}
