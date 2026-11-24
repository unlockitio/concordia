package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import com.daml.ledger.api.v2.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class CredentialDecodeDiagnosticTest {
 @Test void trailingNoneElisionMatchesObservedSdkWire()throws Exception {var full=DemoInventoryScaleTest.credential(184,true);var ordered=full.getCreateArguments().toBuilder().clearFields();for(String name:List.of("opener","repo","observation","fingerprint","version"))ordered.addFields(full.getCreateArguments().getFieldsList().stream().filter(f->f.getLabel().equals(name)).findFirst().orElseThrow());var compact=full.toBuilder().setCreateArguments(ordered).build();assertEquals(CanonicalSourceCredential.decode(full,"opener"),CanonicalSourceCredential.decode(compact,"opener"));var wrong=compact.toBuilder().setCreateArguments(compact.getCreateArguments().toBuilder().setFields(4,compact.getCreateArguments().getFields(4).toBuilder().setValue(ValueOuterClass.Value.newBuilder().setInt64(2)))).build();assertThrows(CredentialDecodeFailure.class,()->CanonicalSourceCredential.decode(wrong,"opener"));}
 @Test void fixedReasonsPreserveStrictnessAndChangeOnlyLogs()throws Exception {
  var event=DemoInventoryScaleTest.credential(184,true);assertNotNull(CanonicalSourceCredential.decode(event,"opener"));
  var variants=new LinkedHashMap<CredentialDecodeFailure.Reason,EventOuterClass.CreatedEvent>();
  var labels=event.getCreateArguments().toBuilder();for(int i=0;i<labels.getFieldsCount();i++)labels.setFields(i,labels.getFields(i).toBuilder().clearLabel());variants.put(CredentialDecodeFailure.Reason.labels,event.toBuilder().setCreateArguments(labels).build());
  variants.put(CredentialDecodeFailure.Reason.schema,event.toBuilder().setCreateArguments(event.getCreateArguments().toBuilder().addFields(ValueOuterClass.RecordField.newBuilder().setLabel("SECRET_SENTINEL").setValue(ValueOuterClass.Value.newBuilder().setText("SECRET_SENTINEL")))).build());
  variants.put(CredentialDecodeFailure.Reason.identity,event.toBuilder().clearSignatories().addSignatories("SECRET_SENTINEL").build());
  for(var field:List.of("observation","version","fingerprint")){var record=event.getCreateArguments().toBuilder();for(int i=0;i<record.getFieldsCount();i++)if(record.getFields(i).getLabel().equals(field))record.setFields(i,record.getFields(i).toBuilder().setValue(ValueOuterClass.Value.newBuilder().setText("SECRET_SENTINEL")));variants.put(field.equals("observation")?CredentialDecodeFailure.Reason.observation:field.equals("version")?CredentialDecodeFailure.Reason.lineage:CredentialDecodeFailure.Reason.fingerprint,event.toBuilder().setCreateArguments(record).build());}
  var state=new IntakeLogState();var logs=new ArrayList<String>();for(var entry:variants.entrySet()){var error=assertThrows(CredentialDecodeFailure.class,()->CanonicalSourceCredential.decode(entry.getValue(),"opener"));assertEquals(entry.getKey(),error.reason);assertNull(error.getCause());state.decodeFailed(error,logs::add);state.decodeFailed(error,logs::add);}
  assertEquals(6,logs.size());assertTrue(logs.stream().noneMatch(s->s.contains("SECRET_SENTINEL")));
 }
 @Test void productionCatalogueRequestsAlwaysAskForVerboseLabels()throws Exception {
  var adapterType=SourceCatalogueLedger.class;var method=adapterType.getDeclaredMethod("format",String[].class);method.setAccessible(true);
  // Exercise the actual request path through the existing offline RPC fixture.
  var server=new SourceCatalogueLedgerTest.Server(){public Iterator<StateServiceOuterClass.GetActiveContractsResponse> active(StateServiceOuterClass.GetActiveContractsRequest request){assertTrue(request.getEventFormat().getVerbose());return super.active(request);}};
  var test=new SourceCatalogueLedgerTest();test.directory=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(".").toRealPath(),"credential-wire-");try(var ledger=test.adapter(server)){ledger.checkpoint();ledger.items(Set.of(184L));}
  assertTrue(IntakeLedger.activeContractsRequest("opener","a".repeat(64),SourceOpenerLedger.RULE,"AgendaProposal",1).getEventFormat().getVerbose());
 }
}
