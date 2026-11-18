package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationTest {
    private JsonNode findings(String... statuses) {
        ArrayNode array = Advisory.JSON.createArrayNode();
        int index = 0;
        for (String status : statuses)
            array.add(Advisory.JSON.createObjectNode().put("clause_id", "clause-" + index++).put("status", status));
        return array;
    }

    private ObjectNode review(String kind, int number, boolean pinned, String revision, String... statuses) {
        ObjectNode review = Advisory.JSON.createObjectNode()
                .put("kind", kind).put("number", number).put("pinned", pinned).put("revision", revision);
        review.set("findings", findings(statuses));
        return review;
    }

    @Test void mappingIsConservativeAndWorstSignalWins() {
        assertEquals(Recommendation.Verdict.APPROVE, Recommendation.verdict(findings("pass", "pass")));
        assertEquals(Recommendation.Verdict.HOLD, Recommendation.verdict(findings("pass", "insufficient_evidence")));
        assertEquals(Recommendation.Verdict.REJECT, Recommendation.verdict(findings("pass", "fail")));
        // A failure outranks missing evidence.
        assertEquals(Recommendation.Verdict.REJECT, Recommendation.verdict(findings("insufficient_evidence", "fail")));
        assertEquals(Recommendation.Verdict.BLOCKED, Recommendation.verdict(findings()));
        assertEquals(Recommendation.Verdict.BLOCKED, Recommendation.verdict(null));
        assertEquals(Recommendation.Verdict.BLOCKED,
                Recommendation.verdict(Advisory.JSON.createObjectNode().put("not", "an array")));
        // An unknown status must never be read as approval.
        assertEquals(Recommendation.Verdict.HOLD, Recommendation.verdict(findings("pass", "surprising")));
    }

    @Test void lineCitesRuleVersionEvidencePinAndAdvisoryLabel() {
        String sha = "f591787a772848e4537aba5d8ba02be1764647da";
        String line = Recommendation.line(review("proposal_pr", 184, true, sha, "pass", "pass"),
                2, "architecture-review", "00abcdef1234567890", "run:grant", true);
        assertTrue(line.startsWith("[reviewer] RECOMMENDATION proposal_pr run:grant"), line);
        assertTrue(line.contains("under architecture-review v2"), line);
        assertTrue(line.contains("(cid 00abcdef1234\u2026)"), line);
        assertTrue(line.contains("APPROVE"), line);
        assertTrue(line.contains("clause-0: pass; clause-1: pass"), line);
        assertTrue(line.contains("offline pinned PR#184 blob f591787a77\u2026"), line);
        assertTrue(line.contains("(advisory only, members decide)"), line);
        // Underscores in a status are rendered readably.
        String hold = Recommendation.line(review("milestone_issue", 540, true, sha, "insufficient_evidence", "pass"),
                2, "architecture-review", "00abcdef1234567890", "run:milestone", true);
        assertTrue(hold.contains("HOLD"), hold);
        assertTrue(hold.contains("clause-0: insufficient evidence"), hold);
    }

    @Test void unpinnedEvidenceIsLabelledAndNeverClaimsARevision() {
        String line = Recommendation.line(review("milestone_issue", 540, false, "", "insufficient_evidence"),
                2, "architecture-review", "00ab", "run:milestone", false);
        assertTrue(line.contains("unpinned issue body (no immutable revision)"), line);
        assertFalse(line.contains("blob"), line);
    }

    private static final String PR_URL = "https://github.com/canton-foundation/canton-dev-fund/pull/184";
    private static final String ISSUE_URL = "https://github.com/canton-foundation/canton-dev-fund/issues/540";

    private ObjectNode cycle() {
        ObjectNode result = Advisory.JSON.createObjectNode()
                .put("rule_version", 2).put("rule_id", "architecture-review")
                .put("rule_contract_id", "00rule").put("discovery", "offline_pinned_pr184_seed_not_fresh_github_poll");
        ObjectNode pr = review("proposal_pr", 184, true, "a".repeat(40), "pass");
        pr.put("url", PR_URL);
        ObjectNode milestone = review("milestone_issue", 540, false, "", "insufficient_evidence");
        milestone.put("url", ISSUE_URL);
        result.putArray("reviews").add(pr).add(milestone);
        result.putArray("open_agendas");
        return result;
    }

    @Test void deduplicatesPerAgendaPerRuleVersion() {
        ObjectNode result = cycle();
        ((ArrayNode) result.path("open_agendas")).add(Advisory.JSON.createObjectNode()
                .put("round_id", "run:grant").put("subject_url", PR_URL).put("subject_body", "a".repeat(40)));
        Set<String> emitted = new HashSet<>();
        assertEquals(1, Recommendation.lines(result, emitted).size());
        // Re-polling the same agenda under the same rule version prints nothing new.
        assertEquals(0, Recommendation.lines(result, emitted).size());
        assertEquals(0, Recommendation.lines(result, emitted).size());
        // A new rule version is a new recommendation.
        result.put("rule_version", 3).put("rule_contract_id", "00rule-v3");
        assertEquals(1, Recommendation.lines(result, emitted).size());
        // A second open agenda is also new.
        ((ArrayNode) result.path("open_agendas")).add(Advisory.JSON.createObjectNode()
                .put("round_id", "run:milestone").put("subject_url", ISSUE_URL).put("subject_body", "illustrative-v1"));
        List<String> next = Recommendation.lines(result, emitted);
        assertEquals(1, next.size());
        assertTrue(next.get(0).contains("run:milestone"), next.get(0));
    }

    /** An agenda must be judged on its own subject's evidence, never another subject's. */
    @Test void agendaIsPairedWithItsOwnSubjectEvidence() {
        ObjectNode result = cycle();
        ((ArrayNode) result.path("open_agendas"))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:grant")
                        .put("subject_url", PR_URL).put("subject_body", "a".repeat(40)))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:milestone")
                        .put("subject_url", ISSUE_URL).put("subject_body", "illustrative-v1"));
        List<String> lines = Recommendation.lines(result, new HashSet<>());
        assertEquals(2, lines.size());
        String grant = lines.stream().filter(l -> l.contains("run:grant")).findFirst().orElseThrow();
        String milestone = lines.stream().filter(l -> l.contains("run:milestone")).findFirst().orElseThrow();
        assertTrue(grant.contains("APPROVE"), grant);
        // The milestone must not inherit the PR's APPROVE.
        assertFalse(milestone.contains("APPROVE"), milestone);
    }

    @Test void agendaWithoutMatchingEvidenceIsBlockedNotApproved() {
        ObjectNode result = cycle();
        ((ArrayNode) result.path("open_agendas")).add(Advisory.JSON.createObjectNode()
                .put("round_id", "run:architecture")
                .put("subject_url", "https://github.com/canton-foundation/canton-dev-fund/pull/999")
                .put("subject_body", "a".repeat(40)));
        List<String> lines = Recommendation.lines(result, new HashSet<>());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("BLOCKED"), lines.get(0));
        assertTrue(lines.get(0).contains("not reviewable against proposal evidence"), lines.get(0));
        assertTrue(lines.get(0).contains("PR#999"), lines.get(0));
        assertTrue(lines.get(0).contains(Recommendation.ADVISORY_LABEL), lines.get(0));
    }

    @Test void noOpenAgendaMeansNoRecommendation() {
        assertTrue(Recommendation.lines(cycle(), new HashSet<>()).isEmpty());
    }

    /**
     * Real agenda shape: a grant agenda records the pinned blob URL while the review records the
     * PR URL plus that blob as source_url. Matching must follow the reviewed artifact.
     */
    @Test void grantAgendaMatchesReviewByPinnedBlobSourceUrl() {
        String blob = "https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc/proposals/concordia.md";
        String sha = "a".repeat(40);
        ObjectNode result = cycle();
        ((ObjectNode) result.path("reviews").get(0)).put("source_url", blob);
        ((ArrayNode) result.path("open_agendas"))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:grant")
                        .put("subject_url", blob).put("subject_body", sha))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:milestone")
                        .put("subject_url", ISSUE_URL).put("subject_body", "illustrative-v1"));
        List<String> lines = Recommendation.lines(result, new HashSet<>());
        String grant = lines.stream().filter(l -> l.contains("run:grant")).findFirst().orElseThrow();
        String milestone = lines.stream().filter(l -> l.contains("run:milestone")).findFirst().orElseThrow();
        assertTrue(grant.contains("APPROVE"), grant);
        // The milestone's own subject has no pinned revision, so it cannot be approved.
        assertFalse(milestone.contains("APPROVE"), milestone);
    }

    /**
     * Regression for a false APPROVE. An Architecture (rule-change) agenda deliberately reuses the
     * pinned proposal's URL, with body "<designText> proposal-blob:<sha>". A URL-only match handed
     * it the PR's passing findings, so rule-v2 and its stale twin were both recommended APPROVE.
     */
    @Test void architectureAgendaIsNotApprovedFromProposalEvidence() {
        String blob = "https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc/proposals/concordia.md";
        String sha = "a".repeat(40);
        ObjectNode result = cycle();
        ((ObjectNode) result.path("reviews").get(0)).put("source_url", blob);
        // Exactly the shape RuleVote.daml builds for an Architecture agenda.
        ((ArrayNode) result.path("open_agendas"))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:rule-v2")
                        .put("subject_url", blob).put("subject_body", "live v2 proposal-blob:" + sha))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:stale")
                        .put("subject_url", blob).put("subject_body", "live v2 proposal-blob:" + sha))
                .add(Advisory.JSON.createObjectNode().put("round_id", "run:grant")
                        .put("subject_url", blob).put("subject_body", sha));
        List<String> lines = Recommendation.lines(result, new HashSet<>());
        assertEquals(3, lines.size());
        for (String line : lines) {
            if (line.contains("run:grant")) {
                assertTrue(line.contains("APPROVE"), line);
            } else {
                assertTrue(line.contains("BLOCKED"), line);
                assertTrue(line.contains("not reviewable against proposal evidence"), line);
                assertFalse(line.contains("APPROVE"), line);
            }
        }
    }

    @Test void subjectBodyMustEqualThePinnedRevisionExactly() {
        String blob = "https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc/proposals/concordia.md";
        String sha = "a".repeat(40);
        ObjectNode result = cycle();
        ((ObjectNode) result.path("reviews").get(0)).put("source_url", blob);
        // A near-miss body (prefix of the pinned SHA) must not match.
        ((ArrayNode) result.path("open_agendas")).add(Advisory.JSON.createObjectNode()
                .put("round_id", "run:near").put("subject_url", blob).put("subject_body", sha.substring(0, 39)));
        assertTrue(Recommendation.lines(result, new HashSet<>()).get(0).contains("BLOCKED"));
        // A missing body cannot match either.
        ObjectNode noBody = cycle();
        ((ObjectNode) noBody.path("reviews").get(0)).put("source_url", blob);
        ((ArrayNode) noBody.path("open_agendas")).add(Advisory.JSON.createObjectNode()
                .put("round_id", "run:nobody").put("subject_url", blob).put("subject_body", ""));
        assertTrue(Recommendation.lines(noBody, new HashSet<>()).get(0).contains("BLOCKED"));
    }

    @Test void noRuleMeansNoRecommendationLine() {
        ObjectNode blocked = Advisory.JSON.createObjectNode().put("status", "BLOCKED");
        blocked.putArray("errors").add("no rule");
        assertTrue(Recommendation.lines(blocked, new HashSet<>()).isEmpty());
    }

    /**
     * The recommendation path must stay text-only: no HTTP, no ledger submission, no file write.
     * This fails if a publication seam is ever added to the type.
     */
    @Test void recommendationHasNoPublicationSeam() {
        for (var method : Recommendation.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("post") || name.contains("publish") || name.contains("submit")
                    || name.contains("comment") || name.contains("write"),
                    "unexpected publication method: " + method.getName());
            assertFalse(method.getReturnType().getName().startsWith("java.net"),
                    "unexpected network return on " + method.getName());
            for (var parameter : method.getParameterTypes()) {
                String type = parameter.getName();
                assertFalse(type.startsWith("java.net") || type.startsWith("java.nio.file")
                        || type.startsWith("java.io"), "unexpected IO parameter on " + method.getName());
            }
        }
        for (var field : Recommendation.class.getDeclaredFields())
            assertFalse(field.getType().getName().startsWith("java.net"), "unexpected network field");
    }
}
