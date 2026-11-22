package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentsLiveTest {
 @Test void offlineIssueAndMode()throws Exception{
  var offline=new OfflineFile(Path.of("../demo/fixtures/pr184-concordia.md"));assertEquals(540,offline.get(Advisory.PREFIX+"/issues/540").path("number").asInt());assertEquals(540,offline.get(Advisory.query("repo:"+Advisory.REPO+" is:issue \"Concordia #184\"")).path("items").get(0).path("number").asInt());assertEquals("offline_no_evidence",assertThrows(GitHubFetchFailure.class,()->offline.get("unknown")).getMessage());var logs=new ByteArrayOutputStream();offline.logMode("Reviewer",new PrintStream(logs));assertTrue(logs.toString().contains("mode=offline_fixture"));
 }
 @Test void livePinMatrix()throws Exception{
  var offline=new OfflineFile(Path.of("../demo/fixtures/pr184-concordia.md"));
  for(String mode:List.of("match","head","blob")){
   var api=new Advisory.GitHub(path->{var node=offline.get(path).deepCopy();if(mode.equals("head")&&path.endsWith("/pulls/184"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("head")).put("sha","a".repeat(40));if(mode.equals("blob")&&path.contains("/pulls/184/files?"))((com.fasterxml.jackson.databind.node.ObjectNode)node.get(0)).put("sha","b".repeat(40));return node;});
   var result=Advisory.collect(api,false);boolean pinned=java.util.stream.StreamSupport.stream(result.path("inputs").spliterator(),false).anyMatch(n->n.path("kind").asText().equals("proposal_pr")&&n.path("pinned").asBoolean());assertEquals(mode.equals("match"),pinned,mode);
  }
 }
 @Test void liveMismatchedPinFailsClosed()throws Exception{
  var delegate=new AdvisoryTest().api();var api=new Advisory.GitHub(delegate);var result=Advisory.collect(api,false);assertTrue(result.path("errors").toString().contains("proposal pinned identity mismatch"));assertTrue(result.path("inputs").findValuesAsText("kind").stream().noneMatch("proposal_pr"::equals));
 }
}
