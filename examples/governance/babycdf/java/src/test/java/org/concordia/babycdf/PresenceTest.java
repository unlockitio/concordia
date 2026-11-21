package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class PresenceTest {
    Advisory.Clause rule(String kind) {return new Advisory.Clause("c1","a","supporting evidence","b","contradiction","criterion",List.of("supporting evidence"),List.of(),kind,kind.equals("PRESENCE_REQUIRED")?List.of("supporting evidence"):List.of());}
    EvidenceBundle bundle(boolean complete) {String text="supporting evidence";return new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f",EvidenceBundle.blob(text),text.length(),text)),null,null,complete);}
    RuleVerdict.Exact candidate(String verdict,String quote) {return RuleVerdict.parse("{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\""+verdict+"\",\"rationale\":\"candidate\",\"quotes\":[\""+quote+"\"]}]}",List.of("c1"),"supporting evidence").exact();}
    @Test void codeResolvesPresence() {
        var rules=List.of(rule("PRESENCE_REQUIRED"));
        assertEquals("source=code_indicator",RuleVerdict.resolve(candidate("FAIL","invented evidence"),rules,bundle(false)).verdicts().getFirst().rationale());
        var supported=RuleVerdict.resolve(candidate("PASS","invented evidence"),rules,bundle(true));assertEquals(RuleVerdict.Verdict.PASS,supported.overall());
        var conflict=RuleVerdict.resolve(candidate("FAIL","supporting evidence"),rules,bundle(true));assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,conflict.overall());assertEquals("conflict",conflict.verdicts().getFirst().rationale());
        assertEquals(RuleVerdict.Verdict.PASS,RuleVerdict.resolve(candidate("PASS","supporting evidence"),rules,bundle(true)).overall());
        assertEquals("malformed_clause",RuleVerdict.resolve(candidate("PASS","supporting evidence"),List.of(rule("UNKNOWN")),bundle(true)).verdicts().getFirst().rationale());
    }
    @Test void literalPresence() {
        var rule=rule("PRESENCE_REQUIRED");assertEquals("pass",ClauseCheck.evaluate(rule,"supporting evidence",true,true).path("status").asText());
        assertEquals("insufficient_evidence",ClauseCheck.evaluate(rule,"other text",true,true).path("status").asText());
        assertEquals("insufficient_evidence",ClauseCheck.evaluate(rule,"other text",true,false).path("status").asText());
        assertEquals("insufficient_evidence",ClauseCheck.evaluate(rule("UNKNOWN"),"supporting evidence",true,true).path("status").asText());
    }
}
