// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit fake HTTP and ledger RPC: no real GitHub or participant acceptance claim. */
class SourceCatalogueHundredsTest {
    @TempDir Path directory;
    @Test void paginatedThreeHundredRecordsHaveLedgerProofAndRestartSuppressesDuplicateLabels() throws Exception {
        var server=new SourceCatalogueLedgerTest.Server() {
            @Override public com.daml.ledger.api.v2.TransactionOuterClass.Transaction submit(
                    com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request) throws java.io.IOException {
                if(request.getCommands().getCommands(0).hasCreate())return super.submit(request);
                var before=active.stream().filter(e->e.getTemplateId().getEntityName().equals("SourceCheckpoint")).findFirst().orElseThrow();
                var record=before.getCreateArguments();
                boolean progress=IntakeLedger.field(record,"inProgress").getBool();
                long generation=IntakeLedger.number(record,"generation")+(progress?0:1);
                long high=IntakeLedger.number(record,"highWater");
                var args=request.getCommands().getCommands(0).getExercise().getChoiceArgument().getRecord();
                var digests=new ArrayList<String>();
                for(var observation:IntakeLedger.field(args,"items").getList().getElementsList()) {
                    high=Math.max(high,IntakeLedger.number(observation.getRecord(),"number"));
                    digests.add(SourceCatalogueDigest.text(SourceCatalogueDigest.digest(observation.getRecord())));
                }
                boolean complete=IntakeLedger.field(args,"complete").getBool();
                String scan=SourceObservation.hash("("+SourceCatalogueDigest.text(progress?IntakeLedger.text(record,"scanFingerprint"):"")+",["+String.join(",",digests)+"])");
                var priorItems=new ArrayList<>(active.stream().filter(e->e.getTemplateId().getEntityName().equals("SourceObservationCredential")).toList());
                var tx=super.submit(request);
                var corrected=tx.toBuilder();
                for(int i=0;i<tx.getEventsCount();i++) {
                    var event=tx.getEvents(i);
                    if(!event.hasCreated() || !event.getCreated().getTemplateId().getEntityName().equals("SourceObservationCredential"))continue;
                    var created=event.getCreated();
                    var observation=SourceCatalogueCodec.observation(IntakeLedger.field(created.getCreateArguments(),"observation").getRecord());
                    for(var prior:priorItems) {
                        var previous=SourceCatalogueCodec.observation(IntakeLedger.field(prior.getCreateArguments(),"observation").getRecord());
                        if(previous.number()!=observation.number())continue;
                        com.daml.ledger.api.v2.EventOuterClass.CreatedEvent revised;
                        try { revised=SourceCatalogueAcceptanceTest.item("revised-"+observation.number(),observation,
                                IntakeLedger.number(prior.getCreateArguments(),"version")+1,
                                IntakeLedger.text(prior.getCreateArguments(),"fingerprint")); }
                        catch(Exception failure){throw new java.io.IOException(failure);}
                        active.remove(created);active.remove(prior);active.add(revised);
                        corrected.setEvents(i,event.toBuilder().setCreated(revised));
                    }
                }
                for(int i=0;i<tx.getEventsCount();i++) {
                    var event=tx.getEvents(i);
                    if(event.hasCreated() && event.getCreated().getTemplateId().getEntityName().equals("SourceCheckpoint")) {
                        var old=event.getCreated();
                        var next=old.toBuilder().setCreateArguments(SourceCatalogueLedgerTest.checkpoint(generation,
                                IntakeLedger.text(args,"nextToken"),!complete,
                                complete?generation:IntakeLedger.number(record,"completedFullGeneration"),scan,high)).build();
                        active.remove(old);active.add(next);
                        corrected.setEvents(i,event.toBuilder().setCreated(next));
                    }
                }
                var result=corrected.build();history.set(history.size()-1,result);return result;
            }
        };
        var requests=new ArrayList<String>();
        var logs=new ArrayList<String>();
        var editOld=new java.util.concurrent.atomic.AtomicBoolean();
        var pages=new GitHubSourcePages((uri,timeout)->{
            boolean pulls=uri.getPath().endsWith("pulls");
            int page=Integer.parseInt(uri.getQuery().substring(uri.getQuery().lastIndexOf("page=")+5));
            requests.add((pulls?"pulls":"issues")+":"+page);
            var rows=new ArrayList<String>();
            for(int i=1;i<=50;i++) {
                long number=(pulls?0:150)+(page-1)*50+i;
                String row=GitHubSourcePagesTest.rowUnchecked(number,pulls?"Development grant "+number:
                        "Grant #1 - Milestone "+number+": integration delivery","Implementation evidence and acceptance criteria",pulls);
                if(editOld.get() && number==1)row=row.replace("Implementation evidence and acceptance criteria","Revised old proposal evidence")
                        .replace("2026-01-01T00:00:00Z","2026-01-02T00:00:00Z");
                rows.add(row);
            }
            return new GitHubDiscovery.Response(200,page<3?List.of(GitHubSourcePagesTest.nextLink(pulls?"pulls":"issues",page+1)):List.of(),
                    "["+String.join(",",rows)+"]");
        },GitHubSourcePagesTest.CLOCK);
        var path=directory.toRealPath();
        try(var ledger=new SourceCatalogueLedger("opener","a".repeat(64),"user",path,server)) {
            var engine=new JavaSourceIngestion(ledger,pages,logs::add);
            for(int i=0;i<6;i++) engine.cycle();
            var confirmed=new TreeMap<Long,JavaSourceIngestion.Stored>();
            for(long start=1;start<=300;start+=50) {
                var numbers=new TreeSet<Long>();for(long n=start;n<start+50;n++)numbers.add(n);
                confirmed.putAll(ledger.items(numbers));
            }
            assertEquals(300,confirmed.size());
            assertEquals(150,confirmed.values().stream().filter(s->s.observation().kind()==SourceObservation.Kind.PROPOSAL).count());
            assertEquals(150,confirmed.values().stream().filter(s->s.observation().kind()==SourceObservation.Kind.MILESTONE).count());
            assertEquals("",ledger.checkpoint().token());
            assertEquals(300,logs.size());
            assertEquals(300,new HashSet<>(logs).size());
            assertTrue(logs.contains("Proposal #1 added"));assertTrue(logs.contains("Milestone #300 added"));
        }
        assertEquals(List.of("pulls:1","pulls:2","pulls:3","issues:1","issues:2","issues:3"),requests);
        var snapshot=server.active.stream().filter(e->e.getTemplateId().getEntityName().equals("SourceObservationCredential"))
                .collect(java.util.stream.Collectors.toMap(e->e.getContractId(),e->e.getCreateArguments()));
        int previousSends=server.sends;
        try(var restarted=new SourceCatalogueLedger("opener","a".repeat(64),"user",path,server)) {
            assertEquals("",restarted.checkpoint().token());
            assertEquals(previousSends,server.sends,"checkpoint recovery must not replay RPCs");
            var engine=new JavaSourceIngestion(restarted,pages,logs::add);
            for(int i=0;i<6;i++)engine.cycle();
            assertEquals(300,logs.size(),"unchanged full sweep must not invent added/updated labels");
            assertEquals(snapshot,server.active.stream().filter(e->e.getTemplateId().getEntityName().equals("SourceObservationCredential"))
                    .collect(java.util.stream.Collectors.toMap(e->e.getContractId(),e->e.getCreateArguments())),
                    "unchanged sweep must preserve every SourceItem CID and version");
            editOld.set(true);
            for(int i=0;i<6;i++)engine.cycle();
            assertEquals(301,logs.size());assertEquals("Proposal #1 updated",logs.getLast());
            var revised=restarted.items(Set.of(1L)).get(1L);
            assertEquals(2,revised.version());
            assertEquals(SourceObservation.hash("Revised old proposal evidence"),revised.observation().bodySha256());
            assertEquals(java.time.Instant.parse("2026-01-02T00:00:00Z"),revised.observation().updatedAt());
            var finalItems=server.active.stream().filter(e->e.getTemplateId().getEntityName().equals("SourceObservationCredential")).toList();
            assertEquals(300,finalItems.size());
            assertEquals(299,finalItems.stream().filter(e->snapshot.containsKey(e.getContractId())
                    && snapshot.get(e.getContractId()).equals(e.getCreateArguments())).count(),
                    "only old proposal #1 below high-water 300 may change");
        }
    }
}
