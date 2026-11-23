// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.concordia.babycdf.SourceCatalogueCodec.*;
import static org.concordia.babycdf.SourceCatalogueLedgerTest.*;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;

/** Explicit pre/post fixtures, independent of Server's item-effect generation. */
class SourceCatalogueAcceptanceTest {
    @TempDir Path directory;
    SourceCatalogueLedger adapter(Server s) throws Exception {return new SourceCatalogueLedger(PARTY,PKG,"user",directory.toRealPath(),s);}
    static EventOuterClass.CreatedEvent item(String cid,SourceObservation o,long version,String previous) throws Exception {
        var optional=ValueOuterClass.Optional.newBuilder();if(previous!=null) optional.setValue(text(previous));
        var value=observation(o);
        return created(cid,"SourceObservationCredential",record("opener",ValueOuterClass.Value.newBuilder().setParty(PARTY).build(),
                "repo",text(REPOSITORY),"observation",value,"fingerprint",text(SourceCatalogueDigest.digest(value.getRecord(),version,previous)),
                "version",integer(version),"previousDigest",ValueOuterClass.Value.newBuilder().setOptional(optional).build()).getRecord());
    }
    static SourceObservation changed(SourceObservation o) {
        return new SourceObservation(o.number(),o.kind(),o.url(),o.title(),SourceObservation.hash("independent revised body"),o.state(),
                o.updatedAt().plusSeconds(1),o.observedAt(),o.parentPR(),o.linkEvidence(),o.merged(),SourceObservation.hash("independent revised body"),o.truncated());
    }
    @Test void createIntentCanResumeButLostReplyReadbackNeverResends() throws Exception {
        var s=new Server(){boolean first=true;public TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest r) throws IOException {
            if(first){first=false;sends++;last=r;throw new IOException("before effect");}
            assertEquals(last,r);return super.submit(r);
        }};
        assertThrows(IOException.class,()->adapter(s).checkpoint());assertEquals("initial",adapter(s).checkpoint().cid());assertEquals(2,s.sends);
        assertEquals("initial",adapter(s).checkpoint().cid());assertEquals(2,s.sends);
    }
    @Test void persistenceNoMarkerRecoversButPartialMarkerRemainsUncertain() throws Exception {
        var s=new Server();var ledger=adapter(s);ledger.checkpoint();
        ledger.intentWriter=(p,n,b)->{throw new IOException("simulated disk full");};
        var engine=new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(),"",true),x->{});
        assertThrows(JavaSourceIngestion.PreTransmission.class,engine::cycle);assertEquals(1,s.sends);
        ledger.intentWriter=IntakeLedger::recordSourceMarker;engine.cycle();assertEquals(2,s.sends);
        var cp=ledger.checkpoint();String command="babycdf-source-v2-"+"c".repeat(64);
        ledger.intentWriter=(p,n,b)->{Files.write(p.resolve(n),new byte[]{1});throw new IOException("partial write");};
        var failure=assertThrows(IOException.class,()->ledger.commit(new JavaSourceIngestion.Batch(cp,
                new JavaSourceIngestion.Page(List.of(),"",true),command)));
        assertFalse(failure instanceof JavaSourceIngestion.PreTransmission);assertEquals(2,s.sends);
    }
    @Test void independentVersionThreeToFourAndWrongPreviousDigest() throws Exception {
        var s=new Server();var ledger=adapter(s);var cp=ledger.checkpoint();var old=SourceCatalogueCodecTest.sample();
        String oldDigest=SourceCatalogueDigest.digest(observation(old).getRecord(),3,"0".repeat(64));
        var prior=item("old-item",old,3,"0".repeat(64));s.active.add(prior);
        var next=changed(old);var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(next),"",true),
                "babycdf-source-v2-"+"8".repeat(64));ledger.commit(batch);
        var tx=s.history.get(1);var updated=item("new-item",next,4,oldDigest);
        s.history.set(1,tx.toBuilder().setEvents(0,EventOuterClass.Event.newBuilder().setCreated(updated)).build());
        assertTrue(ledger.proof(batch).isPresent());
        for(long version:List.of(3L,5L)) {
            var wrongVersion=item("new-item",next,version,oldDigest);
            s.history.set(1,tx.toBuilder().setEvents(0,EventOuterClass.Event.newBuilder().setCreated(wrongVersion)).build());
            assertTrue(ledger.proof(batch).isEmpty());
        }
        var wrong=item("new-item",next,4,"1".repeat(64));
        s.history.set(1,tx.toBuilder().setEvents(0,EventOuterClass.Event.newBuilder().setCreated(wrong)).build());
        assertTrue(ledger.proof(batch).isEmpty());
    }
    @Test void unchangedRaceHasNoCreatedItemAndStillProves() throws Exception {
        var s=new Server();var ledger=adapter(s);var cp=ledger.checkpoint();var o=SourceCatalogueCodecTest.sample();
        s.active.add(item("existing",o,3,"0".repeat(64)));
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(o),"",true),"babycdf-source-v2-"+"9".repeat(64));
        ledger.commit(batch);
        assertTrue(ledger.proof(batch).isEmpty(),"unchanged observation must not create an item");
        s.history.set(1,s.history.get(1).toBuilder().removeEvents(0).build());
        assertTrue(ledger.proof(batch).isPresent());
    }
    @Test void newItemVersionTwoWithValidShapeCannotProve() throws Exception {
        var s=new Server();var ledger=adapter(s);var cp=ledger.checkpoint();var o=SourceCatalogueCodecTest.sample();
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(o),"",true),"babycdf-source-v2-"+"d".repeat(64));
        ledger.commit(batch);var tx=s.history.get(1);
        var wrong=item("new",o,2,"0".repeat(64));
        s.history.set(1,tx.toBuilder().setEvents(0,EventOuterClass.Event.newBuilder().setCreated(wrong)).build());
        assertTrue(ledger.proof(batch).isEmpty());
    }
    @Test void concurrentCreateCollisionResolvesAuthoritativeKeyCid() throws Exception {
        var s=new Server(){public TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest r) {
            sends++;active.add(created("winner-cid","SourceCheckpoint",checkpoint(0,"",false,0,"",0)));offset++;
            throw io.grpc.Status.ALREADY_EXISTS.asRuntimeException();
        }};
        var one=adapter(s);var two=adapter(s);
        assertEquals("winner-cid",one.checkpoint().cid());assertEquals(one.checkpoint(),two.checkpoint());assertEquals(1,s.sends);
    }
    @Test void receiptCorruptionTableCannotSkipOrProve() throws Exception {
        var s=new Server();var ledger=adapter(s);var cp=ledger.checkpoint();String command="babycdf-source-v2-"+"a".repeat(64);
        var batch=new JavaSourceIngestion.Batch(cp,new JavaSourceIngestion.Page(List.of(),"",true),command);
        ledger.commit(batch);assertTrue(ledger.proof(batch).isPresent());var path=directory.resolve(command+".receipt");
        String good=Files.readString(path),binding=good.split("[|]")[1];
        for(String bad:List.of("",good+"|tail",good+"\n","CONFIRMED|"+binding+"|0|update",
                "CONFIRMED|"+binding+"|9999999999999999999|update","REJECTED|"+binding+"|UNAVAILABLE|-",
                "CONFIRMED|"+"0".repeat(64)+"|3|update","CONFIRMED|"+binding+"|3|"+"a".repeat(256))) {
            Files.writeString(path,bad);assertThrows(IOException.class,()->adapter(s).checkpoint());
            assertThrows(IOException.class,()->ledger.proof(batch));
        }
        Files.writeString(path,"CONFIRMED|"+binding+"|3|wrong-update");assertTrue(ledger.proof(batch).isEmpty());
        Files.writeString(path,"CONFIRMED|"+binding+"|4|update-2");assertTrue(ledger.proof(batch).isEmpty());
    }
    @Test void largeItemAcsCannotBreakCheckpointOrReturnFalseZero() throws Exception {
        var s=new Server();var ledger=adapter(s);var cp=ledger.checkpoint();
        var item=item("many",SourceCatalogueCodecTest.sample(),1,null);
        for(int i=0;i<10001;i++) s.active.add(item);
        assertEquals(cp,ledger.checkpoint());assertThrows(IOException.class,()->ledger.items(Set.of(7L)));
    }
    @Test void enginePreflightFailureRecoversWithoutUncertainWedge() throws Exception {
        var s=new Server(){int ends;boolean armed;
            public long end(){if(armed && ++ends==4) throw io.grpc.Status.UNAVAILABLE.asRuntimeException();return super.end();}};
        var ledger=adapter(s);ledger.checkpoint();s.armed=true;
        var logs=new ArrayList<String>();var engine=new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(
                List.of(SourceCatalogueCodecTest.sample()),"",true),logs::add);
        assertThrows(JavaSourceIngestion.PreTransmission.class,engine::cycle);assertEquals(1,s.sends);
        engine.cycle();assertEquals(2,s.sends);assertEquals(List.of("Milestone #7 added"),logs);
    }
    @Test void uncertainCycleNeverResendsBeforeProofAppears() throws Exception {
        var s=new Server();var ledger=adapter(s);ledger.checkpoint();s.reject=true;
        var engine=new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(),"",true),x->fail("unproven label"));
        assertThrows(IOException.class,engine::cycle);assertThrows(IOException.class,engine::cycle);assertEquals(2,s.sends);
    }
}
