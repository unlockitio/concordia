package org.concordia.babycdf;
import java.util.*;

/** Shared code-only presence predicate and eligibility derivation. */
final class Presence {
    static boolean malformed(Advisory.Clause clause) {
        return ClauseKind.recognized(clause.kind())==null || "PRESENCE_REQUIRED".equals(clause.kind())
            &&(clause.indicators().isEmpty()||clause.indicators().stream().anyMatch(s->s==null||s.isBlank()));
    }
    static String indicator(Advisory.Clause clause,EvidenceBundle bundle,String single) {
        List<String> texts=bundle==null?List.of(single==null?"":single):bundle.files().stream().map(EvidenceBundle.File::content).toList();
        for(String text:texts) {
            String normalized=RuleVerdict.normalize(text);
            for(String phrase:clause.indicators()) {
                String needle=RuleVerdict.normalize(phrase);
                var matcher=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(normalized);
                if(!needle.isEmpty()&&matcher.find()) return normalized.substring(matcher.start(),matcher.end());
            }
        } return null;
    }
    record Resolution(RuleVerdict.Verdict verdict,String reason,List<String> quotes) {}
    static Resolution resolve(Advisory.Clause clause,EvidenceBundle bundle,String single,boolean eligible,RuleVerdict.Verdict llm,List<String> verifiedQuotes) {
        String match;
        try { match=indicator(clause,bundle,single); }
        catch(RuntimeException invalid) { return new Resolution(RuleVerdict.Verdict.INCONCLUSIVE,"presence_evaluation_failed",List.of()); }
        if(match!=null) return new Resolution(llm==RuleVerdict.Verdict.FAIL?RuleVerdict.Verdict.INCONCLUSIVE:RuleVerdict.Verdict.PASS,llm==RuleVerdict.Verdict.FAIL?"conflict":"source=code_indicator",List.of(match));
        if(!eligible) return new Resolution(RuleVerdict.Verdict.INCONCLUSIVE,"incomplete_evidence",List.of());
        if(!verifiedQuotes.isEmpty()) return new Resolution(RuleVerdict.Verdict.INCONCLUSIVE,"semantic_support_unconfirmed",verifiedQuotes);
        return new Resolution(RuleVerdict.Verdict.FAIL,"absent",List.of());
    }
    static boolean eligible(com.fasterxml.jackson.databind.node.ObjectNode item,EvidenceBundle bundle) {
        return bundle!=null&&"proposal_pr".equals(item.path("kind").asText())&&item.path("pinned").asBoolean()
            &&item.has("supplemental_valid")&&item.path("supplemental_valid").asBoolean()
            &&item.path("head_consistent").asBoolean()&&bundle.completeness()
            &&bundle.verified(item.path("head_commit").asText());
    }
}
