// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import com.daml.ledger.api.v2.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.concordia.babycdf.SourceCatalogueCodec.*;
class SourceAgendaBindingTest {
 @TempDir Path journal;
 static ValueOuterClass.Value party(){return ValueOuterClass.Value.newBuilder().setParty("opener").build();}
 static ValueOuterClass.Value cid(String id){return ValueOuterClass.Value.newBuilder().setContractId(id).build();}
 static ValueOuterClass.Value none(){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()).build();}
 static ValueOuterClass.Value some(ValueOuterClass.Value v){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(v)).build();}
 static ValueOuterClass.Value pin(){return ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder().setConstructor("BlockedPin").setValue(record("reason",text("missing delivery")))).build();}
 static String fingerprint(){try{return SourceCatalogueDigest.digest(observation(SourceCatalogueCodecTest.sample()).getRecord());}catch(IOException e){throw new RuntimeException(e);}}
 static ValueOuterClass.Value source(){return record("credentialCid",cid("credential"),"expectedFingerprint",text(fingerprint()),"expectedVersion",integer(1),"pin",pin(),"originalProposal",integer(184),"previousRound",none());}
 static ValueOuterClass.Value agenda(){return IntakeLedger.agenda(new IntakeSource("arbitrary",IntakeSource.AgendaConstructor.Milestone,"milestone","https://github.com/canton-foundation/canton-dev-fund/issues/900","a".repeat(64),IntakeSource.Evidence.CURATED_UNVERIFIED));}
 static ValueOuterClass.Value snapshot(){try{return record("sourceCid",cid("credential"),"opener",party(),"observation",observation(SourceCatalogueCodecTest.sample()),"evidenceDigest",text(SourceCatalogueCodecTest.sample().bodySha256()),"fingerprint",text(fingerprint()),"version",integer(1),"previousDigest",none(),"pin",pin(),"originalProposal",integer(184),"previousRound",none(),"roundId",text("round"));}catch(IOException e){throw new RuntimeException(e);}}
 static EventOuterClass.CreatedEvent event(String id,ValueOuterClass.Value value){return EventOuterClass.CreatedEvent.newBuilder().setContractId(id).addSignatories("opener").setCreateArguments(value.getRecord()).build();}
 @Test void durableReceiptRequiresExactBindingAndLinkedAction() throws Exception {
  var request=new Intake.Request("request","opener","run","round","arbitrary",60,0);
  var plan=IntakePlan.bound(new IntakeSource("arbitrary",IntakeSource.AgendaConstructor.Milestone,"milestone",SourceCatalogueCodecTest.sample().url(),fingerprint(),IntakeSource.Evidence.CURATED_UNVERIFIED),"round",agenda(),source());
  var receipt=record("opener",party(),"proposer",party(),"roundId",text("round"),"baseVersion",integer(0),"agenda",agenda(),"actionCid",cid("action"),"sourceSnapshot",some(snapshot()));
  var action=record("opener",party(),"agenda",agenda(),"baseVersion",integer(0),"sourceSnapshot",some(snapshot()));
  var rows=new HashMap<String,List<EventOuterClass.CreatedEvent>>();rows.put("AgendaRoundReceipt",List.of(event("receipt",receipt)));rows.put("AgendaAction",List.of(event("action",action)));
  var adapter=new IntakeLedger("opener","a".repeat(64),"b".repeat(64),"gov","user",journal.toRealPath(),r->fail("proof never submits"),(p,m,e)->rows.getOrDefault(e,List.of()),false);
  assertTrue(adapter.proof(request,plan)); // No proposal and no local journal required.
  rows.put("AgendaAction",List.of());assertThrows(IOException.class,()->adapter.proof(request,plan));
  rows.put("AgendaAction",List.of(event("foreign",action)));assertThrows(IOException.class,()->adapter.proof(request,plan));
  rows.put("AgendaAction",List.of(event("action",action)));rows.put("AgendaRoundReceipt",List.of(event("one",receipt),event("two",receipt)));
  assertThrows(IOException.class,()->adapter.proof(request,plan));
 }
}
