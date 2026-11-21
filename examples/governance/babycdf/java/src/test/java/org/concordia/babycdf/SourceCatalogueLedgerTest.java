// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.concordia.babycdf.SourceCatalogueCodec.*;
import java.nio.file.Path;
import java.util.*;
import java.io.IOException;

class SourceCatalogueLedgerTest {
    @TempDir Path directory;
    static final String PKG="a".repeat(64), PARTY="opener";
    static ValueOuterClass.Identifier id(String entity) {return IntakeLedger.id(PKG,MODULE,entity);}
    static EventOuterClass.CreatedEvent created(String cid,String entity,ValueOuterClass.Record record) {
        return EventOuterClass.CreatedEvent.newBuilder().setContractId(cid).setTemplateId(id(entity))
                .setCreateArguments(record).addSignatories(PARTY).addWitnessParties(PARTY).build();
    }
    static ValueOuterClass.Record checkpoint(long gen,String token,boolean progress,long full,String fingerprint,long high) {
        return record("opener",ValueOuterClass.Value.newBuilder().setParty(PARTY).build(),"repo",text(REPOSITORY),
                "highWater",integer(high),"generation",integer(gen),"phase",constructor("FullSweep"),"pageToken",text(token),
                "inProgress",bool(progress),"completedFullGeneration",integer(full),"overlapsSinceFull",integer(0),
                "scanFingerprint",text(fingerprint)).getRecord();
    }
    static class Server implements SourceCatalogueLedger.Rpc {
        long offset=1;int sends,updateCalls;boolean lose,reject;List<EventOuterClass.CreatedEvent> active=new ArrayList<>();
        List<TransactionOuterClass.Transaction> history=new ArrayList<>();
        Map<Long,List<EventOuterClass.CreatedEvent>> snapshots=new HashMap<>();
        CommandServiceOuterClass.SubmitAndWaitForTransactionRequest last;
        public long end(){return offset;}
        public Iterator<StateServiceOuterClass.GetActiveContractsResponse> active(StateServiceOuterClass.GetActiveContractsRequest r) {
            assertTrue(r.getActiveAtOffset()<=offset);assertTrue(r.getEventFormat().getFiltersByPartyMap().containsKey(PARTY));
            for(var f:r.getEventFormat().getFiltersByPartyOrThrow(PARTY).getCumulativeList()) {
                assertEquals(IntakeLedger.DEMO_PACKAGE_NAME,f.getTemplateFilter().getTemplateId().getPackageId());
                assertEquals(MODULE,f.getTemplateFilter().getTemplateId().getModuleName());
            }
            var rows=r.getActiveAtOffset()==offset?active:snapshots.get(r.getActiveAtOffset());
            if(rows==null) throw new IllegalStateException("snapshot absent");
            String entity=r.getEventFormat().getFiltersByPartyOrThrow(PARTY).getCumulative(0).getTemplateFilter().getTemplateId().getEntityName();
            return rows.stream().filter(e->e.getTemplateId().getEntityName().equals(entity)).map(e->StateServiceOuterClass.GetActiveContractsResponse.newBuilder().setActiveContract(
                    StateServiceOuterClass.ActiveContract.newBuilder().setCreatedEvent(e)).build()).iterator();
        }
        public Iterator<UpdateServiceOuterClass.GetUpdatesResponse> updates(UpdateServiceOuterClass.GetUpdatesRequest r) {
            updateCalls++;assertTrue(r.hasEndInclusive());
            for(var f:r.getUpdateFormat().getIncludeTransactions().getEventFormat().getFiltersByPartyOrThrow(PARTY).getCumulativeList())
                assertEquals(IntakeLedger.DEMO_PACKAGE_NAME,f.getTemplateFilter().getTemplateId().getPackageId());
            return history.stream().filter(t->t.getOffset()>r.getBeginExclusive() && t.getOffset()<=r.getEndInclusive())
                    .map(t->UpdateServiceOuterClass.GetUpdatesResponse.newBuilder().setTransaction(t).build()).iterator();
        }
        public TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest r) throws IOException {
            sends++;last=r;if(reject) throw new IOException("definite rejected fixture");
            var command=last.getCommands().getCommands(0);
            var commandId=command.hasCreate() ? command.getCreate().getTemplateId() : command.getExercise().getTemplateId();
            assertEquals(PKG,commandId.getPackageId());
            assertFalse(commandId.getPackageId().startsWith("#"));
            snapshots.put(offset,List.copyOf(active));
            var commands=r.getCommands();var action=commands.getCommands(0);
            var tx=TransactionOuterClass.Transaction.newBuilder().setCommandId(commands.getCommandId())
                    .setWorkflowId(commands.getWorkflowId()).setUpdateId("update-"+sends).setOffset(++offset);
            if(action.hasCreate()) {
                var e=created("initial","SourceCheckpoint",action.getCreate().getCreateArguments());active.add(e);
                tx.addEvents(EventOuterClass.Event.newBuilder().setCreated(e));
            } else {
                var ex=action.getExercise();var args=ex.getChoiceArgument().getRecord();
                var observations=IntakeLedger.field(args,"items").getList().getElementsList();
                var old=active.stream().filter(e->e.getContractId().equals(ex.getContractId())).findFirst().orElseThrow();
                long gen=IntakeLedger.number(old.getCreateArguments(),"generation")+1;
                String token=IntakeLedger.text(args,"nextToken");boolean complete=IntakeLedger.field(args,"complete").getBool();
                var digests=new ArrayList<String>();long high=0;
                for(var o:observations) {
                    String digest=SourceCatalogueDigest.digest(o.getRecord());digests.add(SourceCatalogueDigest.text(digest));
                    high=Math.max(high,IntakeLedger.number(o.getRecord(),"number"));
                    var item=created("item-"+high,"SourceItem",record("opener",ValueOuterClass.Value.newBuilder().setParty(PARTY).build(),
                            "repo",text(REPOSITORY),"observation",o,"fingerprint",text(digest),"version",integer(1),
                            "previousDigest",ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()).build()).getRecord());
                    active.add(item);tx.addEvents(EventOuterClass.Event.newBuilder().setCreated(item));
                }
                String scan=SourceObservation.hash("(\"\",["+String.join(",",digests)+"])");
                var after=created("after-"+sends,"SourceCheckpoint",checkpoint(gen,token,!complete,complete?gen:0,scan,high));
                active.remove(old);active.add(after);
                tx.addEvents(EventOuterClass.Event.newBuilder().setCreated(after));
                tx.addEvents(EventOuterClass.Event.newBuilder().setExercised(EventOuterClass.ExercisedEvent.newBuilder()
                        .setContractId(ex.getContractId()).setTemplateId(ex.getTemplateId()).setChoice("CommitBatch")
                        .setConsuming(true).addActingParties(PARTY).setChoiceArgument(ex.getChoiceArgument())));
            }
            var result=tx.build();history.add(result);if(lose) throw new IOException("lost response");return result;
        }
    }
    SourceCatalogueLedger adapter(Server server) throws Exception {return new SourceCatalogueLedger(PARTY,PKG,"user",directory.toRealPath(),server);}
    @Test void wrongDeployedPackageInAcsIsRejected() throws Exception {
        var server=new Server();
        var event=created("wrong","SourceCheckpoint",checkpoint(0,"",false,0,"",0));
        server.active.add(event.toBuilder().setTemplateId(id("SourceCheckpoint").toBuilder().setPackageId("b".repeat(64))).build());
        try(var ledger=adapter(server)) {
            assertThrows(IOException.class,ledger::checkpoint);
            assertEquals(0,server.sends);
        }
    }
    @Test void realBuilderQueryDecodeSendProofAndEngineLabels() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();assertEquals("initial",cp.cid());
        var logs=new ArrayList<String>();var source=SourceCatalogueCodecTest.sample();
        new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(source),"",true),logs::add).cycle();
        assertEquals(List.of("Milestone #7 added"),logs);assertEquals(2,server.sends);
        assertEquals(List.of(PARTY),server.last.getCommands().getActAsList());
        assertEquals("user",server.last.getCommands().getUserId());
        var ex=server.last.getCommands().getCommands(0).getExercise();assertEquals(id("SourceCheckpoint"),ex.getTemplateId());
        assertEquals("initial",ex.getContractId());assertEquals("CommitBatch",ex.getChoice());
        assertEquals(source,ledger.items(Set.of(7L)).get(7L).observation());
        assertEquals(1,ledger.checkpoint().generation());
    }
    @Test void lostResponseProofAndRestartNeverSendsTwice() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();server.lose=true;
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(SourceCatalogueCodecTest.sample()),"next",false),
                "babycdf-source-"+"b".repeat(64));
        assertThrows(IOException.class,()->ledger.commit(batch));assertTrue(ledger.proof(batch).isPresent());
        var restarted=adapter(server);assertThrows(IOException.class,()->restarted.commit(batch));assertEquals(2,server.sends);
        assertTrue(restarted.proof(batch).isPresent());
        server.history.set(1,server.history.get(1).toBuilder().setCommandId("wrong").build());assertTrue(restarted.proof(batch).isEmpty());
    }
    @Test void authoritativePrestateMismatchAndWorkflowForgeryCannotProve() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(SourceCatalogueCodecTest.sample()),"",true),
                "babycdf-source-"+"d".repeat(64));
        ledger.commit(batch);
        server.history.set(1,server.history.get(1).toBuilder().setWorkflowId("forged-not-prestate").build());
        assertTrue(ledger.proof(batch).isPresent());
        server.snapshots.put(2L,List.of(created("different-cid","SourceCheckpoint",checkpoint(0,"",false,0,"",0))));
        assertTrue(ledger.proof(batch).isEmpty());
    }
    @Test void actualGrpcEndErrorsAreCheckedAndPretransmission() throws Exception {
        var server=new Server(){public long end(){throw io.grpc.Status.UNAVAILABLE.asRuntimeException();}};
        var ledger=adapter(server);
        assertThrows(IOException.class,()->ledger.items(Set.of(7L)));
        var batch=new JavaSourceIngestion.Batch(new JavaSourceIngestion.Checkpoint("initial",0,"",false),
                new JavaSourceIngestion.Page(List.of(),"",true),"babycdf-source-"+"e".repeat(64));
        assertThrows(JavaSourceIngestion.PreTransmission.class,()->ledger.commit(batch));assertEquals(0,server.sends);
    }
    @Test void oversizedIntentHasNoMarkerOrRpc() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();var sample=SourceCatalogueCodecTest.sample();
        var observations=new ArrayList<SourceObservation>();
        for(int i=1;i<=50;i++) observations.add(new SourceObservation(i,SourceObservation.Kind.PROPOSAL,
                "https://github.com/canton-foundation/canton-dev-fund/pull/"+i,"Title","界".repeat(32768),"open",
                sample.updatedAt(),sample.observedAt(),null,"",false,SourceObservation.hash("x"),false));
        String command="babycdf-source-"+"f".repeat(64);
        assertThrows(JavaSourceIngestion.PreTransmission.class,()->ledger.commit(new JavaSourceIngestion.Batch(cp,
                new JavaSourceIngestion.Page(observations,"",true),command)));
        assertEquals(1,server.sends);assertFalse(java.nio.file.Files.exists(directory.resolve(command+".intent")));
    }
    @Test void confirmedRestartSkipsHistoryButReceiptNeverProvesEffects() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(SourceCatalogueCodecTest.sample()),"",true),
                "babycdf-source-"+"1".repeat(64));
        ledger.commit(batch);assertTrue(ledger.proof(batch).isPresent());int calls=server.updateCalls;
        assertEquals(1,adapter(server).checkpoint().generation());assertEquals(calls,server.updateCalls);
        server.history.clear();assertTrue(adapter(server).proof(batch).isEmpty());
    }
    @Test void malformedAndSymlinkReceiptsFailClosed() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();
        String command="babycdf-source-"+"2".repeat(64);
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(),"",true),command);
        ledger.commit(batch);var path=directory.resolve(command+".receipt");
        java.nio.file.Files.writeString(path,"CONFIRMED|wrong|3|update");
        assertThrows(IOException.class,()->adapter(server).checkpoint());assertThrows(IOException.class,()->ledger.proof(batch));
        var other=directory.resolve("bad-receipt");java.nio.file.Files.writeString(other,"bad");
        java.nio.file.Files.move(path,directory.resolve("old-receipt"));java.nio.file.Files.createSymbolicLink(path,other);
        assertThrows(IOException.class,()->adapter(server).checkpoint());
    }
    @Test void postCheckpointEveryFieldMutationCannotProve() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(SourceCatalogueCodecTest.sample()),"",true),
                "babycdf-source-"+"3".repeat(64));
        ledger.commit(batch);var original=server.history.get(1);
        int checkpointIndex=-1;
        for(int i=0;i<original.getEventsCount();i++) if(original.getEvents(i).hasCreated()
                && original.getEvents(i).getCreated().getTemplateId().getEntityName().equals("SourceCheckpoint")) checkpointIndex=i;
        assertTrue(checkpointIndex>=0);var event=original.getEvents(checkpointIndex).getCreated();
        for(int i=0;i<event.getCreateArguments().getFieldsCount();i++) {
            var field=event.getCreateArguments().getFields(i);var value=field.getValue();
            if(value.hasInt64()) value=integer(value.getInt64()+1);
            else if(value.hasText()) value=text(value.getText()+"x");
            else if(value.hasBool()) value=bool(!value.getBool());
            else if(value.hasParty()) value=ValueOuterClass.Value.newBuilder().setParty("foreign").build();
            else if(value.hasEnum()) value=constructor("OverlapSweep");
            var modified=event.toBuilder().setCreateArguments(event.getCreateArguments().toBuilder().setFields(i,field.toBuilder().setValue(value)));
            server.history.set(1,original.toBuilder().setEvents(checkpointIndex,EventOuterClass.Event.newBuilder().setCreated(modified)).build());
            try {assertTrue(ledger.proof(batch).isEmpty(),field.getLabel());} catch(IOException expected) { }
        }
    }
    @Test void definiteGrpcRejectionIsTerminalNotConfirmed() throws Exception {
        var server=new Server(){public TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest r) throws IOException {
            if(r.getCommands().getCommands(0).hasExercise()) {sends++;throw io.grpc.Status.INVALID_ARGUMENT.asRuntimeException();}
            return super.submit(r);
        }};
        var ledger=adapter(server);var cp=ledger.checkpoint();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(),"",true),"babycdf-source-"+"4".repeat(64));
        assertThrows(IOException.class,()->ledger.commit(batch));assertTrue(ledger.proof(batch).isEmpty());
        assertThrows(IOException.class,()->adapter(server).checkpoint());assertEquals(2,server.sends);
    }
    @Test void missingUnexpectedAndWrongVersionEffectsCannotProve() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(SourceCatalogueCodecTest.sample()),"",true),
                "babycdf-source-"+"5".repeat(64));ledger.commit(batch);var tx=server.history.get(1);
        var item=tx.getEvents(0).getCreated();
        server.history.set(1,tx.toBuilder().removeEvents(0).build());assertTrue(ledger.proof(batch).isEmpty());
        server.history.set(1,tx.toBuilder().addEvents(tx.getEvents(0)).build());
        assertThrows(IOException.class,()->ledger.proof(batch));
        var r=item.getCreateArguments().toBuilder();
        for(int i=0;i<r.getFieldsCount();i++) if(r.getFields(i).getLabel().equals("version"))
            r.setFields(i,r.getFields(i).toBuilder().setValue(integer(9)));
        server.history.set(1,tx.toBuilder().setEvents(0,EventOuterClass.Event.newBuilder().setCreated(item.toBuilder().setCreateArguments(r))).build());
        try {assertTrue(ledger.proof(batch).isEmpty());} catch(IOException expected) { }
    }
    @Test void transactionActorPackageAndArgumentsMismatchCannotProve() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(),"",true),"babycdf-source-"+"6".repeat(64));
        ledger.commit(batch);var tx=server.history.get(1);int index=tx.getEventsCount()-1;var exercise=tx.getEvents(index).getExercised();
        for(var wrong:List.of(exercise.toBuilder().clearActingParties().addActingParties("other").build(),
                exercise.toBuilder().setTemplateId(IntakeLedger.id("b".repeat(64),MODULE,"SourceCheckpoint")).build(),
                exercise.toBuilder().setChoiceArgument(record("items",text("wrong"))).build(),
                exercise.toBuilder().setContractId("stale").build())) {
            server.history.set(1,tx.toBuilder().setEvents(index,EventOuterClass.Event.newBuilder().setExercised(wrong)).build());
            assertTrue(ledger.proof(batch).isEmpty());
        }
    }
    @Test void staleReplicaNeverSubmitsAndPrunedHistoryFailsChecked() throws Exception {
        var server=new Server();var first=adapter(server);var second=adapter(server);var cp=first.checkpoint();
        assertEquals(cp,second.checkpoint());
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(),"",true),"babycdf-source-"+"7".repeat(64));
        first.commit(batch);
        assertThrows(JavaSourceIngestion.PreTransmission.class,()->second.commit(batch));assertEquals(2,server.sends);
        server.snapshots.clear();
        var pruned=new Server(){public Iterator<StateServiceOuterClass.GetActiveContractsResponse> active(StateServiceOuterClass.GetActiveContractsRequest r) {
            throw io.grpc.Status.OUT_OF_RANGE.asRuntimeException();}};
        assertThrows(IOException.class,()->adapter(pruned).items(Set.of(1L)));
    }
    @Test void rejectedBatchDoesNotAdvanceAndRestartDoesNotReplay() throws Exception {
        var server=new Server();var ledger=adapter(server);var cp=ledger.checkpoint();server.reject=true;
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(SourceCatalogueCodecTest.sample()),"",true),
                "babycdf-source-"+"c".repeat(64));
        assertThrows(IOException.class,()->ledger.commit(batch));assertThrows(IOException.class,ledger::checkpoint);
        assertEquals("initial",server.active.getFirst().getContractId());assertTrue(ledger.items(Set.of(7L)).isEmpty());
        assertThrows(IOException.class,()->adapter(server).commit(batch));assertEquals(2,server.sends);assertTrue(ledger.proof(batch).isEmpty());
    }
}
