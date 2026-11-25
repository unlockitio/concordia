package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneCommandPolicyTest {
 @Test void currentStandaloneCommandsOnly() {
  for(String command:new String[]{"/vote","/git-vote"}) {
   assertTrue(SourceEvidence.milestoneVoteCommand(command));
   assertTrue(SourceEvidence.milestoneVoteCommand("context\n"+command+"\n"));
   for(String body:new String[]{"> "+command,"```\n"+command+"\n```","~~~\n"+command+"\n~~~","    "+command,"\t"+command,"- "+command,"1. "+command,"please "+command,command+" now"})assertFalse(SourceEvidence.milestoneVoteCommand(body),body);
  }
  assertFalse(SourceEvidence.voteCommand("/git-vote"));
 }
}
