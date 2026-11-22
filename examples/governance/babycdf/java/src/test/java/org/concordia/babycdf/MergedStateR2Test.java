package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class MergedStateR2Test {
 @Test void documentHashBindsPreviewAndSchemaOneLoads(@TempDir Path root)throws Exception{
  var state=new HashSet<String>();var api=new AdvisoryTest().api();var clauses=SupplementalAssessmentTest.rule(1).clauses();
  for(String hash:List.of("a".repeat(64),"b".repeat(64))){var rule=new Reviewer.EffectiveRule("cid","architecture-review",1,clauses,new RuleDocument.Ref("rule.json",null,hash),null);var result=Reviewer.review(()->List.of(rule),api);assertEquals(hash,result.path("reviews").get(0).path("rule_document_sha256").asText());Reviewer.attachPreviews(result,state);assertFalse(result.path("ballot_previews").isEmpty());}
  Path file=root.resolve("state.json");Advisory.saveState(file,state);assertEquals(state,Advisory.loadState(file));
 }
 @Test void presentationFailureDoesNotPublishState(@TempDir Path root)throws Exception{
  var state=new HashSet<String>();var rule=new Reviewer.EffectiveRule("cid","architecture-review",1,SupplementalAssessmentTest.rule(1).clauses());var original=System.err;var broken=new PrintStream(new OutputStream(){public void write(int b)throws IOException{throw new IOException("injected stderr failure");}});
  try{System.setErr(broken);var demo=new DemoLog("Reviewer",true,false,broken);assertThrows(IOException.class,()->Reviewer.runCycle(()->List.of(rule),new AdvisoryTest().api(),new HashSet<>(),demo,null,state,root.resolve("state.json")));}finally{System.setErr(original);}
  assertTrue(state.isEmpty());assertFalse(Files.exists(root.resolve("state.json")));
 }
 @Test void outputFailureDoesNotPublishPreviewState(@TempDir Path root)throws Exception{
  var state=new HashSet<String>();var rule=new Reviewer.EffectiveRule("cid","architecture-review",1,SupplementalAssessmentTest.rule(1).clauses());var original=System.out;
  try{System.setOut(new PrintStream(new OutputStream(){public void write(int b)throws IOException{throw new IOException("injected output failure");}}));assertThrows(IOException.class,()->Reviewer.runCycle(()->List.of(rule),new AdvisoryTest().api(),new HashSet<>(),null,null,state,root.resolve("state.json")));}finally{System.setOut(original);}
  assertTrue(state.isEmpty());assertFalse(Files.exists(root.resolve("state.json")));var restart=Reviewer.review(()->List.of(rule),new AdvisoryTest().api());Reviewer.attachPreviews(restart,state);assertFalse(restart.path("ballot_previews").isEmpty());
 }
}
