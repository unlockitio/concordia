// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual transaction 93 and historical ACS at journal BEGIN 92; strictly zero RPC. */
class SourceCatalogueCapturedTest {
 @TempDir Path directory;
 record Capture(long begin,CommandServiceOuterClass.SubmitAndWaitForTransactionRequest intent,
                TransactionOuterClass.Transaction transaction,List<StateServiceOuterClass.GetActiveContractsResponse> states) {}
 static byte[] message(DataInputStream in)throws IOException {
  int size=in.readInt();if(size<0||size>2_000_000)throw new IOException("fixture bound");
  byte[] bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException();return bytes;
 }
 Capture capture()throws IOException {
  try(var input=getClass().getResourceAsStream("/source-catalogue/captured-93.bin.gz")) {
   assertNotNull(input);
   try(var in=new DataInputStream(new GZIPInputStream(input))) {
    long begin=in.readLong();var intent=CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.parseFrom(message(in));
    var tx=TransactionOuterClass.Transaction.parseFrom(message(in));int count=in.readInt();assertEquals(13,count);
    var states=new ArrayList<StateServiceOuterClass.GetActiveContractsResponse>();
    for(int i=0;i<count;i++)states.add(StateServiceOuterClass.GetActiveContractsResponse.parseFrom(message(in)));
    assertEquals(-1,in.read());return new Capture(begin,intent,tx,states);
   }
  }
 }
 Optional<?> attest(Capture c,TransactionOuterClass.Transaction tx)throws Exception {
  var commands=c.intent().getCommands();String party=commands.getActAs(0);
  var exercise=commands.getCommands(0).getExercise();String pkg=exercise.getTemplateId().getPackageId();
  var checkpoint=c.states().stream().filter(s->s.hasActiveContract()&&s.getActiveContract().getCreatedEvent().getTemplateId().getEntityName().equals("SourceCheckpoint"))
    .findFirst().orElseThrow().getActiveContract().getCreatedEvent();var cr=checkpoint.getCreateArguments();
  var before=new JavaSourceIngestion.Checkpoint(checkpoint.getContractId(),IntakeLedger.number(cr,"generation"),IntakeLedger.text(cr,"pageToken"),IntakeLedger.field(cr,"inProgress").getBool());
  var args=exercise.getChoiceArgument().getRecord();var observations=new ArrayList<SourceObservation>();
  for(var item:IntakeLedger.field(args,"items").getList().getElementsList())observations.add(SourceCatalogueCodec.observation(item.getRecord()));
  var batch=new JavaSourceIngestion.Batch(before,new JavaSourceIngestion.Page(observations,IntakeLedger.text(args,"nextToken"),IntakeLedger.field(args,"complete").getBool()),commands.getCommandId());
  SourceCatalogueLedger.Rpc rpc=new SourceCatalogueLedger.Rpc(){
   public long end(){throw new AssertionError("no live RPC");}
   public TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest r){throw new AssertionError("no submit");}
   public Iterator<UpdateServiceOuterClass.GetUpdatesResponse> updates(UpdateServiceOuterClass.GetUpdatesRequest r){throw new AssertionError("no live RPC");}
   public Iterator<StateServiceOuterClass.GetActiveContractsResponse> active(StateServiceOuterClass.GetActiveContractsRequest r){
    assertEquals(c.begin(),r.getActiveAtOffset());String entity=r.getEventFormat().getFiltersByPartyOrThrow(party).getCumulative(0).getTemplateFilter().getTemplateId().getEntityName();
    return c.states().stream().filter(s->s.hasActiveContract()&&s.getActiveContract().getCreatedEvent().getTemplateId().getEntityName().equals(entity)).iterator();
   }
  };
  try(var ledger=new SourceCatalogueLedger(party,pkg,commands.getUserId(),directory.toRealPath(),rpc)) {
   var method=SourceCatalogueLedger.class.getDeclaredMethod("attest",TransactionOuterClass.Transaction.class,CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.class,JavaSourceIngestion.Batch.class,long.class);
   method.setAccessible(true);
   try{return (Optional<?>)method.invoke(ledger,tx,c.intent(),batch,c.begin());}
   catch(InvocationTargetException e){if(e.getCause() instanceof Exception exception)throw exception;throw e;}
  }
 }
 @Test void actualVerboseTransactionAndHistoricalPrestateProveExactBatch()throws Exception {
  var c=capture();assertEquals(92,c.begin());assertEquals(93,c.transaction().getOffset());assertEquals(52,c.transaction().getEventsCount());
  assertThrows(IOException.class,()->attest(c,c.transaction()),"legacy raw-body capture must not be reinterpreted as v2");
 }
 @Test void alteredChoiceArgumentStillFails()throws Exception {
  var c=capture();var tx=c.transaction().toBuilder();var event=tx.getEvents(0).toBuilder();var exercise=event.getExercised().toBuilder();
  var record=exercise.getChoiceArgument().getRecord().toBuilder();
  for(int i=0;i<record.getFieldsCount();i++)if(record.getFields(i).getLabel().equals("nextToken"))
   record.setFields(i,record.getFields(i).toBuilder().setValue(SourceCatalogueCodec.text("tampered")));
  exercise.setChoiceArgument(ValueOuterClass.Value.newBuilder().setRecord(record));tx.setEvents(0,event.setExercised(exercise));
  assertThrows(IOException.class,()->attest(c,tx.build()));
 }
 @Test void omittedPreviousDigestCannotProveRevision()throws Exception {
  var c=capture();var tx=c.transaction().toBuilder();var event=tx.getEvents(1).toBuilder();var created=event.getCreated().toBuilder();
  var record=created.getCreateArguments().toBuilder();
  for(int i=0;i<record.getFieldsCount();i++)if(record.getFields(i).getLabel().equals("version"))
   record.setFields(i,record.getFields(i).toBuilder().setValue(SourceCatalogueCodec.integer(2)));
  tx.setEvents(1,event.setCreated(created.setCreateArguments(record)));
  assertThrows(IOException.class,()->attest(c,tx.build()));
 }
 @Test void omittedRequiredItemFieldStillFails()throws Exception {
  var c=capture();var tx=c.transaction().toBuilder();var event=tx.getEvents(1).toBuilder();var created=event.getCreated().toBuilder();
  var record=created.getCreateArguments().toBuilder();
  for(int i=record.getFieldsCount()-1;i>=0;i--)if(record.getFields(i).getLabel().equals("version"))record.removeFields(i);
  tx.setEvents(1,event.setCreated(created.setCreateArguments(record)));
  assertThrows(IOException.class,()->attest(c,tx.build()));
 }
}
