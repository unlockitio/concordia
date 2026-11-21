package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class AdvisoryLedgerRevisionTest {
 @Test void ledgerVersionsAndFailuresAreVisible()throws Exception{
  var fixture=Advisory.fixture(Advisory.RULE,Advisory.RULE_SHA256,2);var state=new HashSet<String>();var api=new AdvisoryTest().api();
  for(int version:List.of(1,2)){
   String hash=RuleDocument.sha(Files.readAllBytes(Path.of("../rules/rule-v"+version+".json")));
   var rule=new Reviewer.EffectiveRule("cid"+version,"architecture-review",version,fixture.clauses(),new RuleDocument.Ref("rules/rule-v"+version+".json",null,hash),null);
   var result=Advisory.pollWithLedger(api,fixture,state,null,()->List.of(rule));assertFalse(result.path("reviews").isEmpty());
   for(var review:result.path("reviews")){
    assertEquals(version,review.path("rule_version").asInt());assertEquals(version,review.path("rule_version_evaluated").asInt());assertEquals(hash,review.path("rule_document_sha256").asText());assertEquals(Advisory.RULE_SHA256,review.path("rule_source_sha256").asText());assertTrue(review.path("rule_authority").asText().contains("cid"+version));
    var ballot=Advisory.prepareBallotSafely(review,null,null,false,null);
    assertEquals("proposal_pr".equals(review.path("kind").asText())?"preview_submission_blocked":"blocked_unpinned_evidence",ballot.path("status").asText());
    assertNotEquals("blocked_invalid_review",ballot.path("status").asText());
   }
  }
  assertEquals("blocked_invalid_review",Advisory.prepareBallotSafely(Advisory.JSON.createObjectNode(),null,null,false,null).path("status").asText());
  var broken=new Reviewer.EffectiveRule("broken","architecture-review",3,List.of(),new RuleDocument.Ref("missing",null,"0".repeat(64)),"rule_unverified");
  var result=Advisory.pollWithLedger(api,fixture,new HashSet<>(),null,()->List.of(broken));
  for(var review:result.path("reviews")){assertEquals("rule_document",review.path("findings").get(0).path("clause_id").asText());assertEquals("insufficient_evidence",review.path("findings").get(0).path("status").asText());}
 }
}
