package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import com.daml.ledger.api.v2.*;import java.nio.file.*;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.concordia.babycdf.SourceCatalogueCodec.*;
class SourceOpenerLedgerTest {
 @TempDir Path path;
 @Test void productionAdapterBuildsExact120SecondGovernorCommandAndRejectsArchivedCid()throws Exception {
  String party="opener",pkg="a".repeat(64);var sample=SourceCatalogueCodecTest.sample();
  var proposal=new SourceObservation(184,SourceObservation.Kind.PROPOSAL,"https://github.com/canton-foundation/canton-dev-fund/pull/184",sample.title(),sample.bodySha256(),sample.state(),sample.updatedAt(),sample.observedAt(),null,"",false,sample.bodySha256(),false);
  var observation=observation(proposal);String fingerprint=SourceCatalogueDigest.digest(observation.getRecord());
  var credential=EventOuterClass.CreatedEvent.newBuilder().setContractId("credential").addSignatories(party).setCreateArguments(record("opener",ValueOuterClass.Value.newBuilder().setParty(party).build(),"repo",text(REPOSITORY),"observation",observation,"version",integer(1),"fingerprint",text(fingerprint),"previousDigest",SourceOpenerLedger.none()).getRecord()).build();
  var governor=EventOuterClass.CreatedEvent.newBuilder().setContractId("gov").addSignatories(party).build();var rows=new HashMap<String,List<EventOuterClass.CreatedEvent>>();rows.put("SourceObservationCredential",List.of(credential));rows.put("AgendaGovernor",List.of(governor));
  var sent=new ArrayList<CommandServiceOuterClass.SubmitAndWaitRequest>();Instant now=Instant.parse("2026-11-23T00:00:00Z");
  var rpc=new SourceOpenerLedger.Rpc(){public Instant now(){return now;}public List<EventOuterClass.CreatedEvent> active(String m,String e){return rows.getOrDefault(e,List.of());}public void submit(CommandServiceOuterClass.SubmitAndWaitRequest r){sent.add(r);}};
  var proof=new IntakeLedger(party,pkg,pkg,"gov","user",path.toRealPath(),r->{},(p,m,e)->List.of(),false);
  try(var ledger=new SourceOpenerLedger(party,pkg,"gov","user",rpc,proof)){
   String pin="[\""+"b".repeat(40)+"\",[[\"proposals/x\",\"added\",\""+"c".repeat(40)+"\",\"\"]]]";
   var binding=new SourceOpenerQueue.Binding(REPOSITORY,184,"proposal","credential",1,fingerprint,proposal.bodySha256(),"round",now,184,pin,"","");
   ledger.open(binding,120);assertEquals(1,sent.size());var command=sent.getFirst().getCommands();assertEquals(List.of(party),command.getActAsList());
   var ex=command.getCommands(0).getExercise();assertEquals("ProposeAgenda",ex.getChoice());assertEquals(now.plusSeconds(120),timestamp(IntakeLedger.field(ex.getChoiceArgument().getRecord(),"closesAt")));
   rows.put("SourceObservationCredential",List.of());assertThrows(java.io.IOException.class,()->ledger.validate(binding));
  }
 }
}
