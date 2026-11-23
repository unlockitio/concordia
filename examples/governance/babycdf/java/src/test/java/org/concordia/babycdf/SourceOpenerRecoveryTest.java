package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceOpenerRecoveryTest {
 @TempDir Path root;
 SourceOpenerQueue.Binding binding(String pin){return new SourceOpenerQueue.Binding(SourceEvidenceCollector.REPO,1,"proposal","cid",1,"a".repeat(64),"b".repeat(64),"round",Instant.EPOCH,1,pin,"","");}
 @Test void crossProcessWriterExclusionAndClosedPinPayload()throws Exception {
  try(var queue=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){
   assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger()));
   assertThrows(IllegalArgumentException.class,()->queue.retainBlocked(binding("RAW_BODY_OR_Bearer_SECRET"),"blocked_pin"));
   assertFalse(Files.exists(root.resolve("source-opener-v1.json")));
  }
  try(var reopened=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){assertTrue(reopened.snapshot().isEmpty());}
 }
 @Test void conflictingJournalDigestAndUnknownStateFailClosed()throws Exception {
  String pin="[\""+"a".repeat(40)+"\",[[\"proposals/x\",\"added\",\""+"b".repeat(40)+"\",\"\"]]]";
  try(var q=new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger())){q.retainBlocked(binding(pin),"blocked_pin");}
  var path=root.resolve("source-opener-v1.json");String original=Files.readString(path);
  Files.writeString(path,original.replace("blocked\"","unknown\""));assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger()));
  Files.writeString(path,original.replace("\"round\"","\"changed\""));assertThrows(IOException.class,()->new SourceOpenerQueue(root.toRealPath(),new SourceOpenerQueueTest.Ledger()));
 }
}
