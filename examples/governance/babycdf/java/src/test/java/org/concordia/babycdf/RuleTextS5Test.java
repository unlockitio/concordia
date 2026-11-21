package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RuleTextS5Test {
 @Test void groundedRulesAndExpectedOutcomes()throws Exception{
  String text=Files.readString(Path.of("../demo/fixtures/pr184-concordia.md"));assertEquals(28024,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);assertEquals("f591787a772848e4537aba5d8ba02be1764647da",EvidenceBundle.blob(text));
  var bundle=new EvidenceBundle("url","c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521",List.of(new EvidenceBundle.File("proposals/concordia.md",EvidenceBundle.blob(text),28024,text)),null,null,true);
  var issue=Advisory.JSON.readTree(Files.readString(Path.of("../demo/fixtures/issue540.json")));String issueText=issue.path("title").asText()+"\n"+issue.path("body").asText();
  for(int version:List.of(1,2)){
   var rules=RuleDocument.parse(Files.readAllBytes(Path.of("../rules/rule-v"+version+".json")));assertTrue(rules.verified());List<RuleVerdict.Clause> candidates=new ArrayList<>();
   for(var clause:rules.clauses()){
    var finding=ClauseCheck.evaluate(clause,text,true,bundle,true);
    assertEquals(version==1?"fail":"pass",finding.path("status").asText(),clause.id());
    assertEquals("insufficient_evidence",ClauseCheck.evaluate(clause,issueText,false).path("status").asText());
    if(version==1&&clause.kind().equals("PRESENCE_REQUIRED")){assertNull(Presence.indicator(clause,bundle,null));assertEquals("absent",finding.path("detail").asText());candidates.add(new RuleVerdict.Clause(clause.id(),RuleVerdict.Verdict.INCONCLUSIVE,"absent",List.of()));}
    else {String quote=version==1?clause.conflict():clause.expected();assertTrue(text.contains(quote));if(version==2)assertFalse(RuleVerdict.normalize(issueText).toLowerCase(Locale.ROOT).contains(RuleVerdict.normalize(quote).toLowerCase(Locale.ROOT)));candidates.add(new RuleVerdict.Clause(clause.id(),version==1?RuleVerdict.Verdict.FAIL:RuleVerdict.Verdict.PASS,"verified",List.of(quote)));}
   }
   assertEquals(version==1?RuleVerdict.Verdict.FAIL:RuleVerdict.Verdict.PASS,RuleVerdict.resolve(new RuleVerdict.Exact(RuleVerdict.aggregate(candidates),candidates,null),rules.clauses(),bundle,true).overall());
  }
  assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,RuleVerdict.parse("{\"verdicts\":[{\"id\":\"c\",\"verdict\":\"PASS\",\"rationale\":\"invented\",\"quotes\":[\"invented unsupported quotation\"]}]}",List.of("c"),text).exact().overall());
 }
}
