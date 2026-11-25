package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import com.daml.ledger.api.v2.*;
import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;import static org.concordia.babycdf.SourceCatalogueCodec.*;
class SourceOpenerAmendmentAdapterTest {
 @TempDir Path root;
 @Test void exactProductionProposeAmendmentBindsBothPinsAndManifest()throws Exception {
  String pkg="a".repeat(64),repo=SourceEvidenceCollector.REPO,head="b".repeat(40),blob="c".repeat(40);Instant now=Instant.parse("2026-11-23T00:00:00Z");
  var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((u,e)->{String body=u.getPath().endsWith("files")?"[{\"filename\":\"proposals/p\",\"status\":\"added\",\"sha\":\""+blob+"\"}]":u.getPath().contains("trees")?"{\"truncated\":false,\"tree\":[{\"path\":\"proposals/p\",\"type\":\"blob\",\"sha\":\""+blob+"\"}]}":"{\"body\":\"body\",\"created_at\":\"2026-01-01T00:00:00Z\",\"head\":{\"sha\":\""+head+"\"}}";return new SourceEvidenceFetch.Response(200,body.getBytes(),"","",Map.of());},Clock.fixed(now,ZoneOffset.UTC)));
  var file=new SourceEvidence.FilePin("proposals/p","added",blob,"");var original=new SourceEvidence.Proposal(184,head,List.of(file),true,"ready");var amending=new SourceEvidence.Proposal(475,head,List.of(file),true,"ready");
  String manifest="{\"scheme\":\"babycdf-amendments/v1\",\"amendments\":[{\"repo\":\""+repo+"\",\"evidenceId\":123,\"original\":{\"number\":184,\"pin\":\""+VerifiedAmendment.pin(original)+"\"},\"amending\":{\"number\":475,\"pin\":\""+VerifiedAmendment.pin(amending)+"\"},\"declaration\":\"amends:184\"}]}";
  var verified=VerifiedAmendment.parse(manifest.getBytes(),Map.of(184L,original,475L,amending),Set.of(123L)).getFirst();var ready=collector.readyAmendment(verified,now).orElseThrow();
  var observation=new SourceObservation(475,SourceObservation.Kind.PROPOSAL,"https://github.com/"+repo+"/pull/475","title",SourceObservation.hash("body"),"open",now,now,null,"",false,SourceObservation.hash("body"),false);var wire=observation(observation);String fingerprint=SourceCatalogueDigest.digest(wire.getRecord());
  var credential=event("credential",record("opener",party(),"repo",text(repo),"observation",wire,"version",integer(1),"fingerprint",text(fingerprint),"previousDigest",SourceOpenerLedger.none()));
  var originalBinding=new SourceOpenerQueue.Binding(repo,184,"proposal","old",1,"a".repeat(64),"a".repeat(64),"original-round",now,184,ready.pinJson(),"","");var originalPin=SourceOpenerLedger.pin(originalBinding);
  var snapshot=record("observation",record("number",integer(184)),"pin",originalPin);
  var receipt=event("original-receipt",record("opener",party(),"roundId",text("original-round"),"sourceSnapshot",some(snapshot)));
  var rows=new HashMap<String,List<EventOuterClass.CreatedEvent>>();rows.put("SourceObservationCredential",List.of(credential));rows.put("AgendaRoundReceipt",List.of(receipt));rows.put("AgendaGovernor",List.of(PrimaryRightsFixtures.governor(event("gov",record("opener",party())),"test-cdf",List.of("primary-a","primary-b","primary-c","primary-d","primary-e"))));rows.put("RegisteredVotingRight",PrimaryRightsFixtures.rights("opener","test-cdf",List.of("primary-a","primary-b","primary-c","primary-d","primary-e"),now));
  var sent=new ArrayList<CommandServiceOuterClass.SubmitAndWaitRequest>();var rpc=new SourceOpenerLedger.Rpc(){public Instant now(){return now.plusNanos(789);}public List<EventOuterClass.CreatedEvent> active(String m,String e){return rows.getOrDefault(e,List.of());}public void submit(CommandServiceOuterClass.SubmitAndWaitRequest r)throws IOException{sent.add(r);throw new IOException("lost ACK");}};
  var proof=new IntakeLedger("opener",pkg,pkg,"gov","user",root.toRealPath(),r->{},(p,m,e)->List.of(),false);
  try(var ledger=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proof)){var binding=ledger.bindAmendment(ready,verified);
   try(var queue=new SourceOpenerQueue(root.toRealPath(),ledger)){queue.commitAmendment(binding.roundId(),ledger.commitment(binding.roundId()));queue.enqueue(ready,binding);assertThrows(IOException.class,queue::tick);assertEquals(SourceOpenerQueue.State.submission_uncertain,queue.snapshot().getFirst().state());}
   assertEquals(1,sent.size());var ex=sent.getFirst().getCommands().getCommands(0).getExercise();assertEquals("ProposeAmendment",ex.getChoice());var args=ex.getChoiceArgument().getRecord();var relation=IntakeLedger.field(args,"relation").getRecord();
   assertEquals(184,IntakeLedger.number(relation,"originalNumber"));assertEquals(475,IntakeLedger.number(relation,"amendingNumber"));assertEquals("original-round",IntakeLedger.text(relation,"originalRound"));assertEquals(verified.manifestDigest(),IntakeLedger.text(relation,"manifestDigest"));assertEquals(123,IntakeLedger.number(relation,"evidenceId"));assertEquals(originalPin,IntakeLedger.field(relation,"originalPin"));assertEquals(SourceOpenerLedger.pin(binding),IntakeLedger.field(relation,"amendingPin"));assertEquals("credential",IntakeLedger.field(IntakeLedger.field(args,"source").getRecord(),"credentialCid").getContractId());
   var copied=record("sourceCid",ValueOuterClass.Value.newBuilder().setContractId("credential").build(),"opener",party(),"observation",wire,"fingerprint",text(fingerprint),"version",integer(1),"previousDigest",SourceOpenerLedger.none(),"evidenceDigest",text(observation.bodySha256()),"pin",SourceOpenerLedger.pin(binding),"roundId",text(binding.roundId()),"originalProposal",integer(475),"previousRound",SourceOpenerLedger.none());
   var openedReceipt=event("new-receipt",record("opener",party(),"proposer",party(),"roundId",text(binding.roundId()),"baseVersion",integer(0),"agenda",SourceOpenerLedger.agenda(binding),"actionCid",ValueOuterClass.Value.newBuilder().setContractId("action").build(),"sourceSnapshot",some(copied)));
   var action=event("action",record("opener",party(),"baseVersion",integer(0),"agenda",SourceOpenerLedger.agenda(binding),"sourceSnapshot",some(copied)));
   var relationEvent=event("relation",record("opener",party(),"roundId",text(binding.roundId()),"relation",ValueOuterClass.Value.newBuilder().setRecord(relation).build()));
   rows.put("AgendaRoundReceipt",List.of(receipt,openedReceipt));rows.put("AgendaAction",List.of(action));rows.put("AgendaProposal",List.of(event("proposal",record("opener",party(),"proposer",IntakeLedger.field(args,"proposer"),"actionCid",ValueOuterClass.Value.newBuilder().setContractId("action").build(),"subject",record("url",text("https://github.com/"+binding.repo()+"/pull/"+binding.number()),"body",text(binding.fingerprint())),"sourceSnapshot",some(copied),"roundId",text(binding.roundId()),"closesAt",IntakeLedger.field(args,"closesAt")))));rows.put("SourceAmendmentRound",List.of(relationEvent));
   var actualProof=new IntakeLedger("opener",pkg,pkg,"gov","user",root.toRealPath(),r->fail("recovery must not submit"),(p,m,e)->rows.getOrDefault(e,List.of()),false);
   var history=new ArrayList<EventOuterClass.CreatedEvent>();actualProof.historicalActions(id->List.copyOf(history));
   try(var restarted=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,actualProof)){
    try(var recoveredQueue=new SourceOpenerQueue(root.toRealPath(),restarted)){
     restarted.restoreCommitment(binding.roundId(),recoveredQueue.commitments().get(binding.roundId()));assertEquals("proven-open",recoveredQueue.tick());rows.put("AgendaAction",List.of());history.add(action);assertEquals("proven-open",recoveredQueue.tick());assertEquals(1,sent.size());
    }
    for(String field:List.of("originalPin","amendingPin","manifestDigest","evidenceId","originalRound","originalNumber","amendingNumber","repo")){
     var bad=relation.toBuilder();for(int i=0;i<bad.getFieldsCount();i++)if(bad.getFields(i).getLabel().equals(field)){var value=switch(field){case "originalPin","amendingPin"->record("wrong",text("pin"));case "evidenceId","originalNumber","amendingNumber"->integer(999);default->text("wrong");};bad.setFields(i,bad.getFields(i).toBuilder().setValue(value));}
     rows.put("SourceAmendmentRound",List.of(event("mutated",record("opener",party(),"roundId",text(binding.roundId()),"relation",ValueOuterClass.Value.newBuilder().setRecord(bad).build()))));assertThrows(IOException.class,()->restarted.proof(binding),field);
    }
    rows.put("SourceAmendmentRound",List.of());assertThrows(IOException.class,()->restarted.proof(binding));rows.put("SourceAmendmentRound",List.of(relationEvent,relationEvent));assertThrows(IOException.class,()->restarted.proof(binding));assertEquals(1,sent.size());
   }
  }
 }
 static ValueOuterClass.Value party(){return ValueOuterClass.Value.newBuilder().setParty("opener").build();}
 static ValueOuterClass.Value some(ValueOuterClass.Value v){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(v)).build();}
 static EventOuterClass.CreatedEvent event(String cid,ValueOuterClass.Value r){return EventOuterClass.CreatedEvent.newBuilder().setContractId(cid).addSignatories("opener").setCreateArguments(r.getRecord()).build();}
}
