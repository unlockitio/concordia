package org.concordia.babycdf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClauseCheckTest {
    /**
     * Representative excerpts from the pinned PR #184 blob
     * f591787a772848e4537aba5d8ba02be1764647da. Line wrapping is deliberate: the real markdown
     * wraps, and normalization must still match a phrase that spans a break. The text says
     * "auditable contract state" but never commits to an independent security audit.
     */
    static final String PROPOSAL = """
            ## Specification

            ### 1. Objective

            CAP is designed to sit above existing Canton asset and
            settlement infrastructure.

            #### Architectural Notes

            - atomic downstream action execution where the required authority has been collected
            - auditable contract state and deterministic resolution logic

            ## Acceptance Criteria

            The Tech & Ops Committee will evaluate completion based on:

            - deliverables completed as specified for each milestone
            - documentation sufficient for another team to understand, evaluate, and extend CAP
            """;

    private Advisory.Clause clause(String id, String criterion, List<String> required, List<String> forbidden) {
        return new Advisory.Clause(id, "anchor", "expected", "conflict-anchor", "conflict",
                criterion, required, forbidden);
    }

    @Test void requiredPhrasePresentPasses() {
        ObjectNode result = ClauseCheck.evaluate(
                clause("c", "must compose", List.of("auditable contract state"), List.of()), PROPOSAL, true);
        assertEquals(ClauseCheck.PASS, result.path("status").asText());
        assertEquals("must compose", result.path("criterion").asText());
        assertEquals("verified_proposal_blob", result.path("source").asText());
    }

    @Test void requiredPhraseMissingFailsAndNamesIt() {
        ObjectNode result = ClauseCheck.evaluate(
                clause("c", "must audit", List.of("independent security audit"), List.of()), PROPOSAL, true);
        assertEquals(ClauseCheck.FAIL, result.path("status").asText());
        assertTrue(result.path("detail").asText().contains("independent security audit"),
                result.path("detail").asText());
        assertEquals("independent security audit", result.path("missing_phrases").get(0).asText());
    }

    @Test void forbiddenPhraseFailsEvenWhenRequirementIsMet() {
        ObjectNode result = ClauseCheck.evaluate(
                clause("c", "no contradiction", List.of("auditable contract state"),
                        List.of("deterministic resolution logic")), PROPOSAL, true);
        assertEquals(ClauseCheck.FAIL, result.path("status").asText());
        assertTrue(result.path("detail").asText().contains("forbidden text present"),
                result.path("detail").asText());
    }

    @Test void matchingIsCaseInsensitiveAndWhitespaceNormalized() {
        // Phrase spans a line break in PROPOSAL and differs in case and spacing.
        ObjectNode wrapped = ClauseCheck.evaluate(
                clause("c", "", List.of("CAP IS   designed to sit above existing Canton asset and settlement infrastructure"),
                        List.of()), PROPOSAL, true);
        assertEquals(ClauseCheck.PASS, wrapped.path("status").asText());
        assertEquals("", ClauseCheck.normalize(null));
        assertEquals("a b c", ClauseCheck.normalize("  a\n\tb   c  "));
    }

    @Test void noPinnedTextIsInsufficientNotFailure() {
        Advisory.Clause c = clause("c", "x", List.of("anything"), List.of());
        assertEquals(ClauseCheck.INSUFFICIENT, ClauseCheck.evaluate(c, PROPOSAL, false).path("status").asText());
        assertEquals(ClauseCheck.INSUFFICIENT, ClauseCheck.evaluate(c, null, true).path("status").asText());
        assertEquals(ClauseCheck.INSUFFICIENT, ClauseCheck.evaluate(c, "   ", true).path("status").asText());
        // A clause with nothing to check cannot be evidence of approval either.
        assertEquals(ClauseCheck.INSUFFICIENT,
                ClauseCheck.evaluate(clause("c", "x", List.of(), List.of()), PROPOSAL, true).path("status").asText());
    }

    /** The v1 seed clause set must fail against the real proposal text. */
    @Test void ruleV1CriteriaFailAgainstTheProposal() {
        List<Advisory.Clause> v1 = List.of(
                clause("compose-with-asset-layer",
                        "composes with the asset layer",
                        List.of("CAP is designed to sit above existing Canton asset and settlement infrastructure"),
                        List.of("CAP replaces existing Canton asset and settlement infrastructure")),
                clause("independent-security-audit",
                        "must commit to an independent security audit before release",
                        List.of("independent security audit"),
                        List.of("no independent security audit is planned")));
        var findings = ClauseCheck.evaluateAll(v1, PROPOSAL, true);
        assertEquals(ClauseCheck.PASS, findings.get(0).path("status").asText());
        assertEquals(ClauseCheck.FAIL, findings.get(1).path("status").asText());
        assertEquals(Recommendation.Verdict.REJECT, Recommendation.verdict(findings));
    }

    /** The v2 clause set must pass against the same text. */
    @Test void ruleV2CriteriaPassAgainstTheSameProposal() {
        List<Advisory.Clause> v2 = List.of(
                clause("compose-with-asset-layer",
                        "composes with the asset layer",
                        List.of("CAP is designed to sit above existing Canton asset and settlement infrastructure"),
                        List.of("CAP replaces existing Canton asset and settlement infrastructure")),
                clause("auditable-state-and-acceptance",
                        "auditable contract state and a stated acceptance bar",
                        List.of("auditable contract state", "Acceptance Criteria"),
                        List.of("contract state is opaque")));
        var findings = ClauseCheck.evaluateAll(v2, PROPOSAL, true);
        for (var finding : findings)
            assertEquals(ClauseCheck.PASS, finding.path("status").asText(), finding.toString());
        assertEquals(Recommendation.Verdict.APPROVE, Recommendation.verdict(findings));
    }
}
