// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
class JavaSourceIngestionTest {
    static SourceObservation observation() throws Exception {
        return SourceObservation.parse(new ObjectMapper().readTree("""
            {"number":184,"title":"Real shaped proposal","body":"Public text","state":"open",
             "html_url":"https://github.com/canton-foundation/canton-dev-fund/pull/184",
             "updated_at":"2026-01-01T00:00:00Z","merged_at":null}
            """),true,Instant.parse("2026-01-02T00:00:00Z"));
    }
    static final class Ledger implements JavaSourceIngestion.Ledger {
        JavaSourceIngestion.Checkpoint checkpoint=new JavaSourceIngestion.Checkpoint("cid",0,"",false);
        Map<Long,JavaSourceIngestion.Stored> items=new HashMap<>();
        boolean fail,loseResponse,missingProof,throwProof,race,foreignProof; int calls,reads;
        JavaSourceIngestion.Batch submitted,proven;
        final List<JavaSourceIngestion.Batch> commits=new ArrayList<>();
        public JavaSourceIngestion.Checkpoint checkpoint(){
            if(race && ++reads>1) return new JavaSourceIngestion.Checkpoint("competitor",1,"",false);
            return checkpoint;
        }
        public Map<Long,JavaSourceIngestion.Stored> items(Set<Long> n){return Map.copyOf(items);}
        public void commit(JavaSourceIngestion.Batch b) throws IOException {
            calls++; submitted=b; if(fail) throw new IOException("lost response");
            commits.add(b);
            b.page().items().forEach(o->items.compute(o.number(),(n,old)->new JavaSourceIngestion.Stored(o,old==null?1:old.version()+1)));
            if(!foreignProof) proven=b;
            checkpoint=new JavaSourceIngestion.Checkpoint("after-"+b.commandId(),b.before().generation()+1,b.page().nextToken(),!b.page().complete());
            if(loseResponse) throw new IOException("committed but response lost");
        }
        public Optional<JavaSourceIngestion.Proof> proof(JavaSourceIngestion.Batch b) throws IOException {
            if(throwProof) throw new IOException("proof unavailable");
            return missingProof || proven==null?Optional.empty():Optional.of(new JavaSourceIngestion.Proof(proven));
        }
    }
    @Test void confirmedOnlyAndRestartUnchangedQuiet() throws Exception {
        var ledger=new Ledger(); var logs=new ArrayList<String>(); var o=observation();
        JavaSourceIngestion.Discovery source=c->new JavaSourceIngestion.Page(List.of(o),"",true);
        new JavaSourceIngestion(ledger,source,logs::add).cycle();
        assertEquals(List.of("Proposal #184 added"),logs);
        logs.clear();
        new JavaSourceIngestion(ledger,source,logs::add).cycle(); assertTrue(logs.isEmpty());
    }
    @Test void uncertainNeverBlindlyReplayedOrLogged() throws Exception {
        var ledger=new Ledger(); ledger.fail=true; var logs=new ArrayList<String>(); var o=observation();
        var engine=new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(o),"",true),logs::add);
        assertThrows(IOException.class,engine::cycle); assertThrows(IOException.class,engine::cycle);
        assertEquals(1,ledger.calls); assertTrue(logs.isEmpty());
    }
    static SourceObservation changed(SourceObservation o,long number,SourceObservation.Kind kind,String body,Instant updated,Instant receipt) {
        return new SourceObservation(number,kind,"https://github.com/canton-foundation/canton-dev-fund/"+(kind==SourceObservation.Kind.PROPOSAL?"pull/":"issues/")+number,
                o.title(),body,o.state(),updated,receipt,null,"",false,SourceObservation.hash(body),false);
    }
    @Test void mixedBatchOnlyWritesChangedAndNewInSortedOrder() throws Exception {
        var ledger=new Ledger(); var logs=new ArrayList<String>(); var o=observation();
        ledger.items.put(184L,new JavaSourceIngestion.Stored(o,7));
        var updated=changed(o,184,o.kind(),"changed",o.updatedAt().plusSeconds(1),o.observedAt());
        var unchanged=changed(o,185,o.kind(),"same",o.updatedAt(),o.observedAt());
        var staleUpdated=changed(o,186,o.kind(),"old update",o.updatedAt().minusSeconds(1),o.observedAt());
        var staleReceipt=changed(o,187,o.kind(),"old receipt",o.updatedAt(),o.observedAt().minusSeconds(1));
        for(long n:List.of(185L,186L,187L)) ledger.items.put(n,new JavaSourceIngestion.Stored(
                n==185?unchanged:changed(o,n,o.kind(),"current",o.updatedAt(),o.observedAt()),3));
        var added=changed(o,188,o.kind(),"new",o.updatedAt(),o.observedAt());
        var engine=new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(added,staleReceipt,unchanged,updated,staleUpdated),"cursor2",false),logs::add);
        engine.cycle();
        var batch=ledger.submitted;
        assertEquals(List.of(updated,added),batch.page().items());
        assertEquals(List.of(updated.fingerprint(),added.fingerprint()),batch.page().items().stream().map(SourceObservation::fingerprint).toList());
        assertEquals("cursor2",batch.page().nextToken()); assertFalse(batch.page().complete());
        assertEquals("cid",batch.before().cid()); assertEquals(0,batch.before().generation());
        assertEquals(8,ledger.items.get(184L).version()); assertEquals(3,ledger.items.get(186L).version());
        assertEquals(3,ledger.items.get(187L).version()); assertEquals(1,ledger.items.get(188L).version());
        assertEquals(List.of("Proposal #184 updated","Proposal #188 added"),logs);
    }
    @Test void olderUpdateOrReceiptPreservesStoredObservationWithoutItemWrites() throws Exception {
        var o=observation();
        for(boolean olderUpdate:List.of(false,true)) {
            var ledger=new Ledger(); var stored=new JavaSourceIngestion.Stored(o,12); ledger.items.put(184L,stored);
            var stale=changed(o,184,o.kind(),"stale",olderUpdate?o.updatedAt().minusSeconds(1):o.updatedAt(),
                    olderUpdate?o.observedAt():o.observedAt().minusSeconds(1));
            var logs=new ArrayList<String>();
            new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(stale),"",true),logs::add).cycle();
            assertTrue(ledger.submitted.page().items().isEmpty()); assertEquals(stored,ledger.items.get(184L)); assertTrue(logs.isEmpty());
        }
    }
    @Test void kindConflictRaceAndNonadvancingCursorNeverSubmit() throws Exception {
        var o=observation();
        for(int scenario=0;scenario<3;scenario++) {
            var ledger=new Ledger(); var logs=new ArrayList<String>();
            SourceObservation input=o;
            if(scenario==0) ledger.items.put(o.number(),new JavaSourceIngestion.Stored(changed(o,o.number(),SourceObservation.Kind.MILESTONE,"other",o.updatedAt(),o.observedAt()),1));
            if(scenario==1) ledger.race=true;
            if(scenario==2) ledger.checkpoint=new JavaSourceIngestion.Checkpoint("cid",1,"same",true);
            String token=scenario==2?"same":"next";
            var page=new JavaSourceIngestion.Page(List.of(input),token,false);
            var engine=new JavaSourceIngestion(ledger,c->page,logs::add);
            assertThrows(IOException.class,engine::cycle); assertEquals(0,ledger.calls); assertTrue(logs.isEmpty());
        }
    }
    @Test void committedWithoutProofOrProofExceptionNeverLogsOrReplays() throws Exception {
        var o=observation();
        for(boolean throwing:List.of(false,true)) {
            var ledger=new Ledger(); ledger.missingProof=!throwing; ledger.throwProof=throwing;
            var logs=new ArrayList<String>();
            var engine=new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(o),"",true),logs::add);
            assertThrows(IOException.class,engine::cycle); assertThrows(IOException.class,engine::cycle);
            assertEquals(1,ledger.calls); assertEquals(1,ledger.commits.size()); assertTrue(logs.isEmpty());
        }
    }
    @Test void lostCommitResponseWithExactProofLogsConfirmedChange() throws Exception {
        var ledger=new Ledger(); ledger.loseResponse=true; var logs=new ArrayList<String>(); var o=observation();
        new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(o),"",true),logs::add).cycle();
        assertEquals(1,ledger.calls); assertEquals(List.of("Proposal #184 added"),logs);
        assertEquals(ledger.submitted,ledger.proven);
    }
    @Test void mismatchedProofBindingsBlockLabels() throws Exception {
        var o=observation();
        for(int mismatch=0;mismatch<4;mismatch++) {
            var ledger=new Ledger(); ledger.foreignProof=true; var logs=new ArrayList<String>();
            var expectedPage=new JavaSourceIngestion.Page(List.of(o),"next",false);
            var before=ledger.checkpoint;
            var wrongBefore=mismatch==0?new JavaSourceIngestion.Checkpoint("wrong",9,"",false):before;
            var wrongPage=mismatch==1?new JavaSourceIngestion.Page(List.of(o),"wrong",false):
                    mismatch==2?new JavaSourceIngestion.Page(List.of(changed(o,184,o.kind(),"wrong",o.updatedAt(),o.observedAt())),"next",false):expectedPage;
            StringBuilder identity=new StringBuilder(before.cid()).append('|').append(before.generation()).append('|').append("next").append('|').append(false);
            identity.append('|').append(o.fingerprint());
            ledger.proven=new JavaSourceIngestion.Batch(wrongBefore,wrongPage,mismatch==3?"wrong":"babycdf-source-"+SourceObservation.hash(identity.toString()));
            var engine=new JavaSourceIngestion(ledger,c->expectedPage,logs::add);
            assertThrows(IOException.class,engine::cycle); assertTrue(logs.isEmpty()); assertEquals(1,ledger.calls);
        }
    }
    @Test void batchRejectsDuplicatesAndOversize() throws Exception {
        var o=observation();
        assertThrows(IllegalArgumentException.class,()->new JavaSourceIngestion.Page(List.of(o,o),"",true));
        assertThrows(IllegalArgumentException.class,()->new JavaSourceIngestion.Page(Collections.nCopies(51,o),"",true));
    }
    @Test void strictDiscoveryPreservesActualFieldsAndRejectsMalformedPage() throws Exception {
        String row="""
            {"number":184,"title":"Proposal","body":"Evidence","state":"closed",
             "html_url":"https://github.com/canton-foundation/canton-dev-fund/pull/184",
             "updated_at":"2026-01-01T00:00:00Z","merged_at":"2026-01-01T00:00:00Z"}
            """;
        var clock=java.time.Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"),java.time.ZoneOffset.UTC);
        var discovery=new GitHubDiscovery((uri,timeout)->new GitHubDiscovery.Response(200,List.of(),
                uri.getPath().endsWith("pulls")?"["+row+"]":"[]"),GitHubDiscovery.Limits.defaults(),clock,System::nanoTime);
        var report=discovery.discoverSources(); assertTrue(report.complete());
        assertEquals(1,report.observations().size()); assertTrue(report.observations().getFirst().merged());
        assertEquals("Evidence",report.observations().getFirst().body());
        var bad=new GitHubDiscovery((uri,timeout)->new GitHubDiscovery.Response(200,List.of(),
                "[{\"number\":184,\"title\":\"missing required fields\"}]"),GitHubDiscovery.Limits.defaults(),clock,System::nanoTime);
        assertFalse(bad.discoverSources().complete()); assertTrue(bad.discoverSources().observations().isEmpty());
    }
    @Test void truncationIsLabelledAndDigestIsPropertyOrderIndependent() throws Exception {
        var mapper=new ObjectMapper();
        var row=mapper.createObjectNode().put("number",184).put("title","Proposal")
                .put("body","x".repeat(40000)).put("state","open")
                .put("html_url","https://github.com/canton-foundation/canton-dev-fund/pull/184")
                .put("updated_at","2026-01-01T00:00:00Z").putNull("merged_at");
        var observation=SourceObservation.parse(row,true,Instant.parse("2026-01-02T00:00:00Z"));
        assertTrue(observation.truncated()); assertTrue(observation.body().contains("TRUNCATED"));
        assertTrue(observation.body().length()<=32768);
        var reordered=mapper.createObjectNode();
        var names=new ArrayList<String>(); row.fieldNames().forEachRemaining(names::add); Collections.reverse(names);
        names.forEach(n->reordered.set(n,row.get(n)));
        assertEquals(observation.fingerprint(),SourceObservation.parse(reordered,true,observation.observedAt()).fingerprint());
    }
    @Test void requiredFieldsAndReceiptIndependentFingerprint() throws Exception {
        assertThrows(IOException.class,()->SourceObservation.parse(new ObjectMapper().readTree("{\"number\":1,\"title\":\"x\"}"),true,Instant.now()));
        var o=observation();
        var later=new SourceObservation(o.number(),o.kind(),o.url(),o.title(),o.body(),o.state(),o.updatedAt(),
                o.observedAt().plusSeconds(10),o.parentPR(),o.linkEvidence(),o.merged(),o.observedDigest(),o.truncated());
        assertEquals(o.fingerprint(),later.fingerprint());
    }
}
