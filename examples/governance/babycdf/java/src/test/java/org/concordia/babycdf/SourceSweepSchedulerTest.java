// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SourceSweepSchedulerTest {
    static final class Ledger implements JavaSourceIngestion.Ledger {
        JavaSourceIngestion.Checkpoint cp=new JavaSourceIngestion.Checkpoint("c0",0,"",false);
        JavaSourceIngestion.Batch committed;
        boolean fail;
        Map<Long,JavaSourceIngestion.Stored> stored=new HashMap<>();
        public JavaSourceIngestion.Checkpoint checkpoint(){return cp;}
        public Map<Long,JavaSourceIngestion.Stored> items(Set<Long> numbers){return Map.copyOf(stored);}
        public void commit(JavaSourceIngestion.Batch b)throws IOException {
            if(fail)throw new JavaSourceIngestion.PreTransmission("mock RPC unavailable");
            b.page().items().forEach(o->stored.put(o.number(),new JavaSourceIngestion.Stored(o,stored.containsKey(o.number())?stored.get(o.number()).version()+1:1)));
            committed=b;cp=new JavaSourceIngestion.Checkpoint("c"+(cp.generation()+1),cp.generation()+1,b.page().nextToken(),!b.page().complete());
        }
        public Optional<JavaSourceIngestion.Proof> proof(JavaSourceIngestion.Batch b){return b.equals(committed)?Optional.of(new JavaSourceIngestion.Proof(b)):Optional.empty();}
    }
    @Test void runtimePollInvokesSourceEngineWithoutStarvingAgenda()throws Exception {
        Ledger ledger=new Ledger();AtomicLong calls=new AtomicLong(),agendaCalls=new AtomicLong();
        var intake=new Intake(new Intake.Transport(){
            public List<Intake.Request> requests(){agendaCalls.incrementAndGet();return List.of();}
            public boolean proof(Intake.Request r,IntakePlan p){return false;}
            public void submit(Intake.Request r,IntakePlan p){fail("idle agenda must not submit");}
        },source->{throw new IOException("idle agenda must not fetch grant");});
        var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,cp->{calls.incrementAndGet();return new JavaSourceIngestion.Page(List.of(),"",true);},s->{}),()->0L,1000);
        Intake.pollWithSources(intake,scheduler,3,1,millis->{},s->{});
        assertEquals(3,agendaCalls.get());assertEquals(1,calls.get());assertNotNull(ledger.committed);
    }
    @Test void blockedWorkerAllowsMultipleAgendaPollsAndTerminates() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            var entered=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var agenda=new AtomicLong();Ledger ledger=new Ledger();
            var intake=idleIntake(agenda);
            var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,cp->{
                entered.countDown();
                try {if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("test release timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                return new JavaSourceIngestion.Page(List.of(),"",true);
            },s->{}),()->0L,1000);
            try {
                Intake.pollWithSources(intake,scheduler,4,1,millis->{
                    assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
                    if(agenda.get()>=3){assertNull(ledger.committed);release.countDown();}
                },s->{});
            } finally {release.countDown();}
            assertEquals(4,agenda.get());assertNotNull(ledger.committed);
        });
    }
    static Intake idleIntake(AtomicLong calls) {
        return new Intake(new Intake.Transport(){
            public List<Intake.Request> requests(){calls.incrementAndGet();return List.of();}
            public boolean proof(Intake.Request r,IntakePlan p){return false;}
            public void submit(Intake.Request r,IntakePlan p){fail("idle submit");}
        },source->{throw new IOException("idle fetch");});
    }
    @Test void terminalWorkerFailureSurfacesAndExecutorTerminates() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            Ledger ledger=new Ledger();var before=ledger.cp;List<String> logs=new ArrayList<>();
            var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,cp->{throw new IOException("mock source failure");},logs::add),()->0L,1000);
            IOException failure=assertThrows(IOException.class,()->Intake.pollWithSources(idleIntake(new AtomicLong()),scheduler,1,1,m->{},logs::add));
            assertTrue(failure.getCause().getMessage().contains("mock source failure"));
            assertEquals(before,ledger.cp);assertNull(ledger.committed);
            assertTrue(logs.stream().noneMatch(s->s.contains(" added")));
        });
    }
    @Test void skippedTickIsNotSuccessfulRecovery()throws Exception {
        Ledger ledger=new Ledger();ledger.fail=true;AtomicLong time=new AtomicLong();
        var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,cp->new JavaSourceIngestion.Page(List.of(),"",true),s->{}),time::get,1000);
        var status=new Intake.SourceStatus();List<String> logs=new ArrayList<>();
        IOException failure=assertThrows(IOException.class,scheduler::tick);status.failed(failure,logs::add);
        String remembered=status.error;
        ledger.fail=false;
        var skipped=scheduler.tick();assertEquals(SourceSweepScheduler.TickOutcome.SKIPPED,skipped);
        status.completed(skipped);assertEquals(remembered,status.error);
        status.failed(failure,logs::add);assertEquals(1,logs.size(),"skipped tick must not reset duplicate-error suppression");
        assertNull(ledger.committed);
        time.set(1_000_000_000L);
        var succeeded=scheduler.tick();assertEquals(SourceSweepScheduler.TickOutcome.SUCCEEDED,succeeded);
        status.completed(succeeded);assertNull(status.error);
        assertNotNull(ledger.committed);
    }
    @Test void restartUsesLedgerCursorAndTerminalRestartsFullSweep()throws Exception {
        Ledger ledger=new Ledger();List<String> cursors=new ArrayList<>(),logs=new ArrayList<>();AtomicLong time=new AtomicLong();
        JavaSourceIngestion.Discovery discovery=cp->{cursors.add(cp.token());return new JavaSourceIngestion.Page(List.of(),cp.token().isEmpty()?"full-v1:issues:1:0:":"",!cp.token().isEmpty());};
        var first=new SourceSweepScheduler(new JavaSourceIngestion(ledger,discovery,logs::add),time::get,1000);
        first.tick();first.tick();assertEquals(1,cursors.size());
        var restarted=new SourceSweepScheduler(new JavaSourceIngestion(ledger,discovery,logs::add),time::get,1000);
        restarted.tick();time.addAndGet(1_000_000_000L);restarted.tick();
        assertEquals(List.of("","full-v1:issues:1:0:",""),cursors);assertTrue(logs.isEmpty());
    }
    @Test void fullSweepFindsOldEditAndRestartDoesNotDuplicateLogs()throws Exception {
        Ledger ledger=new Ledger();List<String> logs=new ArrayList<>();AtomicLong time=new AtomicLong();
        var old=SourceObservation.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(GitHubSourcePagesTest.row(1,"Old","original",true)),true,GitHubSourcePagesTest.CLOCK.instant());
        var edited=SourceObservation.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(GitHubSourcePagesTest.row(1,"Old","edited",true)),true,GitHubSourcePagesTest.CLOCK.instant());
        var current=new java.util.concurrent.atomic.AtomicReference<>(old);
        JavaSourceIngestion.Discovery discovery=cp->new JavaSourceIngestion.Page(List.of(current.get()),"",true);
        new SourceSweepScheduler(new JavaSourceIngestion(ledger,discovery,logs::add),time::get,1000).tick();
        new SourceSweepScheduler(new JavaSourceIngestion(ledger,discovery,logs::add),time::get,1000).tick();
        assertEquals(List.of("Proposal #1 added"),logs);
        current.set(edited);
        new SourceSweepScheduler(new JavaSourceIngestion(ledger,discovery,logs::add),time::get,1000).tick();
        assertEquals(List.of("Proposal #1 added","Proposal #1 updated"),logs);
        assertEquals(2,ledger.stored.get(1L).version());
    }
    @Test void failedRpcDoesNotMoveCursorOrLogAndBacksOff()throws Exception {
        Ledger ledger=new Ledger();ledger.fail=true;AtomicLong time=new AtomicLong();List<String> logs=new ArrayList<>();
        var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,cp->new JavaSourceIngestion.Page(List.of(),"full-v1:issues:1:0:",false),logs::add),time::get,1000);
        assertThrows(IOException.class,scheduler::tick);assertEquals("",ledger.cp.token());assertTrue(logs.isEmpty());
        ledger.fail=false;scheduler.tick();assertEquals("",ledger.cp.token());time.addAndGet(1_000_000_000L);scheduler.tick();assertEquals("full-v1:issues:1:0:",ledger.cp.token());
    }
}
