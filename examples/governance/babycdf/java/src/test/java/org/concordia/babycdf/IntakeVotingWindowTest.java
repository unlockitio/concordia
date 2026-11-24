// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class IntakeVotingWindowTest {
 @Test void defaultAndExplicitWholeUnits() {
  assertEquals(Duration.ofDays(21),SourceOpenerQueue.votingWindow(Map.of()));
  assertEquals(Duration.ofHours(2),SourceOpenerQueue.votingWindow(Map.of("INTAKE_VOTING_WINDOW","2h")));
  assertEquals(Duration.ofDays(7),SourceOpenerQueue.votingWindow(Map.of("INTAKE_VOTING_WINDOW","7d")));
  assertEquals(Duration.ofDays(365),SourceOpenerQueue.votingWindow(Map.of("INTAKE_VOTING_WINDOW","365d")));
 }
 @Test void invalidConstructorWindowDoesNotTouchJournal() throws Exception {
  var parent=java.nio.file.Files.createTempDirectory("intake-window-");
  var root=parent.resolve("absent");
  for(Duration value:new Duration[]{Duration.ZERO,Duration.ofHours(-1),Duration.ofMinutes(90),Duration.ofDays(366)})
   assertThrows(IllegalArgumentException.class,()->new SourceOpenerQueue(root,null,value,java.time.Clock.systemUTC()));
  assertFalse(java.nio.file.Files.exists(root));
 }
 @Test void invalidAndOverflowAreRejected() {
  for(String value:new String[]{"0h","-1d","1m","1.5h","","366d","8761h","9223372036854775807d","999999999999999999999999999999h"})
   assertThrows(IllegalArgumentException.class,()->SourceOpenerQueue.votingWindow(Map.of("INTAKE_VOTING_WINDOW",value)),value);
 }
}
