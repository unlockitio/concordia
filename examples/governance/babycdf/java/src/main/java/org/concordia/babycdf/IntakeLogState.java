package org.concordia.babycdf;
import java.util.function.Consumer;
/** Presentation state only; never controls source attempts or queue transitions. */
final class IntakeLogState {
 void decodeFailed(CredentialDecodeFailure failure,Consumer<String> log){String state="credential_decode_"+failure.reason.name();if(!state.equals(last)){log.accept("Automatic source rounds paused: "+state);last=state;}}
 enum Pause { unavailable_evidence,malformed_evidence,credential_decode,rule_selection,evidence_lookup,inventory_scope_limit }
 private String last;
 void paused(Pause category,Consumer<String> log) {
  String state="paused:"+category.name();if(state.equals(last))return;
  if(category==Pause.credential_decode||category==Pause.rule_selection||category==Pause.evidence_lookup||category==Pause.inventory_scope_limit){log.accept("Automatic source rounds paused: "+category.name());last=state;return;}
  log.accept(category==Pause.malformed_evidence?"Automatic source rounds paused: malformed source evidence":"Automatic source rounds paused: invalid or unavailable source evidence");last=state;
 }
 void observed(String state,Consumer<String> log) {
  if(state.equals(last))return;
  log.accept(state);last=state;
 }
}
