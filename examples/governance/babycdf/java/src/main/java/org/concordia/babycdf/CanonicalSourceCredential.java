package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;import java.util.*;import java.io.IOException;
/** Concrete template arguments only, never Credential views or agenda snapshots. */
record CanonicalSourceCredential(String cid,SourceObservation observation,long version,String fingerprint) {
 static CanonicalSourceCredential decode(EventOuterClass.CreatedEvent event,String opener)throws IOException {
  CredentialDecodeFailure.Reason stage=CredentialDecodeFailure.Reason.labels;
  try{
   var r=event.getCreateArguments();
   // Ledger API normal form elides the declared trailing Optional None.
   if(r.getFieldsCount()==5&&r.getFieldsList().stream().map(ValueOuterClass.RecordField::getLabel).toList().equals(List.of("opener","repo","observation","fingerprint","version")))r=r.toBuilder().addFields(ValueOuterClass.RecordField.newBuilder().setLabel("previousDigest").setValue(ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()))).build();
   if(r.getFieldsList().stream().anyMatch(f->f.getLabel().isBlank()))throw new IOException();
   stage=CredentialDecodeFailure.Reason.schema;SourceCatalogueCodec.fields(r,Set.of("opener","repo","observation","fingerprint","version","previousDigest"));
   stage=CredentialDecodeFailure.Reason.identity;var party=IntakeLedger.field(r,"opener");if(!party.hasParty()||!party.getParty().equals(opener)||!event.getSignatoriesList().equals(List.of(opener))||!IntakeLedger.text(r,"repo").equals(ConcordiaDemoScope.REPO)||event.getContractId().isBlank())throw new IOException();
   stage=CredentialDecodeFailure.Reason.observation;var value=IntakeLedger.field(r,"observation");if(!value.hasRecord())throw new IOException();var observation=SourceCatalogueCodec.observation(value.getRecord());
   stage=CredentialDecodeFailure.Reason.lineage;long version=IntakeLedger.number(r,"version");var previous=IntakeLedger.field(r,"previousDigest");if(!previous.hasOptional())throw new IOException();String prior=null;if(previous.getOptional().hasValue()){var p=previous.getOptional().getValue();if(!p.hasText()||!p.getText().matches("[0-9a-f]{64}"))throw new IOException();prior=p.getText();}if(version<1||(version==1)!=(prior==null))throw new IOException();
   stage=CredentialDecodeFailure.Reason.fingerprint;String fingerprint=IntakeLedger.text(r,"fingerprint");if(!SourceCatalogueDigest.digest(value.getRecord(),version,prior).equals(fingerprint))throw new IOException();return new CanonicalSourceCredential(event.getContractId(),observation,version,fingerprint);
  }catch(IOException|RuntimeException invalid){throw new CredentialDecodeFailure(stage);}
 }
}
