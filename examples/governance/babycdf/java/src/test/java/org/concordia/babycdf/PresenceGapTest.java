package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PresenceGapTest {
    static final String HEAD="a".repeat(40);
    Advisory.Clause clause(){return new Advisory.Clause("c2","a","support","b","negative","criterion",List.of(),List.of(),"PRESENCE_REQUIRED",List.of("security audit"));}
    EvidenceBundle bundle(String text,boolean complete){return new EvidenceBundle("https://example.invalid/pr",HEAD,List.of(new EvidenceBundle.File("other.md",EvidenceBundle.blob(text),text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,text)),null,null,complete);}
    @Test void sharedResolutionMatrix(){
        var c=clause();var support=bundle("A SECURITY\n AUDIT is planned",true);
        for(var verdict:List.of(RuleVerdict.Verdict.PASS,RuleVerdict.Verdict.INCONCLUSIVE)) assertEquals(RuleVerdict.Verdict.PASS,Presence.resolve(c,support,null,true,verdict,List.of()).verdict());
        assertEquals("conflict",Presence.resolve(c,support,null,true,RuleVerdict.Verdict.FAIL,List.of()).reason());
        var absent=bundle("unrelated verbatim statement",true);
        assertEquals("semantic_support_unconfirmed",Presence.resolve(c,absent,null,true,RuleVerdict.Verdict.PASS,List.of("unrelated verbatim statement")).reason());
        assertEquals("absent",Presence.resolve(c,absent,null,true,RuleVerdict.Verdict.INCONCLUSIVE,List.of()).reason());
        assertEquals("incomplete_evidence",Presence.resolve(c,absent,null,false,RuleVerdict.Verdict.PASS,List.of()).reason());
        assertEquals("insufficient_evidence",ClauseCheck.evaluate(c,"unrelated",true).path("status").asText());
        assertEquals("pass",ClauseCheck.evaluate(c,"unrelated proposal",true,support,true).path("status").asText());
        var omitted=new RuleVerdict.Exact(RuleVerdict.Verdict.INCONCLUSIVE,List.of(),null);
        assertEquals(RuleVerdict.Verdict.PASS,RuleVerdict.resolve(omitted,List.of(c),support,true).overall());
        System.out.println("c2=FAIL(absent)");
    }
    @Test void eligibilityMustBeDerived(){
        var b=bundle("security audit",true);
        var good=Advisory.JSON.createObjectNode().put("kind","proposal_pr").put("pinned",true).put("head_commit",HEAD).put("head_consistent",true).put("supplemental_valid",true);
        assertTrue(Presence.eligible(good,b));
        for(String key:List.of("supplemental_valid","head_commit","head_consistent","pinned")){var item=good.deepCopy();item.remove(key);item.put("absence_eligible",true);assertFalse(Presence.eligible(item,b),key);}
        var item=good.deepCopy().put("supplemental_valid",false);assertFalse(Presence.eligible(item,b));
        assertFalse(Presence.eligible(good.deepCopy().put("head_commit","b".repeat(40)),b));
        assertFalse(Presence.eligible(good.deepCopy().put("kind","milestone_issue"),b));
        assertFalse(Presence.eligible(good,bundle("security audit",false)));
        var tampered=new EvidenceBundle(b.url(),HEAD,List.of(new EvidenceBundle.File("other.md","0".repeat(40),14,"security audit")),null,null,true);
        assertFalse(Presence.eligible(good,tampered));
    }
}
