package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import com.daml.ledger.api.v2.*;
import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;import static org.concordia.babycdf.SourceCatalogueCodec.*;
class SourceOpenerMilestoneAdapterTest {
 @TempDir Path root;
 @Test void productionMilestoneCommandPinsExactPredecessorAndEvidence()throws Exception {
  String pkg="a".repeat(64),party="opener";var sample=SourceCatalogueCodecTest.sample();var obs=observation(sample);String fingerprint=SourceCatalogueDigest.digest(obs.getRecord());Instant now=sample.observedAt();
  var credential=event("credential",record("opener",ValueOuterClass.Value.newBuilder().setParty(party).build(),"repo",text(REPOSITORY),"observation",obs,"version",integer(1),"fingerprint",text(fingerprint),"previousDigest",SourceOpenerLedger.none()));
  var rows=new HashMap<String,List<EventOuterClass.CreatedEvent>>();rows.put("SourceObservationCredential",List.of(credential));rows.put("AgendaGovernor",List.of(PrimaryRightsFixtures.governor(event("gov",record("opener",ValueOuterClass.Value.newBuilder().setParty(party).build())),"test-cdf",List.of("primary-a","primary-b","primary-c","primary-d","primary-e"))));
  rows.put("RegisteredVotingRight",PrimaryRightsFixtures.rights(party,"test-cdf",List.of("primary-a","primary-b","primary-c","primary-d","primary-e"),now));
  var sent=new ArrayList<CommandServiceOuterClass.SubmitAndWaitRequest>();var rpc=new SourceOpenerLedger.Rpc(){public Instant now(){return now;}public List<EventOuterClass.CreatedEvent> active(String m,String e){return rows.getOrDefault(e,List.of());}public void submit(CommandServiceOuterClass.SubmitAndWaitRequest r){sent.add(r);}};
  var proof=new IntakeLedger(party,pkg,pkg,"gov","user",root.toRealPath(),r->{},(p,m,e)->List.of(),false);
  try(var ledger=new SourceOpenerLedger(party,pkg,"gov","user",rpc,proof)) {
   String pin=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of("b".repeat(40),sample.bodySha256(),123,now.toString()));
   var b=new SourceOpenerQueue.Binding(REPOSITORY,7,"milestone","credential",1,fingerprint,sample.bodySha256(),"round",now,123,pin,"","3");ledger.open(b,120);
   var ex=sent.getFirst().getCommands().getCommands(0).getExercise();assertEquals("ProposeAgenda",ex.getChoice());var args=ex.getChoiceArgument().getRecord();var source=IntakeLedger.field(args,"source").getOptional().getValue().getRecord();
   assertEquals("credential",IntakeLedger.field(source,"credentialCid").getContractId());assertEquals(1,IntakeLedger.number(source,"expectedVersion"));assertEquals(fingerprint,IntakeLedger.text(source,"expectedFingerprint"));
   var delivery=IntakeLedger.field(source,"pin").getVariant();assertEquals("DeliveryPin",delivery.getConstructor());assertEquals(sample.bodySha256(),IntakeLedger.text(delivery.getValue().getRecord(),"issueDigest"));assertEquals(123,IntakeLedger.number(delivery.getValue().getRecord(),"voteCommentId"));
   rows.put("SourceRoundHead",List.of(event("head",record("number",integer(7),"latest",text("missing")))));assertThrows(IOException.class,()->ledger.validate(b));assertEquals(1,sent.size());
  }
 }
 static EventOuterClass.CreatedEvent event(String cid,ValueOuterClass.Value r){return EventOuterClass.CreatedEvent.newBuilder().setContractId(cid).addSignatories("opener").setCreateArguments(r.getRecord()).build();}
}
