package org.concordia.babycdf;
import com.daml.ledger.api.v2.ValueOuterClass;import java.util.List;
/** Expand only the declared trailing Optional None in the exact SDK record order. */
final class SourceSnapshotWire {
 static ValueOuterClass.Record expand(ValueOuterClass.Record record){
  if(record.getFieldsList().stream().map(ValueOuterClass.RecordField::getLabel).toList().equals(List.of("sourceCid","opener","observation","fingerprint","version","previousDigest","evidenceDigest","pin","roundId","originalProposal")))return record.toBuilder().addFields(ValueOuterClass.RecordField.newBuilder().setLabel("previousRound").setValue(SourceOpenerLedger.none())).build();
  return record;
 }
}
