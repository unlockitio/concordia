package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IntakeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path journal;
    @org.junit.jupiter.api.BeforeEach void canonicalJournal() throws Exception { journal=journal.toRealPath(); }
    @Test void durableMarkerExactExclusivePrivateAndRestartSafe() throws Exception {
        var plan=new IntakePlan(IntakeSources.MILESTONE,request().roundId());
        byte[] payload="exact intent\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IntakeLedger.recordIntent(journal,plan.commandId(),payload);
        var marker=journal.resolve(plan.commandId()+".intent");
        assertArrayEquals(payload,java.nio.file.Files.readAllBytes(marker));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),java.nio.file.Files.getPosixFilePermissions(marker));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->IntakeLedger.recordIntent(journal,plan.commandId(),payload));
        var fake=new Fake() {
            public void submit(Intake.Request r,IntakePlan p) throws IOException {
                try { IntakeLedger.recordIntent(journal,p.commandId(),payload); }
                catch(IOException e) { throw new Intake.PreTransmission("no RPC",e); }
                super.submit(r,p);
            }
        };
        for(int restart=0;restart<2;restart++) {
            var intake=new Intake(fake,s->null);
            assertThrows(Intake.PreTransmission.class,intake::step);
        }
        assertEquals(0,fake.submits);
    }
    @Test void symlinkDanglingAndMissingDirectoryFailClosed() throws Exception {
        String command=new IntakePlan(IntakeSources.MILESTONE,"one").commandId();
        var marker=journal.resolve(command+".intent");
        java.nio.file.Files.createSymbolicLink(marker,journal.resolve("absent"));
        assertThrows(IOException.class,()->IntakeLedger.recordIntent(journal,command,new byte[0]));
        assertFalse(java.nio.file.Files.exists(journal.resolve("absent")));
        var alias=journal.resolve("alias"); java.nio.file.Files.createSymbolicLink(alias,journal);
        assertThrows(IllegalArgumentException.class,()->IntakeLedger.validateJournal(alias));
        assertThrows(IllegalArgumentException.class,()->IntakeLedger.validateJournal(journal.resolve("missing")));
        assertThrows(IOException.class,()->IntakeLedger.recordIntent(journal.resolve("missing"),command,new byte[0]));
    }

    @Test void protobufProofRejectsMismatchDuplicateAndWrongSigner() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var party=com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setParty("opener").build();
        var p=com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                .addFields(IntakeLedger.f("roundId",IntakeLedger.txt(r.roundId())))
                .addFields(IntakeLedger.f("opener",party)).addFields(IntakeLedger.f("proposer",party))
                .addFields(IntakeLedger.f("actionCid",com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setContractId("action").build()));
        var proposal=com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("proposal")
                .addSignatories("opener").setCreateArguments(p).build();
        var a=com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                .addFields(IntakeLedger.f("baseVersion",com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setInt64(2).build()))
                .addFields(IntakeLedger.f("agenda",IntakeLedger.agenda(plan.source())));
        var action=com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("action")
                .addSignatories("opener").setCreateArguments(a).build();
        assertTrue(IntakeLedger.proof(r,plan,List.of(proposal),List.of(action)));
        assertThrows(IOException.class,()->IntakeLedger.proof(r,plan,List.of(proposal,proposal),List.of(action)));
        assertThrows(IOException.class,()->IntakeLedger.proof(r,plan,List.of(proposal),List.of()));
        assertThrows(IOException.class,()->IntakeLedger.proof(r,plan,List.of(proposal.toBuilder().clearSignatories().addSignatories("wrong").build()),List.of(action)));
        var bad=action.toBuilder().setCreateArguments(a.clone().addFields(IntakeLedger.f("baseVersion",IntakeLedger.txt("duplicate")))).build();
        assertThrows(IOException.class,()->IntakeLedger.proof(r,plan,List.of(proposal),List.of(bad)));
        var mismatch=action.toBuilder().setCreateArguments(a.clone().setFields(0,IntakeLedger.f("baseVersion",com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setInt64(3).build()))).build();
        assertThrows(IOException.class,()->IntakeLedger.proof(r,plan,List.of(proposal),List.of(mismatch)));
    }
    static Intake.Request request() { return new Intake.Request("cid","opener","run","run:milestone-540","milestone-540",60,2); }
    static class Fake implements Intake.Transport {
        List<Intake.Request> requests=List.of(request());
        int submits; boolean proof; boolean fail; boolean landed;
                public List<Intake.Request> requests() throws IOException { return requests; }
        public boolean proof(Intake.Request r,IntakePlan p) { return proof; }
                public void submit(Intake.Request r,IntakePlan p) throws IOException {
            submits++; proof=landed;
            if(fail) throw new IOException("lost response");
        }
    }
    @Test void opensOneRequestWithLedgerBridgeAndCuratedEvidence() throws Exception {
        var fake=new Fake(); fake.landed=true;
        var intake=new Intake(fake,s->{ throw new AssertionError("milestone must not fetch grant"); });
        assertTrue(intake.step().startsWith("opened"));
        assertEquals(1,fake.submits);
        assertTrue(intake.step().startsWith("reconciled")); assertEquals(1,fake.submits);
    }
    @Test void uncertainWithoutProofNeverRetries() {
        var fake=new Fake(); fake.fail=true;
        var intake=new Intake(fake,s->null);
        assertThrows(IOException.class,intake::step); assertThrows(IOException.class,intake::step);
        assertEquals(1,fake.submits);
    }
    @Test void lostResponseNeedsActualProof() throws Exception {
        var fake=new Fake(); fake.fail=true; fake.landed=true;
        assertTrue(new Intake(fake,s->null).step().startsWith("reconciled"));
    }
    @Test void successfulResponseWithoutProofIsNotSuccess() {
        var fake=new Fake(); assertThrows(IOException.class,()->new Intake(fake,s->null).step());
    }
    @Test void refusesMultipleRequestsAndDoesNotPrecreateRounds() {
        var fake=new Fake(); fake.requests=List.of(request(),request());
        assertThrows(IOException.class,()->new Intake(fake,s->null).step()); assertEquals(0,fake.submits);
    }
    @Test void badGrantBytesNeverSubmit() {
        var fake=new Fake(); fake.requests=List.of(new Intake.Request("cid","opener","run","run:proposal-184","proposal-184",60,0));
        assertThrows(IllegalArgumentException.class,()->new Intake(fake,s->new byte[0]).step()); assertEquals(0,fake.submits);
    }
    @Test void requestBuilderUsesOpenerAndExactSubject() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var command=IntakeLedger.request(r,plan,"b".repeat(64),"gov","readback-intake").getCommands();
        assertEquals(List.of("opener"),command.getActAsList()); assertEquals("readback-intake",command.getUserId());
        assertEquals(plan.commandId(),command.getCommandId()); assertEquals(86400,command.getDeduplicationDuration().getSeconds());
        assertEquals(1,command.getCommandsCount());
        var exercise=command.getCommands(0).getExercise(); assertEquals("OpenAgenda",exercise.getChoice());
        assertEquals("cid",exercise.getContractId());
        assertEquals("Cap.Examples.BabyCdf.Intake",exercise.getTemplateId().getModuleName());
        assertEquals("IntakeRequest",exercise.getTemplateId().getEntityName());
        var args=exercise.getChoiceArgument().getRecord();
        assertEquals(1,args.getFieldsCount());
        assertEquals("gov",IntakeLedger.field(args,"governorCid").getContractId());
    }
    @Test void successfulStepAllowsNextDifferentRequest() throws Exception {
        var fake=new Fake(); fake.landed=true; var intake=new Intake(fake,s->null);
        intake.step(); fake.proof=false;
        fake.requests=List.of(new Intake.Request("cid2","opener","run2","run2:milestone-540","milestone-540",86400,0));
        assertTrue(intake.step().startsWith("opened")); assertEquals(2,fake.submits);
    }
    @Test void realAdapterPersistsBeforeRpcAndBlocksRestart() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        IntakeLedger.Rpc rpc=command -> {
            assertTrue(java.nio.file.Files.exists(journal.resolve(plan.commandId()+".intent")));
            calls.incrementAndGet(); throw io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException();
        };
        for(int restart=0;restart<2;restart++) try(var ledger=adapter(rpc,false)) {
            assertThrows(IOException.class,()->ledger.submit(r,plan));
        }
        assertEquals(1,calls.get());
        try(var ledger=adapter(rpc,true)) { assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan)); }
        assertEquals(1,calls.get());
    }
    IntakeLedger adapter(IntakeLedger.Rpc rpc,boolean retry) {
        return new IntakeLedger("opener","a".repeat(64),"b".repeat(64),"gov","readback-intake",journal,rpc,
            (pkg,module,entity)->entity.equals("AgendaGovernor") ? List.of(
                com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("gov")
                .setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder().addFields(
                    IntakeLedger.f("opener",com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setParty("opener").build()))).build()) : List.of(),retry);
    }
    @Test void realAdapterDefiniteRejectionAllowsOnlyOneExplicitIdenticalRetry() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        IntakeLedger.Rpc rpc=command->{ calls.incrementAndGet(); throw io.grpc.Status.PERMISSION_DENIED.asRuntimeException(); };
        try(var ledger=adapter(rpc,false)) { assertThrows(Intake.Rejected.class,()->ledger.submit(r,plan)); }
        assertEquals("PERMISSION_DENIED",new String(IntakeLedger.readMarker(journal.resolve(plan.commandId()+".rejected"))));
        try(var ledger=adapter(rpc,false)) { assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan)); }
        assertEquals(1,calls.get());
        try(var ledger=adapter(rpc,true)) { assertThrows(Intake.Rejected.class,()->ledger.submit(r,plan)); }
        try(var ledger=adapter(rpc,true)) { assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan)); }
        assertEquals(2,calls.get());
    }
    @Test void retryRejectsChangedPayloadAndAllUncertainCodes() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        try(var ledger=adapter(command->{throw io.grpc.Status.INVALID_ARGUMENT.asRuntimeException();},false)) {
            assertThrows(Intake.Rejected.class,()->ledger.submit(r,plan));
        }
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var changed=new Intake.Request("changed-cid",r.opener(),r.runId(),r.roundId(),r.sourceKey(),r.windowSeconds(),r.baseVersion());
        try(var ledger=adapter(command->calls.incrementAndGet(),true)) {
            assertThrows(Intake.PreTransmission.class,()->ledger.submit(changed,plan));
        }
        assertEquals(0,calls.get());
        for(String code:List.of("DEADLINE_EXCEEDED","UNAVAILABLE","INTERNAL","ABORTED","UNKNOWN","ALREADY_EXISTS"))
            assertFalse(IntakeLedger.definite(code));
        for(String code:List.of("PERMISSION_DENIED","UNAUTHENTICATED","INVALID_ARGUMENT","NOT_FOUND"))
            assertTrue(IntakeLedger.definite(code));
    }
    @Test void realAdapterJournalErrorNeverCallsRpc() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        IntakeLedger.recordIntent(journal,plan.commandId(),new byte[0]);
        try(var ledger=adapter(command->calls.incrementAndGet(),false)) {
            assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan));
        }
        assertEquals(0,calls.get());
    }
    @Test void boundedPollSuppressesRepeatedIdleAndNeverRetriesErrors() throws Exception {
        var fake=new Fake(); fake.requests=List.of();
        var logs=new java.util.ArrayList<String>(); var sleeps=new java.util.concurrent.atomic.AtomicInteger();
        Intake.poll(new Intake(fake,s->null),3,1,millis->sleeps.incrementAndGet(),logs::add);
        assertEquals(List.of("idle"),logs); assertEquals(2,sleeps.get());
        fake.requests=List.of(request()); fake.fail=true;
        assertThrows(IOException.class,()->Intake.poll(new Intake(fake,s->null),3,1,millis->{},logs::add));
        assertEquals(1,fake.submits);
        assertThrows(IllegalArgumentException.class,()->Intake.poll(new Intake(fake,s->null),0,1,millis->{},logs::add));
    }
    @Test void definiteRejectedClearsInMemoryLatchForExplicitCallerContinuation() throws Exception {
        var fake=new Fake() {
            public void submit(Intake.Request r,IntakePlan p) throws IOException {
                submits++;
                if(submits==1) throw new Intake.Rejected("definite",null);
                proof=true;
            }
        };
        var intake=new Intake(fake,s->null);
        assertThrows(Intake.Rejected.class,intake::step);
        assertEquals(1,fake.submits);
        assertTrue(intake.step().startsWith("opened"));
    }
    @Test void definiteRejectionThenExplicitSuccessKeepsExactBytesAndPersistsBeforeRpc() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var captured=new java.util.ArrayList<byte[]>();
        try(var ledger=adapter(command->{captured.add(command.toByteArray()); throw io.grpc.Status.PERMISSION_DENIED.asRuntimeException();},false)) {
            assertThrows(Intake.Rejected.class,()->ledger.submit(r,plan));
        }
        try(var ledger=adapter(command->{
            assertTrue(java.nio.file.Files.exists(journal.resolve(plan.commandId()+"-retry.intent")));
            try { assertArrayEquals(command.toByteArray(),IntakeLedger.readMarker(journal.resolve(plan.commandId()+"-retry.intent"))); }
            catch(IOException e) { throw new java.io.UncheckedIOException(e); }
            assertArrayEquals(captured.getFirst(),command.toByteArray());
            assertEquals(plan.commandId(),command.getCommands().getCommandId()); captured.add(command.toByteArray());
        },true)) { ledger.submit(r,plan); }
        assertEquals(2,captured.size());
    }
    @Test void corruptAndSymlinkRejectionFailWithoutRpc() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var command=IntakeLedger.request(r,plan,"b".repeat(64),"gov","readback-intake");
        IntakeLedger.recordIntent(journal,plan.commandId(),command.toByteArray());
        var rejection=journal.resolve(plan.commandId()+".rejected");
        java.nio.file.Files.createSymbolicLink(rejection,journal.resolve("missing"));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var ledger=adapter(c->calls.incrementAndGet(),true)) {
            assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan));
        }
        assertEquals(0,calls.get());
        // A separate journal exercises malformed evidence without mutating the symlink.
        journal=java.nio.file.Files.createDirectory(journal.resolve("corrupt"));
        IntakeLedger.recordIntent(journal,plan.commandId(),command.toByteArray());
        java.nio.file.Files.writeString(journal.resolve(plan.commandId()+".rejected"),"UNKNOWN\n");
        try(var ledger=adapter(c->calls.incrementAndGet(),true)) {
            assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan));
        }
        assertEquals(0,calls.get());
    }
    @Test void matchingProofPreventsAnyRetrySubmission() throws Exception {
        var r=request(); var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        // Legal persisted rejection: without proof guard this setup MUST reach RPC.
        try(var rejected=adapter(c->{throw io.grpc.Status.PERMISSION_DENIED.asRuntimeException();},false)) {
            assertThrows(Intake.Rejected.class,()->rejected.submit(r,plan));
        }
        assertArrayEquals(IntakeLedger.request(r,plan,"b".repeat(64),"gov","readback-intake").toByteArray(),
                IntakeLedger.readMarker(journal.resolve(plan.commandId()+".intent")));
        var party=com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setParty("opener").build();
        var proposal=com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().addSignatories("opener")
            .setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                .addFields(IntakeLedger.f("roundId",IntakeLedger.txt(r.roundId())))
                .addFields(IntakeLedger.f("opener",party)).addFields(IntakeLedger.f("proposer",party))
                .addFields(IntakeLedger.f("actionCid",com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setContractId("action").build()))).build();
        var action=com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("action").addSignatories("opener")
            .setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                .addFields(IntakeLedger.f("baseVersion",com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setInt64(2).build()))
                .addFields(IntakeLedger.f("agenda",IntakeLedger.agenda(plan.source())))).build();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var ledger=new IntakeLedger("opener","a".repeat(64),"b".repeat(64),"gov","readback-intake",journal,c->calls.incrementAndGet(),
            (pkg,module,entity)-> switch(entity) {
                case "AgendaProposal" -> List.of(proposal);
                case "AgendaAction" -> List.of(action);
                default -> List.of(com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("gov")
                    .setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder().addFields(IntakeLedger.f("opener",party))).build());
            },true)) {
            var failure=assertThrows(Intake.PreTransmission.class,()->ledger.submit(r,plan));
            assertTrue(failure.getCause().getMessage().contains("round already exists"));
        }
        assertEquals(0,calls.get());
        // Same legal journal, absent proof: the explicit retry reaches RPC.
        try(var control=adapter(c->calls.incrementAndGet(),true)) { control.submit(r,plan); }
        assertEquals(1,calls.get());
    }
    @Test void requestReadFailureStopsPollImmediately() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var fake=new Fake() { public List<Intake.Request> requests() throws IOException { calls.incrementAndGet(); throw new IOException("read"); } };
        assertThrows(IOException.class,()->Intake.poll(new Intake(fake,s->null),3,1,m->{},s->{}));
        assertEquals(1,calls.get()); assertEquals(0,fake.submits);
    }
    @Test void legacySuffixesAreSourceSpecific() {
        assertNotNull(new Intake.Request("cid","opener","run","run:grant","proposal-184",60,0));
        assertNotNull(new Intake.Request("cid","opener","run","run:illustrative-milestone","milestone-540",60,0));
        assertThrows(IllegalArgumentException.class,()->new Intake.Request("cid","opener","run","run:grant","milestone-540",60,0));
        assertThrows(IllegalArgumentException.class,()->new Intake.Request("cid","opener","run","run:illustrative-milestone","proposal-184",60,0));
    }
    @Test void deterministicRoundAndClosedSourcesEnforced() {
        assertThrows(IllegalArgumentException.class,()->new Intake.Request("cid","opener","run","wrong","milestone-540",60,0));
        assertThrows(IllegalArgumentException.class,()->new Intake.Request("cid","opener","run","run:unknown","unknown",60,0));
    }
}
