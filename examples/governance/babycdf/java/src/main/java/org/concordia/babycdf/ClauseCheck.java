// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic text evaluation of one ledger-supplied clause against pinned proposal text.
 *
 * <p><strong>This is literal phrase matching, not comprehension.</strong> A phrase is found when
 * it appears in the text after case folding and whitespace normalization. No semantic, inferential
 * or LLM-assisted understanding is performed or claimed; LLM-assisted evaluation remains planned.
 * The criterion, the required phrases and the forbidden phrases are all data read from the active
 * {@code EffectiveRule} on the ledger, so changing the voted rule changes the verdict.
 */
public final class ClauseCheck {
    private ClauseCheck() { }

    /** Status values, mirroring the on-ledger ClauseStatus vocabulary. */
    public static final String PASS = "pass";
    public static final String FAIL = "fail";
    public static final String INSUFFICIENT = "insufficient_evidence";

    /**
     * Collapses whitespace and folds case, so a phrase spanning a line break still matches.
     * Markdown is wrapped arbitrarily, which would otherwise cause spurious failures.
     */
    static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    static boolean contains(String haystack, String needle) {
        String phrase = normalize(needle);
        return !phrase.isEmpty() && normalize(haystack).contains(phrase);
    }

    /**
     * Evaluates one clause.
     *
     * @param pinnedText the verified pinned proposal text, or null/blank when no pinned evidence
     *                   is available; that yields {@link #INSUFFICIENT} rather than a verdict.
     */
    public static ObjectNode evaluate(Advisory.Clause clause, String pinnedText, boolean pinned) {
        return evaluate(clause,pinnedText,pinned,false);
    }
    public static ObjectNode evaluate(Advisory.Clause clause,String pinnedText,boolean pinned,boolean complete) {
        return evaluate(clause,pinnedText,pinned,null,false);
    }
    static ObjectNode evaluate(Advisory.Clause clause,String pinnedText,boolean pinned,EvidenceBundle bundle,boolean eligible) {
        ObjectNode result = Advisory.JSON.createObjectNode()
                .put("clause_id", clause.id())
                .put("criterion", clause.criterion() == null ? "" : clause.criterion())
                .put("evaluation", "deterministic_text_match_case_insensitive_whitespace_normalized");
        ArrayNode required = result.putArray("required_phrases");
        clause.requiredPhrases().forEach(required::add);
        ArrayNode forbidden = result.putArray("forbidden_phrases");
        clause.forbiddenPhrases().forEach(forbidden::add);

        ClauseKind kind=ClauseKind.recognized(clause.kind());
        if(kind==null) return result.put("status",INSUFFICIENT).put("detail","unknown_clause_kind");
        if(kind==ClauseKind.PRESENCE_REQUIRED) {
            if(Presence.malformed(clause)) return result.put("status",INSUFFICIENT).put("detail","malformed_clause");
            var resolution=Presence.resolve(clause,eligible?bundle:null,pinned?pinnedText:null,eligible,null,List.of());
            return result.put("status",resolution.verdict()==RuleVerdict.Verdict.PASS?PASS:resolution.verdict()==RuleVerdict.Verdict.FAIL?FAIL:INSUFFICIENT).put("detail",resolution.reason());
        }
        if (!pinned || pinnedText == null || pinnedText.isBlank()) {
            return result.put("status", INSUFFICIENT)
                    .put("source", "no_pinned_text")
                    .put("detail", "no verified pinned proposal text to evaluate");
        }
        result.put("source", "verified_proposal_blob");

        // A forbidden phrase is a contradiction and outranks any satisfied requirement.
        for (String phrase : clause.forbiddenPhrases()) {
            if (contains(pinnedText, phrase))
                return result.put("status", FAIL)
                        .put("detail", "forbidden text present: \"" + phrase + "\"");
        }
        // With no stated requirement there is nothing to evidence either way.
        if (clause.requiredPhrases().isEmpty())
            return result.put("status", INSUFFICIENT)
                    .put("detail", "clause states no required phrase to check");

        ArrayNode missing = Advisory.JSON.createArrayNode();
        for (String phrase : clause.requiredPhrases())
            if (!contains(pinnedText, phrase)) missing.add(phrase);
        if (!missing.isEmpty()) {
            StringBuilder detail = new StringBuilder("required text absent: ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) detail.append(", ");
                detail.append('"').append(missing.get(i).asText()).append('"');
            }
            result.set("missing_phrases", missing);
            return result.put("status", FAIL).put("detail", detail.toString());
        }
        return result.put("status", PASS)
                .put("detail", "all " + clause.requiredPhrases().size()
                        + " required phrase(s) present and no forbidden text found");
    }

    /** Convenience for a whole clause list, in ledger order. */
    public static ArrayNode evaluateAll(List<Advisory.Clause> clauses, String pinnedText, boolean pinned) {
        ArrayNode findings = Advisory.JSON.createArrayNode();
        for (Advisory.Clause clause : clauses) findings.add(evaluate(clause, pinnedText, pinned));
        return findings;
    }
}
