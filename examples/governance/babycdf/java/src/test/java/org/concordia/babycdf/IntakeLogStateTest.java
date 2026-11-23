package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IntakeLogStateTest {
 @Test void repeatedPauseSuppressedCategoryChangesAndFailureAfterObservedStateEmit() {
  var state=new IntakeLogState();var lines=new ArrayList<String>();
  state.paused(IntakeLogState.Pause.unavailable_evidence,lines::add);assertEquals(1,lines.size());
  for(int i=0;i<20;i++)state.paused(IntakeLogState.Pause.unavailable_evidence,lines::add);assertEquals(1,lines.size());
  state.paused(IntakeLogState.Pause.malformed_evidence,lines::add);assertEquals(2,lines.size());
  state.observed("idle",lines::add);state.observed("idle",lines::add);assertEquals(3,lines.size());
  state.paused(IntakeLogState.Pause.unavailable_evidence,lines::add);assertEquals(4,lines.size());
  state.observed("proven-open",lines::add);state.observed("active-round",lines::add);assertEquals(6,lines.size());
  assertTrue(lines.stream().noneMatch(s->s.contains("recovered")||s.contains("resolved")));
 }
 @Test void fakeLoopStillAttemptsEveryTickAtUnchangedInterval() {
  var state=new IntakeLogState();var lines=new ArrayList<String>();var times=new ArrayList<Long>();long clock=0;long interval=1000;int attempts=0;
  for(int i=0;i<8;i++){times.add(clock);attempts++;if(i==3)state.observed("idle",lines::add);else state.paused(IntakeLogState.Pause.unavailable_evidence,lines::add);clock+=interval;}
  assertEquals(8,attempts);assertEquals(List.of(0L,1000L,2000L,3000L,4000L,5000L,6000L,7000L),times);assertEquals(3,lines.size());
 }
}
