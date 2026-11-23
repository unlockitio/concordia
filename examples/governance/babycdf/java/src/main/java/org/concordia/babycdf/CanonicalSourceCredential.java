package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;import java.util.*;import java.io.IOException;
/** Concrete template arguments only, never Credential views or agenda snapshots. */
record CanonicalSourceCredential(String cid,SourceObservation observation,long version,String fingerprint) {
 static CanonicalSourceCredential decode(EventOuterClass.CreatedEvent event,String opener)throws IOException {
  try{var r=event.getCreateArguments();SourceCatalogueCodec.fields(r,Set.of("opener","repo","observation","fingerprint","version","previousDigest"));var party=IntakeLedger.field(r,"opener");if(!party.hasParty()||!party.getParty().equals(opener)||!event.getSignatoriesList().equals(List.of(opener))||!IntakeLedger.text(r,"repo").equals(ConcordiaDemoScope.REPO)||event.getContractId().isBlank())throw new IOException();var value=IntakeLedger.field(r,"observation");if(!value.hasRecord())throw new IOException();var observation=SourceCatalogueCodec.observation(value.getRecord());long version=IntakeLedger.number(r,"version");var previous=IntakeLedger.field(r,"previousDigest");if(!previous.hasOptional())throw new IOException();String prior=null;if(previous.getOptional().hasValue()){var p=previous.getOptional().getValue();if(!p.hasText()||!p.getText().matches("[0-9a-f]{64}"))throw new IOException();prior=p.getText();}String fingerprint=IntakeLedger.text(r,"fingerprint");if(version<1||(version==1)!=(prior==null)||!SourceCatalogueDigest.digest(value.getRecord(),version,prior).equals(fingerprint))throw new IOException();return new CanonicalSourceCredential(event.getContractId(),observation,version,fingerprint);
  }catch(IOException|RuntimeException invalid){throw new IntegrationStageFailure(IntegrationStageFailure.Stage.credential_decode);}
 }
}
