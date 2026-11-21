// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a Reviewer cycle result into the handful of sentences a demo audience can follow.
 *
 * <p>Every line is derived from the result the agent already computed: the rule it actually read
 * from the ledger, the findings its own clause evaluation produced, and the agendas it actually
 * saw. Nothing is restated from a constant, so a line cannot claim a verdict the evaluation did
 * not reach.
 *
 * <p>Two kinds of output are deliberately kept apart, because they answer different questions:
 *
 * <ul>
 *   <li><strong>Continuous evaluation.</strong> The agent evaluates the pinned proposal against
 *       the active rule on every cycle, whether or not anything is being voted on. That is what
 *       prints {@code REJECT} under rule v1 before step 2 opens any agenda at all, and it is the
 *       contrast the demo is built around.
 *   <li><strong>Agenda recommendation.</strong> Advice about a specific open round, which only
 *       exists while a round is open and must be about that round's own subject.
 * </ul>
 *
 * <p>Both are deduplicated on their own meaning, so a ten-second poll does not reprint an answer
 * that has not moved, while a genuine change always prints.
 */
final class ReviewerNarration {
    private ReviewerNarration() { }

    static void report(DemoLog demo, JsonNode result) {
        if (demo == null || !demo.enabled() || result == null) return;
        if (!result.has("rule_version")) {
            // No verified active rule. A thrown failure was already reported by
            // the caller under the cycle category, so only a non-throwing
            // shortfall needs stating here, and it belongs to the same category
            // so the two cannot each clear the other. This one IS specifically
            // about the ledger rule, so it says so.
            if (!demo.failing(DemoLog.CYCLE))
                demo.failed(DemoLog.CYCLE, "Waiting: " + DemoLog.trim(result.path("reason")
                        .asText("no verified active rule on the ledger yet")));
            return;
        }
        int ruleVersion = result.path("rule_version").asInt();
        String ruleId = Advisory.value(result, "rule_id");
        String ruleContractId = Advisory.value(result, "rule_contract_id");
        continuous(demo, result, ruleVersion, ruleContractId);
        agendas(demo, result, ruleVersion, ruleId, ruleContractId);
        // Evidence health is assessed last, after the reviews, and reported
        // under its own category. The ledger can be perfectly readable while
        // the evidence behind every review is unverifiable, and that case used
        // to be silent: with a valid rule, a tampered blob and no open agenda,
        // the cycle produced errors and no reviews, printed nothing at all, and
        // left an earlier APPROVE standing as the audience's impression.
        evidence(demo, result, ruleVersion);
    }

    /**
     * Reports the collector's own non-throwing errors, and withdraws any stale verdict.
     *
     * <p>{@link Advisory#collect} does not throw when the proposal's Git blob digest mismatches,
     * when the PR identity cannot be verified, or when the blob response is unverified: it records
     * the reason in {@code errors} and omits the input. The reviews array is then short or empty,
     * so nothing in the evaluation path has anything to say, and in the default demo mode the JSON
     * and activity lines that would otherwise carry {@code errors} are suppressed. Silence there
     * is the worst possible outcome, because the previous cycle's APPROVE stays on screen as if it
     * still applied.
     *
     * <p>So two things happen here. The failure is stated once under
     * {@link DemoLog#EVIDENCE}, independently of the ledger and agenda categories. And when a
     * subject that was previously evaluated no longer has verifiable evidence, its standing
     * verdict is explicitly withdrawn and replaced with BLOCKED, so no false approval is ever left
     * in place. Classification is by what the error says, and anything unrecognized is treated as
     * a verification failure rather than as the benign expected shortfall.
     */
    private static void evidence(DemoLog demo, JsonNode result, int ruleVersion) {
        JsonNode errors = result.path("errors");
        List<String> serious = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                String text = DemoLog.trim(error.asText());
                if (text.isBlank()) continue;
                if (isExpectedShortfall(text)) expected.add(text);
                else serious.add(text);
            }
        }
        if (!serious.isEmpty()) {
            // A verification failure withdraws the standing verdict for every
            // subject that is no longer backed by verified evidence. Reviews
            // that DID survive this cycle keep their own fresh result, which
            // `continuous` has already printed above.
            Set<String> reviewed = new HashSet<>();
            for (JsonNode review : result.path("reviews"))
                reviewed.add("review:" + Advisory.value(review, "kind") + ":" + Advisory.number(review));
            demo.withdraw(reviewed, label -> "Previous finding for " + label
                    + " is no longer current: its evidence could not be verified.");
            demo.failed(DemoLog.EVIDENCE, "Result: BLOCKED \u2014 evidence verification failed: "
                    + String.join("; ", serious) + ".");
            // The benign-note category is deliberately left untouched while a
            // verification failure is outstanding: a note must neither mask the
            // failure nor flap underneath it.
            return;
        }
        // Verification is sound this cycle. That clearance is announced before
        // any note, so a listener hears "verified again" and then the routine
        // shortfall, never the shortfall alone standing in for recovery.
        demo.recovered(DemoLog.EVIDENCE, "Evidence verified again; findings above are current.");
        // The expected shortfall is tracked in its own category. Sharing one
        // category with verification failures meant a cycle that recovered into
        // a routine note could never announce the recovery at all.
        if (!expected.isEmpty())
            demo.failed(DemoLog.EVIDENCE_NOTE, "Note: " + String.join("; ", expected) + ".");
        else demo.recovered(DemoLog.EVIDENCE_NOTE, "All expected evidence is present again.");
    }

    /**
     * Whether an error is the demo's known, benign shortfall rather than a verification failure.
     *
     * <p>Deliberately a narrow allowlist. The default offline seed is PR-only, so it legitimately
     * finds no milestone issue, and that is the single case treated as expected. Everything else,
     * including any wording this method does not recognize, is a verification failure: failing
     * closed here is what stops a digest mismatch from being presented as routine.
     */
    static boolean isExpectedShortfall(String error) {
        String text = error.toLowerCase(java.util.Locale.ROOT);
        return text.contains("no concordia milestone issues discovered")
                || text.startsWith("milestone issue #") && text.contains("lacks revision time");
    }

    /**
     * The standing evaluation of every reviewed subject against the active rule.
     *
     * <p>The dedup key carries the rule contract and version and the evidence revision as well as
     * the verdict, so activating a new rule reprints even when the verdict happens to be the same,
     * and a changed verdict always reprints.
     */
    private static void continuous(DemoLog demo, JsonNode result, int ruleVersion, String ruleContractId) {
        for (JsonNode review : result.path("reviews")) {
            JsonNode findings = review.path("findings");
            Recommendation.Verdict verdict = Recommendation.verdict(findings);
            String label = DemoLog.subjectLabel(review);
            String key = "review:" + Advisory.value(review, "kind") + ":" + Advisory.number(review);
            String state = ruleContractId + "|v" + ruleVersion + "|"
                    + Advisory.value(review, "revision") + "|" + Advisory.value(review, "body_sha256")
                    + "|" + verdict + "|" + DemoLog.reason(verdict, findings);
            demo.evaluated(key, label, "using rule v" + ruleVersion, state,
                    verdict.name(), DemoLog.reason(verdict, findings));
        }
    }

    /**
     * Advice on each open agenda, matched to the review of its own subject.
     *
     * <p>An agenda with no review of its own subject is reported BLOCKED, never approved from
     * somebody else's evidence: a rule-change agenda reuses the proposal's URL while carrying its
     * own design text, so it must not inherit the proposal's passing findings.
     */
    private static void agendas(DemoLog demo, JsonNode result, int ruleVersion, String ruleId,
                                String ruleContractId) {
        for (JsonNode agenda : result.path("open_agendas")) {
            String roundId = Advisory.value(agenda, "round_id");
            if (roundId.isBlank()) continue;
            String round = DemoLog.roundLabel(roundId);
            String subjectUrl = Advisory.value(agenda, "subject_url");
            String subjectBody = Advisory.value(agenda, "subject_body");
            JsonNode review = Recommendation.match(result.path("reviews"), subjectUrl, subjectBody);
            // Identity is the agenda contract itself plus the subject it puts
            // to the vote, not the round identifier alone. A round ID is a
            // caller-chosen label: two distinct AgendaProposal contracts can
            // carry the same one, and suppressing the second because the first
            // was already reported would hide a genuinely different agenda.
            // The contract ID is immutable and unique, so it leads the key, and
            // the subject is included so that advice is never deduplicated
            // across different things being decided.
            String key = agendaKey(agenda, roundId, subjectUrl, subjectBody);
            if (review == null) {
                // Not reviewable is not the same as rejected, and the wording
                // says so: there is no evidence about this subject to judge.
                String subject = DemoLog.subjectLabelFromUrl(subjectUrl);
                demo.evaluated(key, round, "(" + subject + ")",
                        ruleContractId + "|v" + ruleVersion + "|blocked",
                        Recommendation.Verdict.BLOCKED.name(),
                        "no verified evidence about this subject to review");
                continue;
            }
            JsonNode findings = review.path("findings");
            Recommendation.Verdict verdict = Recommendation.verdict(findings);
            String reason = DemoLog.reason(verdict, findings);
            // The evidence pin joins the rule identity in the state key: the
            // same agenda judged against re-pinned evidence is a new answer.
            demo.evaluated(key, round, "on " + DemoLog.subjectLabel(review)
                            + " using rule v" + ruleVersion,
                    ruleContractId + "|v" + ruleVersion + "|"
                            + Advisory.value(review, "revision") + "|"
                            + Advisory.value(review, "body_sha256") + "|"
                            + verdict + "|" + reason,
                    verdict.name(), reason + "; advisory only, members decide");
        }
        // Discovery that degraded is reported, not hidden: advice the agent
        // could not form is different from advice it formed and withheld.
        //
        // This is its own health category. The rule read can succeed while
        // agenda discovery fails, so clearing it must not depend on, or
        // interfere with, the ledger category.
        String degraded = Advisory.value(result, "open_agenda_discovery");
        if (!degraded.isBlank()) {
            // The JSON field already prefixes its value with "unavailable: ";
            // that field is a machine contract and is left exactly as it is,
            // but the spoken sentence must not read "unavailable: unavailable:".
            String detail = DemoLog.trim(degraded);
            if (detail.startsWith("unavailable: ")) detail = detail.substring("unavailable: ".length());
            demo.failed(DemoLog.AGENDA_DISCOVERY, "Agenda discovery unavailable: " + detail);
        } else demo.recovered(DemoLog.AGENDA_DISCOVERY, "Agenda discovery available again.");
    }

    /**
     * Deduplication identity of one agenda: its immutable contract ID, its round label, and the
     * subject it puts to the vote.
     *
     * <p>The contract ID is the only field that cannot be reused for a different agenda, so it
     * leads. The round ID and subject follow so that an agenda whose contract ID is missing from a
     * payload still has a usable identity, and so that two agendas sharing a contract ID could
     * never collapse across different subjects. This affects only which lines are reprinted; it is
     * not a security boundary and does not influence any verdict.
     */
    static String agendaKey(JsonNode agenda, String roundId, String subjectUrl, String subjectBody) {
        String contractId = Advisory.value(agenda, "contract_id");
        return "agenda:" + (contractId.isBlank() ? "no-cid" : contractId)
                + "|" + roundId + "|" + subjectUrl + "|" + subjectBody;
    }
}
