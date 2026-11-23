package org.concordia.babycdf;
import java.util.function.Consumer;
/** Presentation state only; never controls source attempts or queue transitions. */
final class IntakeLogState {
 enum Pause { unavailable_evidence,malformed_evidence }
 private String last;
 void paused(Pause category,Consumer<String> log) {
  String state="paused:"+category.name();if(state.equals(last))return;
  log.accept(category==Pause.malformed_evidence?"Automatic source rounds paused: malformed source evidence":"Automatic source rounds paused: invalid or unavailable source evidence");last=state;
 }
 void observed(String state,Consumer<String> log) {
  if(state.equals(last))return;
  log.accept(state);last=state;
 }
}
